package io.github.trevarj.motd.bouncer

data class BouncerServCommand(val wire: String) {
    init {
        require('\r' !in wire && '\n' !in wire) { "Exactly one BouncerServ command is allowed" }
        require(wire.isNotBlank()) { "BouncerServ command cannot be blank" }
    }

    val display: String get() = redactBouncerServCommand(wire)
}

data class NetworkCommandFields(
    val address: String? = null,
    val name: String? = null,
    val nick: String? = null,
    val username: String? = null,
    val realName: String? = null,
    val autoAway: Boolean? = null,
    val enabled: Boolean? = null,
    val password: String? = null,
    val connectCommands: List<String>? = null,
)

data class ChannelCommandFields(
    val detached: Boolean? = null,
    val relayDetached: String? = null,
    val reattachOn: String? = null,
    val detachAfter: String? = null,
    val detachOn: String? = null,
)

object BouncerServCommands {
    fun help(path: String? = null) = command("help", path)
    fun networkStatus() = BouncerServCommand("network status")

    fun networkCreate(fields: NetworkCommandFields): BouncerServCommand {
        require(!fields.address.isNullOrBlank()) { "Network address is required" }
        return BouncerServCommand(buildList {
            add("network"); add("create")
            appendNetworkFields(fields, includeAddress = true)
        }.joinToString(" "))
    }

    fun networkUpdate(name: String, changed: NetworkCommandFields): BouncerServCommand =
        BouncerServCommand(buildList {
            add("network"); add("update"); add(quoteBouncerArg(name))
            appendNetworkFields(changed, includeAddress = true)
        }.joinToString(" "))

    fun networkDelete(name: String) = command("network delete", name)

    fun channelStatus(network: String) = BouncerServCommand(
        "channel status -network ${quoteBouncerArg(network)}",
    )

    fun channelCreate(channel: String, network: String, fields: ChannelCommandFields = ChannelCommandFields()) =
        channelMutation("create", channel, network, fields)

    fun channelUpdate(channel: String, network: String, fields: ChannelCommandFields) =
        channelMutation("update", channel, network, fields)

    fun channelDelete(channel: String, network: String) =
        BouncerServCommand("channel delete ${quoteBouncerArg("$channel/$network")}")

    fun accountUpdate(nick: String?, realName: String?) = BouncerServCommand(buildList {
        add("user"); add("update")
        nick?.takeIf(String::isNotBlank)?.let { add("-nick"); add(quoteBouncerArg(it)) }
        realName?.takeIf(String::isNotBlank)?.let { add("-realname"); add(quoteBouncerArg(it)) }
    }.joinToString(" "))

    fun saslStatus(network: String) = rootNetworkCommand("sasl status", network)
    fun saslSetPlain(network: String, username: String, password: String) = BouncerServCommand(
        "sasl set-plain -network ${quoteBouncerArg(network)} " +
            "${quoteBouncerArg(username)} ${quoteBouncerArg(password)}",
    )
    fun saslReset(network: String) = rootNetworkCommand("sasl reset", network)
    fun certFpGenerate(network: String, keyType: String = "ed25519") = BouncerServCommand(
        "certfp generate -network ${quoteBouncerArg(network)} -key-type ${quoteBouncerArg(keyType)}",
    )
    fun certFpFingerprint(network: String) = rootNetworkCommand("certfp fingerprint", network)

    fun userStatus(username: String? = null) = command("user status", username)
    fun userCreate(username: String, password: String, admin: Boolean, enabled: Boolean) = BouncerServCommand(
        "user create -username ${quoteBouncerArg(username)} -password ${quoteBouncerArg(password)} " +
            "-admin $admin -enabled $enabled",
    )
    fun userDelete(username: String, confirmationToken: String? = null) = BouncerServCommand(
        buildList {
            add("user"); add("delete"); add(quoteBouncerArg(username))
            confirmationToken?.takeIf(String::isNotBlank)?.let { add(quoteBouncerArg(it)) }
        }.joinToString(" "),
    )
    fun userRun(username: String, nested: BouncerServCommand) = BouncerServCommand(
        "user run ${quoteBouncerArg(username)} ${nested.wire}",
    )
    fun serverStatus() = BouncerServCommand("server status")
    fun serverNotice(message: String) = command("server notice", message)
    fun serverDebug(enabled: Boolean) = BouncerServCommand("server debug $enabled")

    private fun command(path: String, argument: String?): BouncerServCommand = BouncerServCommand(
        if (argument.isNullOrBlank()) path else "$path ${quoteBouncerArg(argument)}",
    )

    private fun rootNetworkCommand(path: String, network: String) = BouncerServCommand(
        "$path -network ${quoteBouncerArg(network)}",
    )

    private fun channelMutation(
        operation: String,
        channel: String,
        network: String,
        fields: ChannelCommandFields,
    ) = BouncerServCommand(buildList {
        // Root mutations use the channel/network target. soju does not accept -network here.
        add("channel"); add(operation); add(quoteBouncerArg("$channel/$network"))
        fields.detached?.let { add("-detached"); add(it.toString()) }
        fields.relayDetached?.let { add("-relay-detached"); add(quoteBouncerArg(it)) }
        fields.reattachOn?.let { add("-reattach-on"); add(quoteBouncerArg(it)) }
        fields.detachAfter?.let { add("-detach-after"); add(quoteBouncerArg(it)) }
        fields.detachOn?.let { add("-detach-on"); add(quoteBouncerArg(it)) }
    }.joinToString(" "))

    private fun MutableList<String>.appendNetworkFields(fields: NetworkCommandFields, includeAddress: Boolean) {
        if (includeAddress) fields.address?.let { add("-addr"); add(quoteBouncerArg(it)) }
        fields.name?.let { add("-name"); add(quoteBouncerArg(it)) }
        fields.nick?.let { add("-nick"); add(quoteBouncerArg(it)) }
        fields.username?.let { add("-username"); add(quoteBouncerArg(it)) }
        fields.realName?.let { add("-realname"); add(quoteBouncerArg(it)) }
        fields.autoAway?.let { add("-auto-away"); add(it.toString()) }
        fields.enabled?.let { add("-enabled"); add(it.toString()) }
        fields.password?.let { add("-pass"); add(quoteBouncerArg(it)) }
        fields.connectCommands?.forEach { add("-connect-command"); add(quoteBouncerArg(it)) }
    }
}

fun quoteBouncerArg(value: String): String {
    if (value.isEmpty()) return "''"
    if (value.all { it.isLetterOrDigit() || it in "-._:/#@+" }) return value
    return "'" + value.replace("'", "'\\''") + "'"
}
