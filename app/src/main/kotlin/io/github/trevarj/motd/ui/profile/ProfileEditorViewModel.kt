package io.github.trevarj.motd.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.profile.IrcProfileRepository
import io.github.trevarj.motd.profile.ProfileAccess
import io.github.trevarj.motd.profile.ProfileEditorState
import io.github.trevarj.motd.profile.ProfileKey
import io.github.trevarj.motd.profile.ProfileTarget
import io.github.trevarj.motd.profile.ProfileWriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class ProfileWriteState(
    val pending: Boolean = false,
    val result: ProfileWriteResult? = null,
    val binding: ProfileEditorBinding? = null,
)

internal data class ProfileEditorBinding(
    val target: ProfileTarget,
    val networkId: Long,
    val normalizedIdentity: String,
)

internal fun ProfileEditorState.binding(target: ProfileTarget): ProfileEditorBinding? = networkId?.let { ProfileEditorBinding(target, it, normalizedIdentity) }

// AttachmentViewModel delivers to the callback it captured at upload start, including after rotation.
internal class ProfileEditorAvatarUpload(
    private val scope: CoroutineScope,
    private val editor: (ProfileTarget) -> Flow<ProfileEditorState>,
    private val canWrite: suspend (ProfileTarget, Long, String) -> Boolean,
) {
    data class Request(
        val id: Long,
        val binding: ProfileEditorBinding,
        val identity: String,
        val url: String? = null,
    )

    private var nextId = 0L
    private var monitor: Job? = null
    private val _request = MutableStateFlow<Request?>(null)
    val request = _request.asStateFlow()

    fun open(
        target: ProfileTarget,
        state: ProfileEditorState,
    ) {
        if (!state.readable || state.access != ProfileAccess.WRITABLE) return
        val binding = state.binding(target) ?: return
        _request.value?.let(::clear)
        val opened = Request(++nextId, binding, state.identity)
        _request.value = opened
        monitor =
            scope.launch {
                editor(target).first { !it.readable || it.access != ProfileAccess.WRITABLE || it.binding(target) != binding }
                clear(opened)
            }
    }

    fun complete(
        opened: Request,
        url: String,
    ) {
        val current = _request.value ?: return
        if (current.id == opened.id && current.url == null) _request.value = current.copy(url = url)
    }

    fun dismiss(opened: Request) {
        // The sheet calls dismiss after invoking completion or selecting a recent upload.
        if (_request.value?.url == null) clear(opened)
    }

    fun clear(opened: Request) {
        if (_request.value?.id != opened.id) return
        _request.value = null
        monitor?.cancel()
        monitor = null
    }

    suspend fun take(completed: Request): String? {
        val url = completed.url ?: return null
        if (_request.value?.id != completed.id) return null
        val (target, networkId) = completed.binding
        val allowed = canWrite(target, networkId, completed.identity)
        if (_request.value?.id != completed.id) return null
        clear(completed)
        return url.takeIf { allowed }
    }
}

@HiltViewModel
internal class ProfileEditorViewModel
    @Inject
    constructor(
        private val profiles: IrcProfileRepository,
    ) : ViewModel() {
        private val _writeState = MutableStateFlow(ProfileWriteState())
        val writeState = _writeState.asStateFlow()
        val avatarUpload = ProfileEditorAvatarUpload(viewModelScope, profiles::editor, profiles::canWrite)

        fun editor(target: ProfileTarget) = profiles.editor(target)

        suspend fun refresh(target: ProfileTarget) = profiles.refresh(target)

        fun clearResult() {
            if (_writeState.value.result != null) _writeState.value = ProfileWriteState()
        }

        fun write(
            target: ProfileTarget,
            state: ProfileEditorState,
            field: ProfileKey,
            value: String?,
        ) {
            if (_writeState.value.pending || !state.readable || state.access != ProfileAccess.WRITABLE) return
            val binding = state.binding(target) ?: return
            _writeState.value = ProfileWriteState(pending = true, binding = binding)
            viewModelScope.launch {
                val result = profiles.write(target, binding.networkId, state.identity, field, value)
                _writeState.value = ProfileWriteState(result = result, binding = binding)
            }
        }
    }
