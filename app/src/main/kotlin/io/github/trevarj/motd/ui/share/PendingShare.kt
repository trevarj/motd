package io.github.trevarj.motd.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Build

/** A single inbound ACTION_SEND payload waiting for the user to pick a destination buffer. */
sealed interface PendingShare {
    data class Text(
        val text: String,
    ) : PendingShare

    data class File(
        val uri: Uri,
        val mimeType: String?,
    ) : PendingShare

    /** Sensitive source context: only the Agentwire harness may review or send this payload. */
    data class AgentContext(
        val originBufferId: Long,
        val sourceLabel: String,
        val prompt: String,
        val coverage: String,
    ) : PendingShare
}

/**
 * Extract the payload from a single-item share intent, or null when the intent isn't one we
 * handle (SEND_MULTIPLE, a launcher intent, an empty share). A stream wins over text unless the
 * sender declared text/plain and supplied real text — several apps attach both, with the stream as
 * a redundant copy of the same text.
 */
fun parseSharedContent(intent: Intent?): PendingShare? {
    if (intent?.action != Intent.ACTION_SEND) return null
    val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
    val stream = intent.streamExtra()
    if (stream != null && (intent.type != "text/plain" || text == null)) {
        return PendingShare.File(stream, intent.type)
    }
    return text?.let(PendingShare::Text)
}

private fun Intent.streamExtra(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_STREAM)
    }
