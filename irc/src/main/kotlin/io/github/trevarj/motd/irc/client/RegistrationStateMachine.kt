package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport

/**
 * Drives IRC registration (steps 1-11): optional PASS → CAP LS 302 → CAP REQ/ACK → SASL →
 * optional BOUNCER BIND → CAP END → 001/005 → Ready. Message-driven and side-effect free:
 * [start] returns the opening lines; [onMessage] returns [Action]s for each inbound message.
 *
 * The client is responsible for actually sending [Action.Send] lines, applying [Action.SetNick],
 * and reacting to the terminal [Action.Complete] / [Action.Fail].
 */
internal class RegistrationStateMachine(
    private val config: IrcClientConfig,
    private val claimNickRecovery: () -> Boolean = { true },
) {
    sealed interface Action {
        data class Send(
            val line: String,
        ) : Action

        /** Our nick changed (initial, or after a 433 retry) — client must update self-nick. */
        data class SetNick(
            val nick: String,
        ) : Action

        /**
         * Send once the connection proves it is flowing again after registration — the first
         * post-Complete server line — or after [delayMs] at the latest. The ceiling exists for the
         * soju child fallback: the post-BIND capability mutation can stall the welcome burst on the
         * embedded transport, and these deferred feature REQs must not be interleaved with that
         * mutation window. A line arriving after Complete is proof the stall did not happen (or has
         * cleared), so the client sends immediately then instead of blindly waiting the ceiling.
         */
        data class SendDeferred(
            val line: String,
            val delayMs: Long,
        ) : Action

        data class Emit(
            val event: IrcEvent,
        ) : Action

        /** Registration succeeded. */
        data class Complete(
            val nick: String,
            val ackedCaps: Set<String>,
            val isupport: Isupport,
            /** Soju child fallback: the stalled welcome burst requires protocol-default CHANTYPES. */
            val assumeDefaultTargetClassification: Boolean = false,
            /** Capabilities requested after Ready whose ACK/NAK decision is still outstanding. */
            val deferredCaps: Set<String> = emptySet(),
        ) : Action {
            val caps = CapNegotiator.usableCaps(ackedCaps)
        }

        /** Registration failed terminally. */
        data class Fail(
            val reason: String,
            val fatal: Boolean,
        ) : Action
    }

    private enum class Phase { INIT, CAP_LS, CAP_REQ, SASL, BIND, CAP_END, WELCOME, DONE, FAILED }

    private var phase = Phase.INIT
    private var nick = config.nick
    private var nickTries = 0

    // Advertised caps from CAP LS: name -> value ("" when valueless).
    private val advertised = LinkedHashMap<String, String>()

    // ACKed caps, encoded as "name=value" when the LS carried a value (sts surfacing).
    private val acked = LinkedHashSet<String>()
    private var requestedBatches = 0
    private var ackedBatches = 0
    private val postWelcomeCapReqs = ArrayList<String>()
    private var batchNew = false
    private var labeledResponseNew = false
    private var metadataNew = false

    private val isupport = Isupport()
    private var sasl: SaslAuthenticator? = null
    private var preAwayAttempted = false
    private var preAwayRejected = false

    /** Opening lines: optional PASS, then CAP LS 302, NICK, USER. */
    fun start(): List<Action> {
        if (config.sasl == SaslMechanism.NONE && !config.nickServPassword.isNullOrEmpty()) {
            runCatching {
                nickServIdentifyLine(config)
                nickServRecoveryLines(config)
            }.onFailure {
                return fail("invalid NickServ configuration", fatal = true)
            }
        }
        val actions = mutableListOf<Action>(Action.SetNick(nick))
        config.serverPassword?.takeIf { it.isNotEmpty() }?.let { password ->
            val passLine =
                runCatching {
                    IrcMessage(command = "PASS", params = listOf(password)).serialize()
                }.getOrElse {
                    return fail("invalid server password", fatal = true)
                }
            actions += Action.Send(passLine)
        }
        actions += Action.Send("CAP LS 302")
        actions += Action.Send("NICK $nick")
        actions += Action.Send("USER ${config.username} 0 * :${config.realname}")
        return actions
    }

    fun onMessage(msg: IrcMessage): List<Action> =
        when (msg.command) {
            "CAP" -> {
                onCap(msg)
            }

            "AUTHENTICATE", "900", "901", "902", "903", "904", "905", "906", "907" -> {
                onSasl(msg)
            }

            "464" -> {
                fail("server password rejected", fatal = true)
            }

            "432", "433", "436" -> {
                onNickError(msg)
            }

            "001" -> {
                onWelcome(msg)
            }

            "005" -> {
                onIsupport(msg)
                emptyList()
            }

            "FAIL" -> {
                onFail(msg)
            }

            "376", "422" -> {
                emptyList()
            }

            // end of motd; 001 already completed us
            "PING" -> {
                listOf(Action.Send("PONG ${msg.params.firstOrNull().orEmpty()}"))
            }

            "ERROR" -> {
                fail("server ERROR: ${msg.params.lastOrNull().orEmpty()}", fatal = false)
            }

            else -> {
                emptyList()
            }
        }

    private fun onCap(msg: IrcMessage): List<Action> {
        // params: <nick|*> <subcommand> [*] :<caps>
        val sub = msg.params.getOrNull(1) ?: return emptyList()
        return when (sub) {
            "LS" -> onCapLs(msg)
            "ACK" -> onCapAck(msg)
            "NAK" -> onCapNak(msg)
            "DEL", "NEW" -> onCapChangedBeforeWelcome(msg)
            else -> emptyList()
        }
    }

    private fun onCapLs(msg: IrcMessage): List<Action> {
        // Multiline: params[2] == "*" means more coming, caps in the trailing param.
        val multiline = msg.params.getOrNull(2) == "*"
        val capsText = msg.params.last()
        parseAdvertised(capsText)
        if (multiline) return emptyList()

        // Full LS received: compute request set and send REQs.
        phase = Phase.CAP_REQ
        batchNew = false
        labeledResponseNew = false
        val desired = CapNegotiator.requestSet(advertised.keys, config.extraCaps)
        val req =
            if (isBouncerChildRegistration()) {
                // soju mutates the capability set when a downstream selects a bouncer network.
                // Requesting the full feature set before network selection can leave Android's embedded
                // transport stuck before welcome. Keep ordinary features deferred, but negotiate the
                // replay-safety barrier now so soju does not deliver its legacy backlog as live traffic.
                val preBind =
                    buildList {
                        add("sasl")
                        if (config.bouncerNetId != null) add("soju.im/bouncer-networks")
                        add("draft/chathistory")
                        add("batch")
                        add("message-tags")
                        addAll(SERVER_TIME_ALIASES)
                    }.filterTo(linkedSetOf()) { it in desired }
                postWelcomeCapReqs.clear()
                postWelcomeCapReqs.addAll(deferredFeatureCaps(desired - preBind))
                preBind
            } else {
                desired
            }
        if (req.isEmpty()) return advanceAfterCaps()
        val batches = CapNegotiator.batches(req)
        requestedBatches = batches.size
        return batches.map { Action.Send("CAP REQ :$it") }
    }

    private fun onCapAck(msg: IrcMessage): List<Action> {
        val caps =
            msg.params
                .last()
                .split(' ')
                .filter { it.isNotEmpty() }
        for (c in caps) recordAcked(c)
        ackedBatches++
        return maybeAfterReq()
    }

    private fun onCapNak(msg: IrcMessage): List<Action> {
        // A NAK batch still counts toward completion; we just don't record those caps.
        ackedBatches++
        return maybeAfterReq()
    }

    private fun maybeAfterReq(): List<Action> {
        if (phase != Phase.CAP_REQ || ackedBatches < requestedBatches) return emptyList()
        return advanceAfterCaps()
    }

    /** After all CAP REQ/ACK: optionally SASL, else BIND, else CAP END. */
    private fun advanceAfterCaps(): List<Action> {
        // SASL required but cap absent → fatal (never fall back to cleartext).
        if (config.sasl != SaslMechanism.NONE) {
            if (!hasAcked("sasl")) {
                return fail("SASL required but server did not offer it", fatal = true)
            }
            phase = Phase.SASL
            val authenticator = SaslAuthenticator(config.sasl, config.saslUser, config.saslPassword)
            sasl = authenticator
            return listOf(Action.Send(authenticator.begin()))
        }
        return afterSasl()
    }

    private fun onSasl(msg: IrcMessage): List<Action> {
        val s = sasl ?: return emptyList()
        return when (val step = s.onMessage(msg)) {
            is SaslAuthenticator.Step.Send -> step.lines.map { Action.Send(it) }
            SaslAuthenticator.Step.Done -> afterSasl()
            is SaslAuthenticator.Step.Failed -> fail(step.reason, fatal = true)
            SaslAuthenticator.Step.Ignore -> emptyList()
        }
    }

    /** After SASL (or when none): BOUNCER BIND before CAP END, then CAP END. */
    private fun afterSasl(): List<Action> {
        val actions = mutableListOf<Action>()
        if (config.bouncerNetId != null && hasAcked("soju.im/bouncer-networks")) {
            phase = Phase.BIND
            actions.add(Action.Send("BOUNCER BIND ${config.bouncerNetId}"))
        }
        initialAwayLine()?.takeIf { hasAcked("draft/pre-away") }?.let { line ->
            preAwayAttempted = true
            actions.add(Action.Send(line))
        }
        phase = Phase.CAP_END
        actions.add(Action.Send("CAP END"))
        phase = Phase.WELCOME
        return actions
    }

    private fun onNickError(msg: IrcMessage): List<Action> {
        if (phase == Phase.WELCOME || phase == Phase.DONE) return emptyList()
        if (nickTries >= 3) {
            return fail("nick unavailable after retries: $nick", fatal = false)
        }
        nickTries++
        nick += "_"
        return listOf(Action.SetNick(nick), Action.Send("NICK $nick"))
    }

    private fun onWelcome(msg: IrcMessage): List<Action> {
        // 001 <nick> :Welcome — server's canonical nick wins.
        nick = msg.params.firstOrNull() ?: nick
        phase = Phase.DONE
        val actions = mutableListOf<Action>(Action.SetNick(nick))
        if (config.sasl == SaslMechanism.NONE && !config.nickServPassword.isNullOrEmpty()) {
            actions += Action.Send(nickServIdentifyLine(config))
            val preferredUnavailable = isupport.normalize(nick) != isupport.normalize(config.nick)
            if (preferredUnavailable && config.nickServRecoveryCommands.isNotEmpty() && claimNickRecovery()) {
                actions += nickServRecoveryLines(config).map(Action::Send)
            }
        }
        val deferredCaps = deferredCaps()
        actions +=
            Action.Complete(
                nick,
                acked.toSet(),
                isupport,
                deferredCaps = deferredCaps,
            )
        val fallbackAwayLine = initialAwayLine()
        if (fallbackAwayLine != null && (!preAwayAttempted || preAwayRejected)) {
            actions.add(Action.Send(fallbackAwayLine))
        }
        for (cap in deferredCaps.sorted()) {
            actions.add(Action.Send("CAP REQ :$cap"))
        }
        return actions
    }

    private fun onFail(msg: IrcMessage): List<Action> {
        if (preAwayAttempted && phase == Phase.WELCOME && msg.params.firstOrNull()?.equals("AWAY", ignoreCase = true) == true) {
            preAwayRejected = true
            val code = msg.params.getOrNull(1) ?: "FAIL"
            return listOf(
                Action.Emit(
                    IrcEvent.StandardReply(
                        ctx =
                            io.github.trevarj.motd.irc.event.MessageContext(
                                msgid = msg.tags["msgid"],
                                serverTime = System.currentTimeMillis(),
                                account = msg.tags["account"],
                                batchId = msg.tags["batch"],
                                label = msg.tags["label"],
                                serverTimeSource = io.github.trevarj.motd.irc.event.ServerTimeSource.LOCAL,
                            ),
                        severity = IrcEvent.StandardReplySeverity.FAIL,
                        commandName = "AWAY",
                        code = code,
                        context = if (msg.params.size <= 3) emptyList() else msg.params.subList(2, msg.params.lastIndex),
                        description = msg.params.lastOrNull().orEmpty(),
                    ),
                ),
            )
        }
        return fail(
            msg.params
                .drop(1)
                .joinToString(" ")
                .ifBlank { "registration failed" },
            fatal = true,
        )
    }

    private fun initialAwayLine(): String? {
        val message = config.initialAwayMessage?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            IrcMessage(command = "AWAY", params = listOf(message)).serialize()
        }.getOrNull()
    }

    /**
     * soju emits CAP DEL/NEW immediately after a bouncer-network child is selected. On Android's
     * embedded libbox path the final welcome burst can stall after this mutation even though soju
     * has completed registration server-side. For bouncer children only, the mutation after CAP END
     * is sufficient proof that SASL and network selection succeeded; mark the child Ready so the
     * app can use the live socket. Ordinary IRC connections still require 001.
     */
    private fun onCapChangedBeforeWelcome(msg: IrcMessage): List<Action> {
        val subcommand = msg.params.getOrNull(1)
        val tokens =
            msg.params
                .lastOrNull()
                ?.split(' ')
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        val names =
            tokens.mapTo(linkedSetOf()) {
                it
                    .removePrefix("-")
                    .removePrefix("=")
                    .removePrefix("~")
                    .substringBefore('=')
            }
        when (subcommand) {
            "NEW" -> {
                parseAdvertised(tokens.joinToString(" "))
                if ("batch" in names) batchNew = true
                if ("labeled-response" in names) labeledResponseNew = true
                for (alias in METADATA_CAP_ALIASES) {
                    if (alias !in names) continue
                    metadataNew = true
                    if (hasAcked(alias)) {
                        acked.removeAll { it.substringBefore('=') == alias }
                        recordAcked(alias)
                    }
                }
                if (phase != Phase.WELCOME || !isBouncerChildRegistration()) return emptyList()
                val newlyDesired =
                    CapNegotiator.runtimeRequestSet(
                        names,
                        acked,
                        config.extraCaps,
                    )
                postWelcomeCapReqs.addAll(deferredFeatureCaps(newlyDesired))
            }

            "DEL" -> {
                names.forEach(advertised::remove)
                acked.removeAll { it.substringBefore('=') in names }
                postWelcomeCapReqs.removeAll { it in names }
            }
        }
        if (phase != Phase.WELCOME || !isBouncerChildRegistration()) return emptyList()

        phase = Phase.DONE
        val deferredCaps = deferredCaps()
        val actions =
            mutableListOf<Action>(
                Action.SetNick(nick),
                Action.Complete(
                    nick,
                    acked.toSet(),
                    isupport,
                    assumeDefaultTargetClassification = true,
                    deferredCaps = deferredCaps,
                ),
            )
        // Soju can remove capabilities while BOUNCER BIND selects the upstream network. Request
        // each deferred capability separately so one stale capability can be NAKed without also
        // rejecting unrelated features such as draft/read-marker.
        for (cap in deferredCaps.sorted()) {
            actions.add(Action.SendDeferred("CAP REQ :$cap", FALLBACK_FEATURE_CAP_DELAY_MS))
        }
        return actions
    }

    private fun onIsupport(msg: IrcMessage) {
        // params: <nick> <token...> :are supported... — drop first (nick) and last (text).
        val tokens = msg.params.drop(1).dropLast(1)
        isupport.update(tokens)
    }

    private fun fail(
        reason: String,
        fatal: Boolean,
    ): List<Action> {
        phase = Phase.FAILED
        return listOf(Action.Fail(reason, fatal))
    }

    // -- cap bookkeeping --

    private fun parseAdvertised(capsText: String) {
        for (token in capsText.split(' ')) {
            if (token.isEmpty()) continue
            val eq = token.indexOf('=')
            if (eq < 0) advertised[token] = "" else advertised[token.substring(0, eq)] = token.substring(eq + 1)
        }
    }

    /** Record an ACKed cap, encoding its LS value as `name=value` when present. */
    private fun recordAcked(cap: String) {
        val name = cap.removePrefix("-").removePrefix("=").removePrefix("~")
        if (cap.startsWith('-')) {
            acked.removeAll { it.substringBefore('=') == name }
            return
        }
        val value = advertised[name]
        if (!value.isNullOrEmpty()) acked.add("$name=$value") else acked.add(name)
    }

    private fun hasAcked(name: String): Boolean = acked.any { it == name || it.startsWith("$name=") }

    private fun isBouncerChildRegistration(): Boolean = config.bouncerNetId != null || config.saslUser?.contains('/') == true

    private fun deferredCaps(): Set<String> =
        buildSet {
            addAll(postWelcomeCapReqs)
            if ((isBouncerChildRegistration() || labeledResponseNew) &&
                CapNegotiator.wantsLabeledResponse(advertised.keys, acked)
            ) {
                add("labeled-response")
            } else if (batchNew && "batch" in advertised && !hasAcked("batch")) {
                add("batch")
            }
            if (isBouncerChildRegistration() || metadataNew || batchNew) {
                val metadata =
                    CapNegotiator.runtimeRequestSet(advertised.keys, acked, config.extraCaps).firstOrNull { it in METADATA_CAP_ALIASES }
                if (metadata != null) {
                    add(if (metadata == "metadata-notify" || hasAcked("batch")) metadata else "batch")
                }
            }
        }

    private fun deferredFeatureCaps(caps: Set<String>): Set<String> =
        caps -
            buildSet {
                add("cap-notify")
                add("labeled-response")
                addAll(METADATA_CAP_ALIASES)
                // Slash-auth children are already bound by their SASL authcid and must not BOUNCER BIND.
                if (config.bouncerNetId == null) add("soju.im/bouncer-networks")
            }

    companion object {
        const val FALLBACK_FEATURE_CAP_DELAY_MS = 1_000L
    }
}

