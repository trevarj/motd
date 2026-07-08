package io.github.trevarj.motd.di

import io.github.trevarj.motd.service.ForegroundBufferTracker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Trivial [ForegroundBufferTracker]: a single [MutableStateFlow] holding the bufferId currently
 * visible in the UI. Set by [io.github.trevarj.motd.ui.chat.ChatViewModel], read by the
 * notification-suppression logic (plans/05). Replaces WP1's stub.
 */
@Singleton
class ForegroundBufferTrackerImpl @Inject constructor() : ForegroundBufferTracker {
    private val _foreground = MutableStateFlow<Long?>(null)
    override val foregroundBufferId: StateFlow<Long?> = _foreground.asStateFlow()
    override fun set(bufferId: Long?) { _foreground.value = bufferId }
}
