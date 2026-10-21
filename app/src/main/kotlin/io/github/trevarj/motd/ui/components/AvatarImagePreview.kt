package io.github.trevarj.motd.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.trevarj.motd.R
import io.github.trevarj.motd.attachment.isLocalMediaUri
import io.github.trevarj.motd.avatar.expandAvatarUrl
import io.github.trevarj.motd.avatar.validateAvatarUrl
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.ui.theme.LocalAvatarStyle
import io.github.trevarj.motd.ui.theme.MotdShapes

private data class AvatarPreviewSnapshot(
    val identity: String,
    val networkId: Long,
    val url: String,
    val retry: Int,
)

@Composable
internal fun CurrentAvatarInspection(
    name: String,
    isChannel: Boolean,
    currentModel: String?,
    modifier: Modifier = Modifier,
) {
    val empty = remember { RemoteAvatarState() }
    val hidden = LocalAvatarStyle.current == AvatarStyle.NONE
    val remote = currentModel != null && !currentModel.startsWith("file://")
    CompositionLocalProvider(LocalRemoteAvatars provides empty, LocalAvatarStyle provides if (LocalAvatarStyle.current == AvatarStyle.NONE) AvatarStyle.MONOGRAM else LocalAvatarStyle.current) {
        Column(modifier) {
            Text(stringResource(if (remote) R.string.settings_avatar_current_remote else R.string.settings_avatar_current))
            if (hidden) Text(stringResource(R.string.settings_avatar_inspection_hidden), style = MaterialTheme.typography.bodySmall)
            Avatar(name, isChannel = isChannel, size = 88.dp, conversationModel = currentModel?.takeIf { it.startsWith("file://") })
        }
    }
}

@Composable
internal fun LocalAvatarProposal(
    uri: Uri,
    isChannel: Boolean,
    modifier: Modifier = Modifier,
) {
    val local = uri.isLocalMediaUri()
    var failed by remember(uri) { mutableStateOf(false) }
    Column(modifier) {
        Text(stringResource(R.string.settings_avatar_proposed))
        if (local && !failed) {
            AsyncImage(
                model = uri,
                contentDescription = stringResource(R.string.settings_avatar_proposed),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                onSuccess = { failed = false },
                modifier = Modifier.size(88.dp).clip(if (isChannel) MotdShapes.channelAvatar else CircleShape).testTag("avatar_local_proposal"),
            )
        }
        if (!local || failed) {
            Text(stringResource(if (local) R.string.settings_avatar_local_preview_failed else R.string.settings_local_media_required), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("avatar_local_proposal_error"))
        }
    }
}

/** Consent is bound to this raw editor value AND saved owner/identity; any change disposes the request. */
@Composable
internal fun ExplicitAvatarUrlPreview(
    url: String,
    networkId: Long?,
    identity: String,
    isChannel: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val http = LocalNetworkMediaHttp.current
    val validated = remember(url) { validateAvatarUrl(url) }
    var proposal by remember(url, networkId, identity, isChannel, http) { mutableStateOf<AvatarPreviewSnapshot?>(null) }
    val owner = networkId?.takeIf { it > 0 }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = {
                val captured = validated?.let { expandAvatarUrl(it, 96) }
                if (captured != null && owner != null && http != null) proposal = AvatarPreviewSnapshot(identity, owner, captured, (proposal?.retry ?: 0) + 1)
            },
            enabled = validated != null && owner != null && http != null,
            modifier = Modifier.testTag("avatar_url_preview"),
        ) { Text(stringResource(if (proposal == null) R.string.settings_preview else R.string.settings_avatar_preview_again)) }
        Text(stringResource(R.string.settings_avatar_preview_consent), style = MaterialTheme.typography.bodySmall)
        if (owner == null || http == null) Text(stringResource(R.string.settings_avatar_preview_owner), style = MaterialTheme.typography.bodySmall)
        proposal?.let { captured ->
            key(captured) {
                var loading by remember { mutableStateOf(true) }
                var failed by remember { mutableStateOf(false) }
                val request =
                    remember(context, captured) {
                        ImageRequest.Builder(context).routedRemoteMediaData(captured.url, captured.networkId, networkAllowed = true, retry = captured.retry).build()
                    }
                Text(stringResource(R.string.settings_avatar_proposed))
                AsyncImage(
                    model = request,
                    contentDescription = stringResource(R.string.settings_avatar_proposed),
                    contentScale = ContentScale.Crop,
                    onSuccess = {
                        loading = false
                        failed = false
                    },
                    onError = {
                        loading = false
                        failed = true
                    },
                    modifier = Modifier.size(88.dp).clip(if (isChannel) MotdShapes.channelAvatar else CircleShape).testTag("avatar_url_proposal"),
                )
                if (loading) Text(stringResource(R.string.settings_avatar_preview_loading))
                if (failed) Text(stringResource(R.string.settings_avatar_preview_failed), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
