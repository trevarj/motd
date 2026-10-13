package io.github.trevarj.motd.ui.chat

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.BuildConfig
import io.github.trevarj.motd.R
import io.github.trevarj.motd.audio.isCanonicalVoiceFallback
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.irc.format.containsIrcFormatting
import io.github.trevarj.motd.irc.format.plainIrcText
import io.github.trevarj.motd.ui.components.MESSAGE_TEXT_CONTRAST
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.ensureContrast
import io.github.trevarj.motd.ui.theme.readingInk
import kotlinx.coroutines.launch

internal data class ExternalTranslationTarget(
    val packageName: String,
    val label: String,
    val action: String,
)

data class InlineTranslation(
    val roomId: Long,
    val eventId: Long,
    val originalText: String,
    val text: String,
)

data class ExternalTranslationRequest(
    val requestId: Long,
    val sessionId: Long,
    val roomId: Long,
    val eventId: Long,
    val originalText: String,
    val plainText: String,
)

private val translationPackages =
    listOf(
        "com.google.android.apps.translate",
        "com.deepl.mobiletranslator",
        "com.microsoft.translator",
        "com.bnyro.translate",
        "ru.yandex.translate",
    )

internal fun externalTranslationText(message: MessageEntity): String? {
    if (message.kind == MessageKind.REDACTED || isCanonicalVoiceFallback(message.text)) return null
    val text = if (containsIrcFormatting(message.text)) plainIrcText(message.text) else message.text
    return text.takeUnless(String::isBlank)
}

internal fun externalTranslationTargets(packageManager: PackageManager): List<ExternalTranslationTarget> =
    translationPackages.mapNotNull { packageName ->
        val processActivity = packageManager.translationActivity(packageName, Intent.ACTION_PROCESS_TEXT)
        val activity = processActivity ?: packageManager.translationActivity(packageName, Intent.ACTION_SEND)
        activity?.let {
            ExternalTranslationTarget(
                packageName = packageName,
                label = it.applicationInfo.loadLabel(packageManager).toString(),
                action = if (processActivity != null) Intent.ACTION_PROCESS_TEXT else Intent.ACTION_SEND,
            )
        }
    }

internal fun externalTranslationAvailable(
    packageManager: PackageManager,
    target: ExternalTranslationTarget?,
): Boolean = packageManager.translationActivity(target?.packageName, target?.action ?: Intent.ACTION_PROCESS_TEXT) != null

private fun PackageManager.translationActivity(
    packageName: String?,
    action: String,
) = queryIntentActivities(
    Intent(action).setType("text/plain").setPackage(packageName),
    PackageManager.MATCH_DEFAULT_ONLY,
).firstOrNull { resolved ->
    val activity = resolved.activityInfo ?: return@firstOrNull false
    val application = activity.applicationInfo ?: return@firstOrNull false
    (packageName == null || activity.packageName == packageName) &&
        activity.exported && activity.enabled && application.enabled &&
        (
            activity.permission.isNullOrEmpty() ||
                checkPermission(activity.permission, BuildConfig.APPLICATION_ID) == PackageManager.PERMISSION_GRANTED
        )
}?.activityInfo

internal fun externalTranslationIntent(
    text: String,
    target: ExternalTranslationTarget?,
    chooserTitle: String,
): Intent {
    val intent =
        if (target?.action == Intent.ACTION_SEND) {
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
        } else {
            Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        }
    return if (target == null) Intent.createChooser(intent, chooserTitle) else intent.setPackage(target.packageName)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageTranslationPicker(
    targets: List<ExternalTranslationTarget>,
    localAvailable: Boolean,
    otherAvailable: Boolean,
    onLocal: () -> Unit,
    onTarget: (ExternalTranslationTarget) -> Unit,
    onOther: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun hideThen(action: () -> Unit) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("message_translation_picker"),
    ) {
        SheetSystemBars()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.chat_translation_picker_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.chat_translation_disclosure),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            if (localAvailable) {
                TranslationPickerRow(
                    label = stringResource(R.string.chat_translation_local),
                    tag = "message_translation_local",
                    onClick = { hideThen(onLocal) },
                )
            }
            if (targets.isEmpty()) {
                Text(
                    stringResource(R.string.chat_translation_no_supported_apps),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            targets.forEach { target ->
                TranslationPickerRow(
                    label = target.label,
                    subtitle = if (target.action == Intent.ACTION_SEND) stringResource(R.string.chat_translation_external_only) else null,
                    tag = "message_translation_app_${target.packageName}",
                    onClick = { hideThen { onTarget(target) } },
                )
            }
            TranslationPickerRow(
                label = stringResource(R.string.chat_translation_other),
                subtitle = if (otherAvailable) null else stringResource(R.string.chat_translation_no_processors),
                tag = "message_translation_other",
                enabled = otherAvailable,
                onClick = { hideThen(onOther) },
            )
            TextButton(
                onClick = { hideThen {} },
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("message_translation_cancel"),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun TranslationPickerRow(
    label: String,
    tag: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(Icons.Outlined.Translate, contentDescription = null) },
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .alpha(if (enabled) 1f else 0.38f)
                .testTag(tag)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

internal fun inlineTranslationFor(
    message: MessageEntity,
    translations: Map<Long, InlineTranslation>,
): InlineTranslation? =
    translations[message.id]?.takeIf {
        it.eventId == message.id && it.roomId == message.bufferId &&
            it.originalText == message.text && message.kind != MessageKind.REDACTED
    }

@Composable
internal fun InlineMessageTranslation(
    translation: InlineTranslation,
    parentColor: Color = MaterialTheme.colorScheme.background,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fill = scheme.surfaceContainerHighest.copy(alpha = 0.6f).compositeOver(parentColor)
    val ink = readingInk(scheme)
    val bodyColor = remember(ink, fill) { ensureContrast(ink, listOf(fill), MESSAGE_TEXT_CONTRAST) }
    val accentInk = scheme.primary
    val accent = remember(accentInk, fill) { ensureContrast(accentInk, listOf(fill), MESSAGE_TEXT_CONTRAST) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
            .testTag("message_translation_${translation.eventId}")
            .padding(start = 9.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.chat_translation_label),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("message_translation_dismiss_${translation.eventId}"),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_translation_dismiss), tint = accent)
            }
        }
        SelectionContainer {
            Text(
                translation.text,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = bodyColor,
            )
        }
    }
}
