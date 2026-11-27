package io.github.trevarj.motd.ui.share

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import android.util.Base64
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.PaletteException
import io.github.trevarj.motd.data.prefs.decodePaletteTree
import io.github.trevarj.motd.data.prefs.encodePalette
import io.github.trevarj.motd.data.prefs.parsePaletteJson
import io.github.trevarj.motd.data.prefs.readPaletteBytes
import io.github.trevarj.motd.parseJoinInvitePayload
import io.github.trevarj.motd.ui.nav.JoinInviteRoute
import io.github.trevarj.motd.ui.nav.PalettePreviewRoute
import io.github.trevarj.motd.ui.nav.SharePickerRoute
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import io.github.trevarj.motd.ui.components.EmojiText as Text

sealed interface IncomingContent {
    data class Palette(
        val json: String,
    ) : IncomingContent

    data class Share(
        val share: PendingShare,
    ) : IncomingContent

    data class Invite(
        val payload: String,
    ) : IncomingContent

    data class Error(
        val message: String,
        @StringRes val titleRes: Int? = null,
    ) : IncomingContent
}

private const val MAX_SHARED_FILES = 16

private fun Context.readSharedFiles(intent: Intent): IncomingContent {
    fun error(
        @StringRes messageRes: Int,
    ) = IncomingContent.Error(getString(messageRes), R.string.share_batch_error_title)

    // An invalid EXTRA_STREAM is not permission to fall back. IntentCompat uses the typed
    // getter on API 34+ and the legacy getter on earlier releases.
    val streams: List<*>? =
        try {
            if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: return error(R.string.share_batch_error_invalid)
            } else {
                null
            }
        } catch (_: RuntimeException) {
            // Untrusted extras may fail to unparcel before Android can check their type.
            return error(R.string.share_batch_error_invalid)
        }
    val clip = if (streams == null) intent.clipData else null
    val count = streams?.size ?: clip?.itemCount ?: 0
    if (count == 0) return error(R.string.share_batch_error_invalid)
    if (count > MAX_SHARED_FILES) {
        return IncomingContent.Error(getString(R.string.share_batch_error_limit, MAX_SHARED_FILES), R.string.share_batch_error_title)
    }

    val files = ArrayList<PendingShare.File>(count)
    val mimeType = intent.type
    for (index in 0 until count) {
        val uri =
            if (streams != null) {
                streams[index] as? Uri
            } else {
                clip?.getItemAt(index)?.takeIf { it.intent == null }?.uri
            } ?: return error(R.string.share_batch_error_invalid)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.isOpaque || uri.authority.isNullOrEmpty()) {
            return error(R.string.share_batch_error_unsupported)
        }
        files.add(PendingShare.File(uri, mimeType))
    }
    return IncomingContent.Share(PendingShare.Files(files))
}

private fun IncomingContent?.sharedFiles(): PendingShare.Files? = (this as? IncomingContent.Share)?.share as? PendingShare.Files