private val NICKSERV_COMMAND = Regex("[A-Za-z][A-Za-z0-9-]*")

internal fun nickServIdentifyLine(config: IrcClientConfig): String {
    val password = config.validNickServPassword()
    val body =
        when (config.nickServIdentifySyntax) {
            NickServIdentifySyntax.NICK_PASSWORD -> "IDENTIFY ${config.nick} $password"
            NickServIdentifySyntax.PASSWORD_NICK -> "IDENTIFY $password ${config.nick}"
            NickServIdentifySyntax.PASSWORD_ONLY -> "IDENTIFY $password"
        }
    return nickServMessage(body)
}

/** Recovery service commands followed by the explicit preferred-nick request. */
internal fun nickServRecoveryLines(config: IrcClientConfig): List<String> {
    if (config.nickServRecoveryCommands.isEmpty()) return emptyList()
    val password = config.validNickServPassword()
    return config.nickServRecoveryCommands.map { command ->
        require(NICKSERV_COMMAND.matches(command)) { "invalid NickServ recovery command" }
        nickServMessage("${command.uppercase()} ${config.nick} $password")
    } + IrcMessage(command = "NICK", params = listOf(config.nick)).serialize()
}

private fun IrcClientConfig.validNickServPassword(): String {
    val password = requireNotNull(nickServPassword) { "missing NickServ password" }
    require(password.isNotEmpty() && password.none(Char::isWhitespace)) {
        "invalid NickServ password"
    }
    return password
}

private fun nickServMessage(body: String): String = IrcMessage(command = "PRIVMSG", params = listOf("NickServ", body)).serialize()
