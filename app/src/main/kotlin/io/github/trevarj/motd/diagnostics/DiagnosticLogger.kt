package io.github.trevarj.motd.diagnostics

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.BuildConfig
import io.github.trevarj.motd.di.IoDispatcher
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Opt-in, app-owned diagnostic journal.
 *
 * Call sites provide only classification and identity metadata. Message bodies, nicks, hosts,
 * accounts, endpoints, credentials, and other user data must never be passed as fields. Values
 * needed for correlation can be represented with [fingerprint], which produces a short one-way
 * digest. Known sensitive field names are omitted as defense in depth. The journal is bounded to
 * two 512 KiB files and is excluded from Android backup.
 */
interface DiagnosticLogger {
    val enabled: StateFlow<Boolean>

    fun setEnabled(enabled: Boolean)

    fun record(
        component: String,
        event: String,
        fields: () -> Map<String, Any?> = { emptyMap() },
    )

    fun fingerprint(value: String?): String?

    suspend fun exportTo(output: OutputStream)

    object Noop : DiagnosticLogger {
        override val enabled: StateFlow<Boolean> = MutableStateFlow(false)
        override fun setEnabled(enabled: Boolean) = Unit
        override fun record(component: String, event: String, fields: () -> Map<String, Any?>) = Unit
        override fun fingerprint(value: String?): String? = diagnosticFingerprint(value)
        override suspend fun exportTo(output: OutputStream) = Unit
    }
}

@Singleton
class FileDiagnosticLogger @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : DiagnosticLogger {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val directory = File(appContext.filesDir, DIRECTORY)
    private val currentFile = File(directory, CURRENT_FILE)
    private val previousFile = File(directory, PREVIOUS_FILE)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val sequence = AtomicLong(0)
    private val _enabled = MutableStateFlow(preferences.getBoolean(ENABLED, false))
    internal var maxFileBytes: Long = MAX_FILE_BYTES

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is Command.Append -> append(command.line)
                    Command.Clear -> clearFiles()
                    is Command.Export -> runCatching { exportFiles(command.output) }
                        .fold(command.done::complete) { command.done.completeExceptionally(it) }
                }
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        if (_enabled.value == enabled) return
        if (enabled) {
            // A newly enabled trace starts from a clean boundary so an exported report cannot
            // accidentally contain an older diagnostic session.
            commands.trySend(Command.Clear)
            preferences.edit { putBoolean(ENABLED, true) }
            _enabled.value = true
            record("diagnostics", "enabled")
        } else {
            record("diagnostics", "disabled")
            _enabled.value = false
            preferences.edit { putBoolean(ENABLED, false) }
        }
    }

    override fun record(component: String, event: String, fields: () -> Map<String, Any?>) {
        if (!_enabled.value) return
        val line = formatDiagnosticLine(
            timestamp = Instant.now().toString(),
            sequence = sequence.incrementAndGet(),
            component = component,
            event = event,
            fields = fields(),
        )
        commands.trySend(Command.Append(line))
    }

    override fun fingerprint(value: String?): String? = diagnosticFingerprint(value)

    override suspend fun exportTo(output: OutputStream) {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Export(output, done))
        done.await()
    }

    private fun append(line: String) {
        directory.mkdirs()
        val bytes = (line + '\n').toByteArray(Charsets.UTF_8)
        if (currentFile.length() + bytes.size > maxFileBytes) {
            previousFile.delete()
            if (!currentFile.renameTo(previousFile)) currentFile.delete()
        }
        currentFile.appendBytes(bytes)
    }

    private fun clearFiles() {
        currentFile.delete()
        previousFile.delete()
    }

    private fun exportFiles(output: OutputStream) {
        val writer = output.bufferedWriter(Charsets.UTF_8)
        writer.appendLine("# MOTD diagnostic log")
        writer.appendLine("# app=${BuildConfig.VERSION_NAME} build=${BuildConfig.BUILD_TYPE} sdk=${Build.VERSION.SDK_INT}")
        writer.appendLine("# Generated ${Instant.now()}; message bodies and user identifiers are not recorded.")
        if (!previousFile.exists() && !currentFile.exists()) writer.appendLine("# No diagnostic events captured.")
        listOf(previousFile, currentFile).filter(File::exists).forEach { file ->
            file.forEachLine(Charsets.UTF_8, writer::appendLine)
        }
        writer.flush()
    }

    private sealed interface Command {
        data class Append(val line: String) : Command
        data object Clear : Command
        data class Export(val output: OutputStream, val done: CompletableDeferred<Unit>) : Command
    }

    private companion object {
        const val PREFERENCES = "diagnostic_logging"
        const val ENABLED = "enabled"
        const val DIRECTORY = "diagnostics"
        const val CURRENT_FILE = "motd-diagnostics.log"
        const val PREVIOUS_FILE = "motd-diagnostics.log.1"
        const val MAX_FILE_BYTES = 512L * 1024L
    }
}

internal fun diagnosticFingerprint(value: String?): String? {
    if (value == null) return null
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return bytes.take(8).joinToString("") { "%02x".format(it) }
}

internal fun formatDiagnosticLine(
    timestamp: String,
    sequence: Long,
    component: String,
    event: String,
    fields: Map<String, Any?>,
): String = buildString {
    append(timestamp)
    append(" seq=").append(sequence)
    append(" component=").append(sanitizeDiagnosticValue(component))
    append(" event=").append(sanitizeDiagnosticValue(event))
    fields.toSortedMap().forEach { (key, value) ->
        if (value != null) {
            append(' ')
            append(sanitizeDiagnosticValue(key))
            append('=')
            append(
                if (key.lowercase() in SENSITIVE_FIELD_NAMES) "[omitted]"
                else sanitizeDiagnosticValue(value.toString()),
            )
        }
    }
}

private fun sanitizeDiagnosticValue(value: String): String = value
    .replace(Regex("[\\r\\n\\t ]+"), "_")
    .take(256)

private val SENSITIVE_FIELD_NAMES = setOf(
    "account",
    "body",
    "credential",
    "endpoint",
    "host",
    "message",
    "nick",
    "password",
    "reason",
    "sender",
    "text",
    "token",
)
