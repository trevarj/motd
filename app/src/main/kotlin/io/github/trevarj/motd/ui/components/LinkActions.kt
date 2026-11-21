package io.github.trevarj.motd.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.chat.ChatLinkKind
import io.github.trevarj.motd.ui.chat.IrcChannelLink
import io.github.trevarj.motd.ui.chat.chatLinkKind
import io.github.trevarj.motd.ui.chat.parseIrcChannelLink
import io.github.trevarj.motd.ui.theme.SheetSystemBars

internal val LocalChatLinksEnabled = staticCompositionLocalOf { true }

internal val LocalOpenIrcChannelLink = staticCompositionLocalOf<((IrcChannelLink) -> Unit)?> { null }
internal const val NON_WEB_LINK_RANGE = "non_web_link"

internal fun Context.openChatNonWebLink(
    url: String,
    openIrcLink: ((IrcChannelLink) -> Unit)?,
) {
    when (chatLinkKind(url)) {
        ChatLinkKind.IRC -> if (openIrcLink != null) parseIrcChannelLink(url)?.let(openIrcLink)
        ChatLinkKind.EXTERNAL -> launchLink(externalLinkIntent(url), R.string.chat_link_no_app)
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LinkActionsSheet(
    urls: List<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val openIrcLink = LocalOpenIrcChannelLink.current
    var selected by remember(urls) { mutableStateOf(urls.singleOrNull()) }
    EffectModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("link_action_sheet"),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetSystemBars()
        val url = selected
        if (url == null) {
            Text(stringResource(R.string.chat_link_actions), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.heightIn(max = 320.dp).testTag("link_action_choices")) {
                items(urls) { choice ->
                    ListItem(
                        headlineContent = { Text(choice) },
                        modifier = Modifier.clickable { selected = choice },
                    )
                }
            }
            return@EffectModalBottomSheet
        }
        val kind = remember(url) { chatLinkKind(url) }
        Text(
            text = url,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        when (kind) {
            ChatLinkKind.WEB -> {
                val link = Uri.parse(url)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.chat_link_open_default)) },
                    modifier =
                        Modifier.testTag("link_action_open_default").clickable {
                            onDismiss()
                            context.launchLink(Intent(Intent.ACTION_VIEW, link), R.string.chat_link_no_app)
                        },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.chat_link_open_browser)) },
                    modifier =
                        Modifier.testTag("link_action_open_browser").clickable {
                            onDismiss()
                            context.launchLink(browserLinkIntent(context.packageManager, link), R.string.chat_link_no_browser)
                        },
                )
            }

            ChatLinkKind.EXTERNAL -> {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.chat_link_open_with)) },
                    modifier =
                        Modifier.testTag("link_action_open_with").clickable {
                            onDismiss()
                            context.launchLink(Intent.createChooser(externalLinkIntent(url), null), R.string.chat_link_no_app)
                        },
                )
            }

            ChatLinkKind.IRC -> {
                if (openIrcLink != null) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.new_sheet_join_channel)) },
                        modifier =
                            Modifier.testTag("link_action_join_channel").clickable {
                                onDismiss()
                                parseIrcChannelLink(url)?.let(openIrcLink)
                            },
                    )
                }
            }

            null -> {
                Unit
            }
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.upload_copy_link)) },
            modifier =
                Modifier.testTag("link_action_copy").clickable {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.upload_copy_link), url))
                    onDismiss()
                },
        )
    }
}

private fun browsableIntent(link: Uri): Intent = Intent(Intent.ACTION_VIEW, link).addCategory(Intent.CATEGORY_BROWSABLE)

private fun externalLinkIntent(url: String): Intent {
    val scheme = url.substringBefore(':')
    val normalized = scheme.lowercase()
    return browsableIntent(Uri.parse(if (scheme == normalized) url else url.replaceRange(0, scheme.length, normalized)))
}

private fun browserLinkIntent(
    packageManager: PackageManager,
    link: Uri,
): Intent? {
    val probe = browsableIntent(Uri.parse("${link.scheme?.lowercase()}://browser.invalid/"))
    val candidates =
        packageManager
            .queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .distinct()
    if (candidates.isEmpty()) return null
    val preferred = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let { ComponentName(it.packageName, it.name) }
    if (preferred in candidates) return browsableIntent(link).setComponent(preferred)
    if (candidates.size == 1) return browsableIntent(link).setComponent(candidates.single())
    return Intent.createChooser(browsableIntent(link).setComponent(candidates.first()), null).apply {
        putExtra(Intent.EXTRA_ALTERNATE_INTENTS, candidates.drop(1).map { browsableIntent(link).setComponent(it) }.toTypedArray())
    }
}

private fun Context.launchLink(
    intent: Intent?,
    failureMessage: Int,
) {
    if (intent != null) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            Unit
        } catch (_: SecurityException) {
            Unit
        }
    }
    Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show()
}
