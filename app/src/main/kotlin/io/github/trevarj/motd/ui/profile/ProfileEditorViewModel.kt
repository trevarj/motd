package io.github.trevarj.motd.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.profile.IrcProfileRepository
import io.github.trevarj.motd.profile.ProfileKey
import io.github.trevarj.motd.profile.ProfileTarget
import io.github.trevarj.motd.profile.ProfileWriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class ProfileWriteState(
    val pending: Boolean = false,
    val result: ProfileWriteResult? = null,
)

@HiltViewModel
internal class ProfileEditorViewModel
    @Inject
    constructor(
        private val profiles: IrcProfileRepository,
    ) : ViewModel() {
        private val _writeState = MutableStateFlow(ProfileWriteState())
        val writeState = _writeState.asStateFlow()

        fun editor(target: ProfileTarget) = profiles.editor(target)

        suspend fun refresh(target: ProfileTarget) = profiles.refresh(target)

        fun clearResult() {
            if (_writeState.value.result != null) _writeState.value = ProfileWriteState()
        }

        fun write(
            target: ProfileTarget,
            field: ProfileKey,
            value: String?,
        ) {
            if (_writeState.value.pending) return
            _writeState.value = ProfileWriteState(pending = true)
            viewModelScope.launch {
                _writeState.value = ProfileWriteState(result = profiles.write(target, field, value))
            }
        }
    }
