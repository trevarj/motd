package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport

/**
 * Drives IRC registration (plans/02 steps 1-11): CAP LS 302 → CAP REQ/ACK → SASL →
 * optional BOUNCER BIND → CAP END → 001/005 → Ready. Message-driven and side-effect free:
 * [start] returns the opening lines; [onMessage] returns [Action]s for each inbound message.
 *
 * The client is responsible for actually sending [Action.Send] lines, applying [Action.SetNick],
 * and reacting to the terminal [Action.Complete] / [Action.Fail].
 */
internal class RegistrationStateMachine(
    private val config: IrcClientConfig,
) {
    sealed interface Action {
        data class Send(val line: String) : Action
        /** Our nick changed (initial, or after a 433 retry) — client must update self-nick. */
        data class SetNick(val nick: String) : Action
        data class SendDeferred(val line: String, val delayMs: Long) : Action
        /** Registration succeeded. */
        data class Complete(val nick: String, val caps: Set<String>, val isupport: Isupport) : Action
        /** Registration failed terminally. */
        data class Fail(val reason: String, val fatal: Boolean) : Action
    }

    private enum class Phase { INIT, CAP_LS, CAP_REQ, SASL, BIND, CAP_END, WELCOME, DONE, FAILED }

    private var phase = Phase.INIT
    private var nick = config.nick
    private var nickTries = 0

    // Advertised caps from CAP LS: name -> value ("" when valueless).
    private val advertised = LinkedHashMap<String, String>()
    // ACKed caps, encoded as "name=value" when the LS carried a value (plans/03 sts surfacing).
    private val acked = LinkedHashSet<String>()
    private var requestedBatches = 0
    private var ackedBatches = 0
    private val postWelcomeCapReqs = ArrayList<String>()

    private val isupport = Isupport()
    private var sasl: SaslAuthenticator? = null

    /** Opening lines: CAP LS 302, NICK, USER. */
    fun start(): List<Action> = listOf(
        Action.SetNick(nick),
        Action.Send("CAP LS 302"),
        Action.Send("NICK $nick"),
        Action.Send("USER ${config.username} 0 * :${config.realname}"),
    )

    fun onMessage(msg: IrcMessage): List<Action> {
        return when (msg.command) {
            "CAP" -> onCap(msg)
            "AUTHENTICATE", "900", "901", "902", "903", "904", "905", "906", "907" -> onSasl(msg)
            "432", "433", "436" -> onNickError(msg)
            "001" -> onWelcome(msg)
            "005" -> { onIsupport(msg); emptyList() }
            "FAIL" -> fail(msg.params.drop(1).joinToString(" ").ifBlank { "registration failed" }, fatal = true)
            "376", "422" -> emptyList() // end of MOTD; 001 already completed us
            "PING" -> listOf(Action.Send("PONG ${msg.params.firstOrNull().orEmpty()}"))
            "ERROR" -> fail("server ERROR: ${msg.params.lastOrNull().orEmpty()}", fatal = false)
            else -> emptyList()
        }
    }

    private fun onCap(msg: IrcMessage): List<Action> {
        // params: <nick|*> <subcommand> [*] :<caps>
        val sub = msg.params.getOrNull(1) ?: return emptyList()
        return when (sub) {
            "LS" -> onCapLs(msg)
            "ACK" -> onCapAck(msg)
            "NAK" -> onCapNak(msg)
            "DEL", "NEW" -> onCapChangedBeforeWelcome()
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
        val desired = CapNegotiator.requestSet(advertised.keys, config.extraCaps)
        val req = if (isBouncerChildRegistration()) {
            // soju mutates the capability set when a downstream selects a bouncer network.
            // Requesting the full feature set before network selection can leave Android's embedded
            // transport stuck before welcome. Use the minimum caps needed to authenticate/select,
            // then request ordinary feature caps after 001.
            val preBind = setOf("sasl", "soju.im/bouncer-networks")
                .filter { it in desired || it in advertised.keys }
                .filter { it != "soju.im/bouncer-networks" || config.bouncerNetId != null }
                .toSet()
            postWelcomeCapReqs.clear()
            postWelcomeCapReqs.addAll(desired - preBind - "cap-notify")
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
        val caps = msg.params.last().split(' ').filter { it.isNotEmpty() }
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
            sasl = SaslAuthenticator(config.sasl, config.saslUser, config.saslPassword)
            return listOf(Action.Send(sasl!!.begin()))
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
        val actions = mutableListOf<Action>(
            Action.SetNick(nick),
            Action.Complete(nick, acked.toSet(), isupport),
        )
        for (batch in CapNegotiator.batches(postWelcomeCapReqs.toSet())) {
            actions.add(Action.Send("CAP REQ :$batch"))
        }
        return actions
    }

    /**
     * soju emits CAP DEL/NEW immediately after a bouncer-network child is selected. On Android's
     * embedded libbox path the final welcome burst can stall after this mutation even though soju
     * has completed registration server-side. For bouncer children only, the mutation after CAP END
     * is sufficient proof that SASL and network selection succeeded; mark the child Ready so the
     * app can use the live socket. Ordinary IRC connections still require 001.
     */
    private fun onCapChangedBeforeWelcome(): List<Action> {
        if (phase != Phase.WELCOME || !isBouncerChildRegistration()) return emptyList()
        phase = Phase.DONE
        val actions = mutableListOf<Action>(
            Action.SetNick(nick),
            Action.Complete(nick, acked.toSet(), isupport),
        )
        for (batch in CapNegotiator.batches(postWelcomeCapReqs.toSet())) {
            actions.add(Action.SendDeferred("CAP REQ :$batch", FALLBACK_FEATURE_CAP_DELAY_MS))
        }
        return actions
    }

    private fun onIsupport(msg: IrcMessage) {
        // params: <nick> <token...> :are supported... — drop first (nick) and last (text).
        val tokens = msg.params.drop(1).dropLast(1)
        isupport.update(tokens)
    }

    private fun fail(reason: String, fatal: Boolean): List<Action> {
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

    /** Record an ACKed cap, encoding its LS value as `name=value` when present (plans/03). */
    private fun recordAcked(cap: String) {
        // ACK may prefix with '-' to drop; ignore those here (unusual during registration).
        val name = cap.removePrefix("-").removePrefix("=").removePrefix("~")
        val value = advertised[name]
        if (!value.isNullOrEmpty()) acked.add("$name=$value") else acked.add(name)
    }

    private fun hasAcked(name: String): Boolean =
        acked.any { it == name || it.startsWith("$name=") }

    private fun isBouncerChildRegistration(): Boolean =
        config.bouncerNetId != null || config.saslUser?.contains('/') == true

    companion object {
        const val FALLBACK_FEATURE_CAP_DELAY_MS = 1_000L
    }
}
