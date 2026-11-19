package io.github.trevarj.motd.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.backup.BackupDocumentTooLargeException
import io.github.trevarj.motd.data.backup.BackupExportMode
import io.github.trevarj.motd.data.backup.BackupFormatException
import io.github.trevarj.motd.data.backup.BackupImportMode
import io.github.trevarj.motd.data.backup.BackupLocalWallpaperLibraryException
import io.github.trevarj.motd.data.backup.BackupWallpaperMergeException
import io.github.trevarj.motd.data.backup.ConfigurationBackupRepository
import io.github.trevarj.motd.data.backup.ConfigurationImportPreview
import io.github.trevarj.motd.data.backup.ConfigurationImportResult
import io.github.trevarj.motd.data.backup.MAX_BACKUP_DOCUMENT_BYTES
import io.github.trevarj.motd.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject

enum class BackupExportPhase { IDLE, EXPORTING }

enum class BackupImportPhase { EMPTY, READING, PASSWORD_REQUIRED, PREVIEW, APPLYING, COMPLETE }

enum class BackupFailure {
    WRONG_PASSWORD_OR_CORRUPT,
    UNSUPPORTED_OR_INVALID,
    OVERSIZED,
    LOCAL_WALLPAPER_LIBRARY,
    READ,
    WRITE,
    EXPORT,
    IMPORT,
}

sealed interface BackupExportOutcome {
    data object Success : BackupExportOutcome

    data class Failure(
        val category: BackupFailure,
    ) : BackupExportOutcome
}

sealed interface BackupImportOutcome {
    data class Success(
        val result: ConfigurationImportResult,
        val sourceFilename: String,
        val encrypted: Boolean,
        val mode: BackupImportMode,
    ) : BackupImportOutcome

    data class Failure(
        val category: BackupFailure,
    ) : BackupImportOutcome
}

data class BackupRestoreUiState(
    val exportPhase: BackupExportPhase = BackupExportPhase.IDLE,
    val exportOutcome: BackupExportOutcome? = null,
    val importPhase: BackupImportPhase = BackupImportPhase.EMPTY,
    val importOutcome: BackupImportOutcome? = null,
    val importMode: BackupImportMode = BackupImportMode.MERGE,
    val selectedFilename: String? = null,
    val importEncrypted: Boolean = false,
    val preview: ConfigurationImportPreview? = null,
    val confirmReplace: Boolean = false,
) {
    val busy: Boolean get() = exportPhase == BackupExportPhase.EXPORTING || importPhase in setOf(BackupImportPhase.READING, BackupImportPhase.APPLYING)
}

