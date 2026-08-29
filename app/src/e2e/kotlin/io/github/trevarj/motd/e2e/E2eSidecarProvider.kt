package io.github.trevarj.motd.e2e

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.trevarj.motd.sidecar.IMotdSidecarProvider
import io.github.trevarj.motd.sidecar.SidecarAccount
import io.github.trevarj.motd.sidecar.SidecarContract
import io.github.trevarj.motd.sidecar.SidecarJson
import io.github.trevarj.motd.sidecar.SidecarProviderFeature
import io.github.trevarj.motd.sidecar.SidecarProviderInfo
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.UUID
import kotlin.concurrent.thread

class E2eSidecarPairActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_CALLER)?.let { caller ->
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(caller, true).commit()
        }
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_CALLER = "caller"
        const val PREFS = "e2e_sidecar_paired"
    }
}

class E2eSidecarProviderService : Service() {
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == SidecarContract.PROVIDER_ACTION) provider else null

    private val provider =
        object : IMotdSidecarProvider.Stub() {
            override fun getApiVersion() = SidecarContract.API_VERSION

            override fun getProviderInfoJson() =
                SidecarJson.encodeProvider(
                    SidecarProviderInfo(
                        providerId = "e2e-fixture",
                        features = setOf(SidecarProviderFeature.WAKE),
                    ),
                )

            override fun getAccountsJson(): MutableList<String> {
                requirePaired()
                return mutableListOf(SidecarJson.encodeAccount(SidecarAccount(accountId = ACCOUNT, displayName = "Fixture")))
            }

            override fun createUiIntent(
                action: String,
                accountId: String,
                requestJson: String,
            ): Intent {
                val caller = callerPackage()
                if (action != SidecarContract.ACTION_PAIR) requirePaired()
                return Intent(this@E2eSidecarProviderService, E2eSidecarPairActivity::class.java)
                    .putExtra(E2eSidecarPairActivity.EXTRA_CALLER, caller)
            }

            override fun openSession(
                accountId: String,
                optionsJson: String,
            ): ParcelFileDescriptor {
                requirePaired()
                require(accountId == ACCOUNT)
                SidecarJson.decodeSessionOptions(optionsJson)
                val pair = ParcelFileDescriptor.createReliableSocketPair()
                thread(name = "e2e-sidecar-irc") { runSession(pair[0]) }
                return pair[1]
            }

            override fun setWakeIntent(
                accountId: String,
                wakeIntent: PendingIntent,
            ) = requirePaired()

            override fun clearWakeIntent(accountId: String) = requirePaired()
        }

    private fun requirePaired() {
        check(getSharedPreferences(E2eSidecarPairActivity.PREFS, MODE_PRIVATE).getBoolean(callerPackage(), false))
    }

    private fun callerPackage(): String = packageManager.getPackagesForUid(Binder.getCallingUid())?.firstOrNull() ?: error("unknown caller")

    private fun runSession(descriptor: ParcelFileDescriptor) {
        val output = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        val reader = BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(descriptor)))
        val writer = BufferedWriter(OutputStreamWriter(ParcelFileDescriptor.AutoCloseOutputStream(output)))
        var nick = "motd"
        var user = false
        var capEnded = false
        var welcomed = false

        fun send(line: String) {
            writer.write(line)
            writer.write("\r\n")
            writer.flush()
        }

        fun welcome() {
            if (welcomed || !user || !capEnded) return
            welcomed = true
            send(":fixture 001 $nick :Welcome")
            send(":fixture 005 $nick CHANTYPES=# CASEMAPPING=ascii CHATHISTORY=100 CLIENTTAGDENY= :supported")
            send(":fixture 376 $nick :End")
        }
        reader.forEachLine { raw ->
            val tags =
                raw
                    .takeIf { it.startsWith('@') }
                    ?.substringAfter('@')
                    ?.substringBefore(' ')
                    .orEmpty()
            val body = if (raw.startsWith('@')) raw.substringAfter(' ') else raw
            val command = body.substringBefore(' ').uppercase()
            val params = body.substringAfter(' ', "")
            when (command) {
                "CAP" -> {
                    when (params.substringBefore(' ').uppercase()) {
                        "LS" -> {
                            send(
                                ":fixture CAP * LS :${SidecarContract.IRC_CAPABILITY}=1 message-tags server-time batch labeled-response echo-message standard-replies draft/chathistory draft/event-playback draft/read-marker",
                            )
                        }

                        "REQ" -> {
                            send(":fixture CAP * ACK :${params.substringAfter(':')}")
                        }

                        "END" -> {
                            capEnded = true
                            welcome()
                        }
                    }
                }

                "NICK" -> {
                    nick = params.substringBefore(' ')
                    welcome()
                }

                "USER" -> {
                    user = true
                    welcome()
                }

                "PING" -> {
                    send("PONG :${params.substringAfter(':')}")
                }

                "PRIVMSG" -> {
                    val target = params.substringBefore(' ')
                    val text = params.substringAfter(" :", "")
                    val label = tags.split(';').firstOrNull { it.startsWith("label=") }?.substringAfter('=')
                    val labelTag = label?.let { "label=$it;" }.orEmpty()
                    send(
                        "@${labelTag}time=${Instant.now()};msgid=${UUID.randomUUID()};${SidecarContract.SECURITY_MESSAGE_TAG}=plaintext :$nick!fixture@sidecar PRIVMSG $target :$text",
                    )
                }

                "QUIT" -> {
                    return@forEachLine
                }
            }
        }
    }

    private companion object {
        const val ACCOUNT = "fixture"
    }
}
