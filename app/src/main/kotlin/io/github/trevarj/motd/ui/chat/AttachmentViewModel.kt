package io.github.trevarj.motd.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentUploader
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.UploadRecord
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AttachmentViewModel @Inject constructor(
    private val prefs: AttachmentPrefs,
    private val uploader: AttachmentUploader,
) : ViewModel() {
    val config = prefs.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PasteBackendConfig())
    val recent = prefs.recentUploads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _progress = MutableStateFlow<UploadProgress?>(null)
    val progress: StateFlow<UploadProgress?> = _progress.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private var job: Job? = null

    fun upload(source: AttachmentSource, override: PasteBackendConfig = config.value, onComplete: (UploadRecord) -> Unit) {
        job?.cancel()
        _error.value = null
        job = viewModelScope.launch {
            try {
                uploader.upload(source, override).collect { update ->
                    _progress.value = update
                    if (update is UploadProgress.Complete) {
                        prefs.addUpload(update.record)
                        onComplete(update.record)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _error.value = failure.message ?: "Upload failed"
            } finally {
                _progress.value = null
            }
        }
    }

    fun cancel() { job?.cancel(); _progress.value = null }
    fun clearError() { _error.value = null }
    fun delete(record: UploadRecord) = viewModelScope.launch {
        runCatching { uploader.delete(record); prefs.removeUpload(record.url) }
            .onFailure { _error.value = it.message ?: "Delete failed" }
    }
}