@HiltViewModel
class BackupRestoreViewModel
    @Inject
    constructor(
        private val repository: ConfigurationBackupRepository,
        @ApplicationContext private val context: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _state = MutableStateFlow(BackupRestoreUiState())
        val state: StateFlow<BackupRestoreUiState> = _state.asStateFlow()
        private var importedDocument: String? = null
        private var acceptedPassword: String? = null

        internal var readDocument: suspend (Uri) -> Pair<String, String> = { uri ->
            withContext(ioDispatcher) { displayName(uri) to readText(uri) }
        }
        internal var writeDocument: suspend (Uri, String) -> Unit = { uri, text ->
            withContext(ioDispatcher) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) }
                    ?: throw IOException("destination unavailable")
            }
        }

        fun export(
            uri: Uri,
            mode: BackupExportMode,
            password: String,
        ) {
            if (_state.value.busy) return
            viewModelScope.launch {
                _state.update { it.copy(exportPhase = BackupExportPhase.EXPORTING, exportOutcome = null) }
                val document =
                    try {
                        withContext(ioDispatcher) { repository.exportToString(mode, password.takeIf(String::isNotBlank)) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val category =
                            when (failure) {
                                is BackupDocumentTooLargeException -> BackupFailure.OVERSIZED
                                is BackupLocalWallpaperLibraryException -> BackupFailure.LOCAL_WALLPAPER_LIBRARY
                                else -> BackupFailure.EXPORT
                            }
                        _state.update {
                            it.copy(exportPhase = BackupExportPhase.IDLE, exportOutcome = BackupExportOutcome.Failure(category))
                        }
                        return@launch
                    }
                try {
                    writeDocument(uri, document)
                    _state.update { it.copy(exportPhase = BackupExportPhase.IDLE, exportOutcome = BackupExportOutcome.Success) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update {
                        it.copy(exportPhase = BackupExportPhase.IDLE, exportOutcome = BackupExportOutcome.Failure(BackupFailure.WRITE))
                    }
                }
            }
        }

        fun dismissExportOutcome() = _state.update { it.copy(exportOutcome = null) }

        fun loadImport(uri: Uri) {
            if (_state.value.busy) return
            viewModelScope.launch {
                _state.update {
                    it.copy(
                        importPhase = BackupImportPhase.READING,
                        importOutcome = null,
                        selectedFilename = null,
                        preview = null,
                        confirmReplace = false,
                    )
                }
                try {
                    val (name, raw) = readDocument(uri)
                    val encrypted = withContext(ioDispatcher) { repository.isEncrypted(raw) }
                    importedDocument = raw
                    acceptedPassword = null
                    _state.update {
                        it.copy(
                            selectedFilename = name,
                            importEncrypted = encrypted,
                            importPhase = if (encrypted) BackupImportPhase.PASSWORD_REQUIRED else BackupImportPhase.READING,
                        )
                    }
                    if (!encrypted) previewLoaded("")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    importedDocument = null
                    acceptedPassword = null
                    _state.update {
                        it.copy(
                            importPhase = BackupImportPhase.EMPTY,
                            importOutcome = BackupImportOutcome.Failure(classifyLoadFailure(failure)),
                        )
                    }
                }
            }
        }

        fun previewImport(password: String) {
            if (importedDocument == null || _state.value.importPhase != BackupImportPhase.PASSWORD_REQUIRED) return
            _state.update { it.copy(importPhase = BackupImportPhase.READING, importOutcome = null, preview = null) }
            viewModelScope.launch { previewLoaded(password) }
        }

        fun setImportMode(mode: BackupImportMode) {
            if (_state.value.importMode == mode || _state.value.importPhase != BackupImportPhase.PREVIEW) return
            val password = if (_state.value.importEncrypted) acceptedPassword else ""
            if (importedDocument == null || (_state.value.importEncrypted && password == null)) return
            _state.update {
                it.copy(importMode = mode, importPhase = BackupImportPhase.READING, importOutcome = null, preview = null, confirmReplace = false)
            }
            viewModelScope.launch { previewLoaded(password.orEmpty()) }
        }

        fun requestApply(password: String) {
            val preview = _state.value.preview ?: return
            if (_state.value.importPhase != BackupImportPhase.PREVIEW) return
            if (_state.value.importMode == BackupImportMode.REPLACE && preview.removedNetworks > 0) {
                _state.update { it.copy(confirmReplace = true) }
            } else {
                applyImport(password)
            }
        }

        fun cancelReplace() = _state.update { it.copy(confirmReplace = false) }

        fun confirmReplace(password: String) {
            if (!_state.value.confirmReplace) return
            _state.update { it.copy(confirmReplace = false) }
            applyImport(password)
        }

        fun clearImportOutcome() = _state.update { it.copy(importOutcome = null) }

        private suspend fun previewLoaded(password: String) {
            val raw = importedDocument ?: return
            try {
                val preview = withContext(ioDispatcher) { repository.preview(raw, password.takeIf(String::isNotBlank), _state.value.importMode) }
                acceptedPassword = password.takeIf(String::isNotBlank)
                _state.update { it.copy(importPhase = BackupImportPhase.PREVIEW, preview = preview, importOutcome = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val canChangeMode = failure is BackupLocalWallpaperLibraryException || failure is BackupWallpaperMergeException
                if (canChangeMode) acceptedPassword = password.takeIf(String::isNotBlank)
                _state.update {
                    it.copy(
                        importPhase =
                            when {
                                canChangeMode -> BackupImportPhase.PREVIEW
                                it.importEncrypted -> BackupImportPhase.PASSWORD_REQUIRED
                                else -> BackupImportPhase.EMPTY
                            },
                        importOutcome = BackupImportOutcome.Failure(classifyPreviewFailure(failure, it.importEncrypted)),
                    )
                }
            }
        }

        private fun applyImport(password: String) {
            val current = _state.value
            if (current.importPhase != BackupImportPhase.PREVIEW) return
            val raw = importedDocument ?: return
            val sourceFilename = current.selectedFilename.orEmpty()
            val encrypted = current.importEncrypted
            val mode = current.importMode
            val effectivePassword = password.takeIf(String::isNotBlank) ?: acceptedPassword
            _state.update { it.copy(importPhase = BackupImportPhase.APPLYING, importOutcome = null) }
            viewModelScope.launch {
                try {
                    val result = withContext(ioDispatcher) { repository.import(raw, effectivePassword, mode) }
                    importedDocument = null
                    acceptedPassword = null
                    _state.update {
                        it.copy(
                            importPhase = BackupImportPhase.COMPLETE,
                            importOutcome = BackupImportOutcome.Success(result, sourceFilename, encrypted, mode),
                            selectedFilename = null,
                            importEncrypted = false,
                            preview = null,
                            confirmReplace = false,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val category =
                        when (failure) {
                            is BackupDocumentTooLargeException -> BackupFailure.OVERSIZED
                            is BackupLocalWallpaperLibraryException -> BackupFailure.LOCAL_WALLPAPER_LIBRARY
                            else -> BackupFailure.IMPORT
                        }
                    _state.update {
                        it.copy(
                            importPhase = BackupImportPhase.PREVIEW,
                            importOutcome = BackupImportOutcome.Failure(category),
                        )
                    }
                }
            }
        }

        private fun displayName(uri: Uri): String {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
            return uri.lastPathSegment.orEmpty().ifBlank { "motd.motdconfig" }
        }

        private fun readText(uri: Uri): String {
            val output = ByteArrayOutputStream()
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BACKUP_DOCUMENT_BYTES) throw BackupDocumentTooLargeException()
                    output.write(buffer, 0, read)
                }
            } ?: throw IOException("source unavailable")
            return output.toString(Charsets.UTF_8.name())
        }

        private fun classifyLoadFailure(failure: Exception): BackupFailure =
            when (failure) {
                is BackupDocumentTooLargeException -> BackupFailure.OVERSIZED
                is IOException -> BackupFailure.READ
                else -> BackupFailure.UNSUPPORTED_OR_INVALID
            }

        private fun classifyPreviewFailure(
            failure: Exception,
            encrypted: Boolean,
        ): BackupFailure =
            when {
                failure is BackupDocumentTooLargeException -> BackupFailure.OVERSIZED
                failure is BackupLocalWallpaperLibraryException -> BackupFailure.LOCAL_WALLPAPER_LIBRARY
                failure is BackupFormatException -> BackupFailure.UNSUPPORTED_OR_INVALID
                failure is IOException -> BackupFailure.READ
                encrypted -> BackupFailure.WRONG_PASSWORD_OR_CORRUPT
                else -> BackupFailure.UNSUPPORTED_OR_INVALID
            }
    }
