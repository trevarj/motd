package io.github.trevarj.motd

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import io.github.trevarj.motd.ai.AiExecutionCoordinator
import io.github.trevarj.motd.ai.AiLabsRepository
import io.github.trevarj.motd.appearance.LauncherIconController
import io.github.trevarj.motd.audio.AudioCacheStore
import io.github.trevarj.motd.avatar.LocalAvatarStore
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.di.AppVisibilityImpl
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.push.PushInstanceCoordinator
import io.github.trevarj.motd.push.PushLifecycleCoordinator
import io.github.trevarj.motd.service.AutoAwayCoordinator
import io.github.trevarj.motd.ui.ComposeFoundationWorkarounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MotdApplication :
    Application(),
    ImageLoaderFactory {
    // THE UnifiedPush registration trigger: reconciles registered instances against the
    // delivery mode and connectable-network set for the process lifetime.
    @Inject lateinit var pushInstanceCoordinator: PushInstanceCoordinator

    @Inject lateinit var pushLifecycleCoordinator: PushLifecycleCoordinator

    // Backgrounded-for-long-enough → AWAY on every connected network, and back on return.
    @Inject lateinit var autoAwayCoordinator: AutoAwayCoordinator

    @Inject lateinit var diagnosticLogger: DiagnosticLogger

    // Process-wide "is the user looking at us", read by panes that navigation disposes.
    @Inject lateinit var appVisibility: AppVisibilityImpl

    @Inject lateinit var aiExecutionCoordinator: AiExecutionCoordinator

    @Inject lateinit var aiLabsRepository: AiLabsRepository

    @Inject lateinit var appearancePrefs: AppearancePrefs

    @Inject lateinit var launcherIconController: LauncherIconController

    @Inject lateinit var database: MotdDatabase

    @Inject lateinit var localAvatarStore: LocalAvatarStore

    @Inject lateinit var audioCacheStore: AudioCacheStore

    @ApplicationScope
    @Inject
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        ComposeFoundationWorkarounds.apply()
        diagnosticLogger.record("app", "process_started") {
            mapOf("cold_start" to true)
        }
        appVisibility.start()
        aiExecutionCoordinator.start()
        applicationScope.launch(Dispatchers.IO) {
            // Retired semantic-search sidecar; IRC history and imported models are untouched.
            deleteDatabase("semantic-search-cache.db")
            aiLabsRepository.reconcile()
        }
        applicationScope.launch(Dispatchers.IO) {
            audioCacheStore.pruneStaleLeases()
        }
        pushInstanceCoordinator.start()
        pushLifecycleCoordinator.start()
        autoAwayCoordinator.start()
        applicationScope.launch(Dispatchers.IO) {
            localAvatarStore.prune(database.bufferDao().localAvatarModels())
        }
        appearancePrefs.config
            .map { it.launcherIcon }
            .distinctUntilChanged()
            .onEach(launcherIconController::apply)
            .launchIn(applicationScope)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader
            .Builder(this)
            .components {
                // Coil's GIF and video modules provide their decoders but do not register them by
                // themselves. Keep the platform decoder where available for animated formats.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(VideoFrameDecoder.Factory())
            }.build()
}
