package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.Isupport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class SelfChannelPrivileges {
    private val _modes = MutableStateFlow<Map<String, Set<Char>>>(emptyMap())
    val modes = _modes.asStateFlow()
    private val namesDeltas = mutableMapOf<String, MutableMap<Char, Boolean>>()

    @Synchronized
    fun clear() {
        namesDeltas.clear()
        _modes.value = emptyMap()
    }

    @Synchronized
    fun apply(
        event: IrcEvent,
        support: Isupport,
        nick: String,
        joined: Set<String>,
    ) {
        fun isSelf(raw: String) = support.normalize(raw) == support.normalize(nick)

        fun joinedTarget(raw: String) = joined.any { support.normalize(it) == support.normalize(raw) }

        fun forget(raw: String) {
            val channel = support.normalize(raw)
            namesDeltas.remove(channel)
            _modes.update { it - channel }
        }
        when (event) {
            is IrcEvent.Joined -> {
                if (event.isSelf) forget(event.channel)
            }

            is IrcEvent.Parted -> {
                if (event.isSelf) forget(event.channel)
            }

            is IrcEvent.Kicked -> {
                if (event.isSelf) forget(event.channel)
            }

            is IrcEvent.ChannelRenamed -> {
                if (joinedTarget(event.oldName)) {
                    forget(event.oldName)
                    forget(event.newName)
                }
            }

            is IrcEvent.NamesStarted -> {
                if (joinedTarget(event.channel)) {
                    namesDeltas[support.normalize(event.channel)] = mutableMapOf()
                }
            }

            is IrcEvent.Names -> {
                if (joinedTarget(event.channel)) {
                    val channel = support.normalize(event.channel)
                    val member = event.members.find { isSelf(it.nick) }
                    val modes =
                        support.prefixModes.mapNotNullTo(mutableSetOf()) { (mode, glyph) ->
                            mode.takeIf { glyph in member?.prefixes.orEmpty() }
                        }
                    namesDeltas.remove(channel)?.forEach { (mode, add) -> if (add) modes.add(mode) else modes.remove(mode) }
                    _modes.update { it + (channel to modes) }
                }
            }

            is IrcEvent.ModeChanged -> {
                if (joinedTarget(event.target) && !event.ctx.isHistoryContext) {
                    val prefix = support.prefixModes
                    val groups = (support["CHANMODES"] ?: "beI,k,l,imnst").split(',')
                    var adding = true
                    var argIndex = 0
                    val changes = mutableMapOf<Char, Boolean>()
                    for (mode in event.modes) {
                        when (mode) {
                            '+' -> {
                                adding = true
                            }

                            '-' -> {
                                adding = false
                            }

                            else -> {
                                val isPrefix = prefix.any { it.first == mode }
                                if (isPrefix || mode in groups.getOrElse(0) { "" } || mode in groups.getOrElse(1) { "" } ||
                                    (adding && mode in groups.getOrElse(2) { "" })
                                ) {
                                    val argument = event.args.getOrNull(argIndex++) ?: break
                                    if (isPrefix && isSelf(argument)) changes[mode] = adding
                                }
                            }
                        }
                    }
                    if (changes.isNotEmpty()) {
                        val channel = support.normalize(event.target)
                        namesDeltas[channel]?.putAll(changes)
                        _modes.update { old ->
                            val current = old[channel].orEmpty().toMutableSet()
                            changes.forEach { (mode, add) -> if (add) current.add(mode) else current.remove(mode) }
                            old + (channel to current)
                        }
                    }
                }
            }

            else -> {}
        }
    }
}
