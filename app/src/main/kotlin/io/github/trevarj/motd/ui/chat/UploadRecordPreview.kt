package io.github.trevarj.motd.ui.chat

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.attachment.MAX_UPLOAD_PREVIEW_BASE64_LENGTH
import io.github.trevarj.motd.attachment.UPLOAD_PREVIEW_THUMBNAIL_EDGE
import io.github.trevarj.motd.attachment.UploadRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

@Composable
internal fun UploadRecordPreview(
    record: UploadRecord,
    modifier: Modifier = Modifier,
) {
    val encoded = record.preview?.thumbnailBase64
    val thumbnail =
        if (encoded == null) {
            null
        } else {
            key(encoded) {
                produceState<ImageBitmap?>(null) {
                    value = withContext(Dispatchers.Default) { decodeThumbnail(encoded) }
                }.value
            }
        }
    Box(
        modifier.defaultMinSize(minWidth = 24.dp, minHeight = 24.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumbnail
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize().testTag("attachment_record_thumbnail"),
            )
        } else {
            val icon =
                when (record.mimeType?.substringBefore('/')) {
                    "image" -> Icons.Outlined.Image
                    "video" -> Icons.Filled.PlayCircle
                    "audio" -> Icons.Outlined.RecordVoiceOver
                    else -> Icons.Outlined.Description
                }
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("attachment_record_fallback"))
        }
    }
}

private fun decodeThumbnail(encoded: String): ImageBitmap? {
    if (encoded.length > MAX_UPLOAD_PREVIEW_BASE64_LENGTH) return null
    val bytes =
        try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null
        }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..UPLOAD_PREVIEW_THUMBNAIL_EDGE || bounds.outHeight !in 1..UPLOAD_PREVIEW_THUMBNAIL_EDGE) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