private fun Context.authorizeSharedFiles(batch: IncomingContent): IncomingContent {
    val files = batch.sharedFiles()?.files ?: return batch
    val pid = Process.myPid()
    val uid = Process.myUid()
    // An exported activity's own provider permissions must never authorize a sender's URI.
    val granted =
        try {
            files.all { checkUriPermission(it.uri, pid, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED }
        } catch (_: SecurityException) {
            false
        }
    return if (granted) {
        batch
    } else {
        IncomingContent.Error(getString(R.string.share_batch_error_permission), R.string.share_batch_error_title)
    }
}

internal suspend fun classifyIncomingContent(
    context: Context,
    intent: Intent,
): IncomingContent? =
    withContext(Dispatchers.IO) {
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) return@withContext context.authorizeSharedFiles(context.readSharedFiles(intent))
        parseJoinInvitePayload(intent)?.let { return@withContext IncomingContent.Invite(it) }
        val share = parseSharedContent(intent)
        if (share is PendingShare.Text) return@withContext IncomingContent.Share(share)
        val view = intent.action == Intent.ACTION_VIEW && intent.data?.scheme in setOf("content", "file")
        if (intent.action != Intent.ACTION_SEND && !view) return@withContext null
        val uri = (share as? PendingShare.File)?.uri ?: if (view) intent.data else null
        if (uri == null) return@withContext null
        val mime = intent.type?.substringBefore(';')?.lowercase()
        val vendor = mime == "application/vnd.motd.palette+json"
        val json = mime == "application/json" || mime == "text/json" || mime?.endsWith("+json") == true
        var hinted = vendor || uri.lastPathSegment.orEmpty().endsWith(".motd-palette.json", ignoreCase = true)
        if (!hinted && uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0) hinted = cursor.getString(column).orEmpty().endsWith(".motd-palette.json", ignoreCase = true)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
        val fallback = share?.let(IncomingContent::Share)
        if (!hinted && !json) return@withContext fallback
        var marked = false
        try {
            val bytes =
                context.contentResolver.openInputStream(uri)?.use { readPaletteBytes(it) }
                    ?: return@withContext if (hinted || view) IncomingContent.Error("Could not read palette") else fallback
            ensureActive()
            val tree = parsePaletteJson(bytes)
            val marker = (tree as? JsonObject)?.get("format") as? JsonPrimitive
            marked = marker?.isString == true && marker.content == "motd-palette"
            if (!marked && !hinted) return@withContext if (view) IncomingContent.Error("Not a MOTD palette") else fallback
            IncomingContent.Palette(encodePalette(decodePaletteTree(tree)).decodeToString())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (hinted || marked || view) {
                IncomingContent.Error(
                    if (error is PaletteException) error.message ?: "Invalid palette" else "Could not read palette",
                )
            } else {
                fallback
            }
        }
    }

class PaletteIntentIntake(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    data class Pending(
        val token: Long,
        val source: Intent,
        val content: IncomingContent,
    )

    init {
        for (key in savedState.keys()) {
            if (key != "deliveryCounter" && key != "consumedSource") savedState.remove<Any?>(key)
        }
        savedState["deliveryCounter"] = (savedState.get<Any?>("deliveryCounter") as? Long)
            ?.takeIf { it >= 0L && it < Long.MAX_VALUE } ?: 0L
        savedState["consumedSource"] =
            (savedState.get<Any?>("consumedSource") as? String)
                ?.takeIf { it.length == 44 }
    }

    private val mutablePending = MutableStateFlow<Pending?>(null)
    val pending = mutablePending.asStateFlow()
    private val mutableActive = MutableStateFlow(false)
    val active = mutableActive.asStateFlow()
    private var generation = savedState.get<Long>("deliveryCounter") ?: 0L
    private val mutableDelivery = MutableStateFlow(generation)
    val delivery = mutableDelivery.asStateFlow()
    private var job: Job? = null
    private var initializedSource: String? = null

    fun receive(
        intent: Intent,
        restoring: Boolean = false,
    ) {
        val batch = if (intent.action == Intent.ACTION_SEND_MULTIPLE) getApplication<Application>().readSharedFiles(intent) else null
        val source = intent.deliveryFingerprint(batch.sharedFiles())
        if (restoring && (initializedSource == source || savedState.get<String>("consumedSource") == source)) return
        initializedSource = source
        savedState["consumedSource"] = null
        val token = ++generation
        savedState["deliveryCounter"] = token
        mutableDelivery.value = token
        mutableActive.value = true
        job?.cancel()
        mutablePending.value = null
        job =
            viewModelScope.launch {
                val context = getApplication<Application>()
                val content =
                    if (batch == null) {
                        classifyIncomingContent(context, intent)
                    } else {
                        withContext(Dispatchers.IO) { context.authorizeSharedFiles(batch) }
                    }
                if (generation == token) {
                    mutablePending.value = content?.let { Pending(token, intent, it) }
                    if (content == null) mutableActive.value = false
                }
            }
    }

    fun isCurrent(token: Long): Boolean = mutablePending.value?.token == token && generation == token

    fun consume(token: Long): Intent? {
        if (!isCurrent(token)) return null
        val source = mutablePending.value!!.source
        mutablePending.value = null
        savedState["consumedSource"] = initializedSource
        mutableActive.value = false
        return source
    }
}

