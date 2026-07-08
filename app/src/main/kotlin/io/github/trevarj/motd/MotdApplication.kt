package io.github.trevarj.motd

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.trevarj.motd.push.PushInstanceCoordinator
import javax.inject.Inject

@HiltAndroidApp
class MotdApplication : Application() {
    // THE UnifiedPush registration trigger: reconciles registered instances against the
    // delivery mode and connectable-network set for the process lifetime.
    @Inject lateinit var pushInstanceCoordinator: PushInstanceCoordinator

    override fun onCreate() {
        super.onCreate()
        pushInstanceCoordinator.start()
    }
}