private fun Intent.deliveryFingerprint(orderedFiles: PendingShare.Files?): String {
    val digest = MessageDigest.getInstance("SHA-256")

    fun number(value: Int) {
        for (shift in 24 downTo 0 step 8) digest.update((value ushr shift).toByte())
    }

    fun text(value: CharSequence?) {
        number(value?.length ?: -1)
        if (value != null) {
            for (character in value) {
                digest.update((character.code ushr 8).toByte())
                digest.update(character.code.toByte())
            }
        }
    }
    text(action)
    text(dataString)
    text(type)
    if (action == Intent.ACTION_SEND_MULTIPLE) {
        val files = orderedFiles?.files
        number(files?.size ?: -1)
        files?.forEach { text(it.uri.toString()) }
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }
    text(streamExtra()?.toString())
    text(getStringExtra(Intent.EXTRA_TEXT))
    val clip = clipData
    number(clip?.itemCount ?: -1)
    if (clip != null) {
        number(clip.description.mimeTypeCount)
        for (index in 0 until clip.description.mimeTypeCount) text(clip.description.getMimeType(index))
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            text(item.uri?.toString())
            text(item.text)
            text(item.htmlText)
        }
    }
    return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
}

@Composable
fun IncomingContentNavigation(
    intake: PaletteIntentIntake,
    navController: NavHostController,
    shares: PendingShareStore,
    onConsumed: (Intent) -> Unit,
) {
    val expiredMessage by rememberUpdatedState(stringResource(R.string.share_batch_error_expired))
    val reviewActiveMessage by rememberUpdatedState(stringResource(R.string.share_batch_error_review_active))
    val backStackEntry by navController.currentBackStackEntryAsState()
    val active by intake.active.collectAsStateWithLifecycle()
    val pending by intake.pending.collectAsStateWithLifecycle()
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var errorTitle by rememberSaveable { mutableStateOf<Int?>(null) }
    val delivery by intake.delivery.collectAsStateWithLifecycle()
    var shownDelivery by rememberSaveable { mutableStateOf(delivery) }
    LaunchedEffect(delivery) {
        if (shownDelivery != delivery) {
            error = null
            shownDelivery = delivery
        }
    }
    LaunchedEffect(backStackEntry, active) {
        val entry = backStackEntry ?: return@LaunchedEffect
        if (!active && entry == navController.currentBackStackEntry &&
            entry.destination.hasRoute<SharePickerRoute>() && shares.peek() == null
        ) {
            // Navigation can survive process death, but the claim and its URI grants cannot.
            if (navController.popBackStack() && error == null) {
                errorTitle = R.string.share_batch_error_title
                error = expiredMessage
            }
        }
    }
    LaunchedEffect(pending?.token) {
        val next = pending ?: return@LaunchedEffect
        if (!intake.isCurrent(next.token)) return@LaunchedEffect
        error = null
        when (val content = next.content) {
            is IncomingContent.Palette -> {
                if (navController.currentDestination?.hasRoute<PalettePreviewRoute>() == true) {
                    navController.popBackStack()
                }
                navController.navigate(PalettePreviewRoute(content.json))
            }

            is IncomingContent.Invite -> {
                navController.navigate(JoinInviteRoute(content.payload)) { launchSingleTop = true }
            }

            is IncomingContent.Share -> {
                if (shares.hasActiveBatchReview) {
                    errorTitle = R.string.share_batch_error_title
                    error = reviewActiveMessage
                } else {
                    // A replacement share needs a fresh picker claim, even if the picker is open.
                    if (navController.currentDestination?.hasRoute<SharePickerRoute>() == true) {
                        navController.popBackStack()
                    }
                    shares.set(content.share)
                    navController.navigate(SharePickerRoute) { launchSingleTop = true }
                }
            }

            is IncomingContent.Error -> {
                error = content.message
                errorTitle = content.titleRes
            }
        }
        intake.consume(next.token)?.let(onConsumed)
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text(errorTitle?.let { stringResource(it) } ?: "Palette import") },
            text = { Text(message) },
            confirmButton = {
                TextButton(colors = readableTextButtonColors(), onClick = { error = null }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
}
