package io.github.trevarj.motd.ai

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private val Context.aiLabsDataStore by preferencesDataStore("ai_labs")
private val AI_LABS_STATE = stringPreferencesKey("state_v1")

private const val MODEL_DIRECTORY = "ai-models"
private const val MODEL_SUFFIX = ".model"
private const val IMPORT_PREFIX = ".import-"
private const val DELETE_PREFIX = ".delete-"
private const val TEMP_SUFFIX = ".tmp"
private const val COPY_BUFFER_BYTES = 1024 * 1024
private const val MAX_MODEL_BYTES = 8L * 1024L * 1024L * 1024L
private const val FREE_SPACE_RESERVE_BYTES = 256L * 1024L * 1024L

private val AI_LABS_JSON =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

/** Stable, content-free failure categories safe to show outside the repository. */
enum class AiLabsFailureKind {
    SOURCE_PERMISSION,
    SOURCE_OPEN,
    SOURCE_READ,
    STORAGE_WRITE,
    TOO_LARGE,
    INSUFFICIENT_SPACE,
    TRUNCATED_MODEL,
    INVALID_FORMAT,
    CORRUPT_MODEL,
    UNSUPPORTED_ROLE,
    UNSUPPORTED_ARCHITECTURE,
    NATIVE_OUT_OF_MEMORY,
    RUNTIME_FAILURE,
    ATOMIC_INSTALL,
    DELETION,
    MODEL_NOT_FOUND,
    MODEL_NOT_READY,
    INVALID_SETTINGS,
    PERSISTENCE,
    INTERNAL,
}

class AiLabsException(
    val kind: AiLabsFailureKind,
) : Exception(kind.safeMessage)

private val AiLabsFailureKind.safeMessage: String
    get() =
        when (this) {
            AiLabsFailureKind.SOURCE_PERMISSION -> "Permission to read the selected model was denied"
            AiLabsFailureKind.SOURCE_OPEN -> "The selected model could not be opened"
            AiLabsFailureKind.SOURCE_READ -> "The selected model could not be read"
            AiLabsFailureKind.STORAGE_WRITE -> "The model could not be written to local storage"
            AiLabsFailureKind.TOO_LARGE -> "The selected model is larger than 8 GiB"
            AiLabsFailureKind.INSUFFICIENT_SPACE -> "There is not enough storage space to import this model"
            AiLabsFailureKind.TRUNCATED_MODEL -> "The model file is incomplete"
            AiLabsFailureKind.INVALID_FORMAT -> "The file is not a supported local model"
            AiLabsFailureKind.CORRUPT_MODEL -> "The model file is corrupt"
            AiLabsFailureKind.UNSUPPORTED_ROLE -> "The model does not support the requested task"
            AiLabsFailureKind.UNSUPPORTED_ARCHITECTURE -> "The model architecture is unsupported"
            AiLabsFailureKind.NATIVE_OUT_OF_MEMORY -> "There is not enough memory to inspect this model"
            AiLabsFailureKind.RUNTIME_FAILURE -> "The local AI runtime could not inspect this model"
            AiLabsFailureKind.ATOMIC_INSTALL -> "The model could not be installed atomically"
            AiLabsFailureKind.DELETION -> "The model could not be deleted"
            AiLabsFailureKind.MODEL_NOT_FOUND -> "The selected model is no longer available"
            AiLabsFailureKind.MODEL_NOT_READY -> "The selected model is not ready for this feature"
            AiLabsFailureKind.INVALID_SETTINGS -> "The model settings are invalid"
            AiLabsFailureKind.PERSISTENCE -> "The AI settings could not be read or saved"
            AiLabsFailureKind.INTERNAL -> "The local AI model operation failed"
        }

internal data class AiModelSourceMetadata(
    val displayName: String? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
)

internal interface AiModelSource {
    fun metadata(uri: Uri): AiModelSourceMetadata

    fun metadata(
        uri: Uri,
        cancellationSignal: CancellationSignal,
    ): AiModelSourceMetadata = metadata(uri)

    fun open(uri: Uri): InputStream

    fun open(
        uri: Uri,
        cancellationSignal: CancellationSignal,
    ): InputStream = open(uri)
}

internal interface AiLabsRuntimeBoundary {
    suspend fun inspect(
        modelId: String,
        modelFile: File,
        capability: AiModelCapability,
    ): AiModelMetadata

    suspend fun unloadForDeletion(modelId: String)
}

private class ContentResolverModelSource(
    private val resolver: ContentResolver,
) : AiModelSource {
    override fun metadata(uri: Uri): AiModelSourceMetadata = metadata(uri, CancellationSignal())

    override fun metadata(
        uri: Uri,
        cancellationSignal: CancellationSignal,
    ): AiModelSourceMetadata {
        var name: String? = null
        var size: Long? = null
        resolver
            .query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
                cancellationSignal,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { index ->
                        if (!cursor.isNull(index)) size = cursor.getLong(index).takeIf { it >= 0 }
                    }
                }
            }
        return AiModelSourceMetadata(name, size)
    }

    override fun open(uri: Uri): InputStream = open(uri, CancellationSignal())

    override fun open(
        uri: Uri,
        cancellationSignal: CancellationSignal,
    ): InputStream {
        val descriptor =
            resolver.openAssetFileDescriptor(uri, "r", cancellationSignal)
                ?: throw FileNotFoundException()
        return try {
            descriptor.createInputStream()
        } catch (failure: Throwable) {
            try {
                descriptor.close()
            } catch (_: Exception) {
                // Preserve the open failure.
            }
            throw failure
        }
    }
}

private class CoordinatorBoundary(
    private val coordinator: AiExecutionCoordinator,
) : AiLabsRuntimeBoundary {
    override suspend fun inspect(
        modelId: String,
        modelFile: File,
        capability: AiModelCapability,
    ): AiModelMetadata = coordinator.inspect(modelId, modelFile, capability)

    override suspend fun unloadForDeletion(modelId: String) {
        coordinator.unloadForDeletion(modelId)
    }
}

@Singleton
class AiLabsRepository internal constructor(
    private val store: DataStore<Preferences>,
    private val modelDirectory: File,
    private val source: AiModelSource,
    private val runtime: AiLabsRuntimeBoundary,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
    private val availableBytes: (File) -> Long,
    private val allocateBytes: (File, Long) -> Unit,
    private val maximumModelBytes: Long = MAX_MODEL_BYTES,
    private val freeSpaceReserveBytes: Long = FREE_SPACE_RESERVE_BYTES,
    private val copyBufferBytes: Int = COPY_BUFFER_BYTES,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        coordinator: AiExecutionCoordinator,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        context.aiLabsDataStore,
        File(context.noBackupFilesDir, MODEL_DIRECTORY),
        ContentResolverModelSource(context.contentResolver),
        CoordinatorBoundary(coordinator),
        scope,
        Dispatchers.IO,
        System::currentTimeMillis,
        Runtime.getRuntime().availableProcessors(),
        { StatFs(it.absolutePath).availableBytes },
        { directory, requiredBytes ->
            val storage = checkNotNull(context.getSystemService(StorageManager::class.java))
            val volume = storage.getUuidForPath(directory)
            val allocatable = storage.getAllocatableBytes(volume)
            if (requiredBytes > allocatable) throw AiLabsException(AiLabsFailureKind.INSUFFICIENT_SPACE)
            // Reclaim headroom so low-space copies do not evict cache for every buffer.
            storage.allocateBytes(volume, requiredBytes + minOf(FREE_SPACE_RESERVE_BYTES, allocatable - requiredBytes))
        },
        MAX_MODEL_BYTES,
        FREE_SPACE_RESERVE_BYTES,
        COPY_BUFFER_BYTES,
    )

    private val mutations = Mutex()
    private val transientImportState = MutableStateFlow<AiImportState>(AiImportState.Idle)
    private val persistedState: Flow<AiLabsState> =
        store.data
            .catch { failure ->
                if (failure is IOException) emit(emptyPreferences()) else throw failure
            }.map { preferences -> normalized(decode(preferences[AI_LABS_STATE]) ?: AiLabsState()) }

    val state: StateFlow<AiLabsState> =
        combine(persistedState, transientImportState) { persisted, importing ->
            persisted.copy(importState = importing)
        }.stateIn(scope, SharingStarted.Eagerly, AiLabsState())

    fun modelFile(modelId: String): File {
        require(isValidAiModelId(modelId)) { "Invalid model ID" }
        return File(modelDirectory, "$modelId$MODEL_SUFFIX")
    }

    suspend fun setFeatureEnabled(
        feature: AiFeature,
        enabled: Boolean,
    ): Result<Unit> =
        repositoryResult {
            mutations.withLock {
                editState { current ->
                    if (!enabled) {
                        current.copy(enabledFeatures = current.enabledFeatures - feature)
                    } else {
                        val modelId =
                            current.assignedModelId(feature)
                                ?: throw AiLabsException(AiLabsFailureKind.MODEL_NOT_READY)
                        val model =
                            current.models.firstOrNull { it.id == modelId }
                                ?: throw AiLabsException(AiLabsFailureKind.MODEL_NOT_READY)
                        if (!model.isReadyFor(feature.requiredCapability, current.settingsFor(modelId, feature.requiredCapability))) {
                            throw AiLabsException(AiLabsFailureKind.MODEL_NOT_READY)
                        }
                        current.copy(enabledFeatures = current.enabledFeatures + feature)
                    }
                }
            }
        }

    suspend fun importModel(
        uri: Uri,
        requestedCapability: AiModelCapability,
        onProgress: (bytesCopied: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Result<AiModelRecord> =
        repositoryResult {
            mutations.withLock {
                withContext(ioDispatcher) { importLocked(uri, requestedCapability, onProgress) }
            }
        }

    suspend fun assignModel(
        feature: AiFeature,
        modelId: String,
    ): Result<Unit> =
        repositoryResult {
            mutations.withLock {
                editState { current ->
                    val model =
                        current.models.firstOrNull { it.id == modelId }
                            ?: throw AiLabsException(AiLabsFailureKind.MODEL_NOT_FOUND)
                    val capability = feature.requiredCapability
                    if (!model.isReadyFor(capability, current.settingsFor(modelId, capability))) {
                        throw AiLabsException(AiLabsFailureKind.MODEL_NOT_READY)
                    }
                    current.copy(
                        assignments =
                            current.assignments.filterNot { it.feature == feature } +
                                AiFeatureAssignment(feature, modelId),
                    )
                }
            }
        }

    suspend fun updateSettings(
        modelId: String,
        capability: AiModelCapability,
        settings: TranscriptionSettings,
    ): Result<Unit> =
        repositoryResult {
            mutations.withLock {
                editState { current ->
                    val model =
                        current.models.firstOrNull { it.id == modelId }
                            ?: throw AiLabsException(AiLabsFailureKind.MODEL_NOT_FOUND)
                    if (capability !in model.capabilities) {
                        throw AiLabsException(AiLabsFailureKind.INVALID_SETTINGS)
                    }
                    if (!settings.isRuntimeCompatible(model.metadata)) {
                        throw AiLabsException(AiLabsFailureKind.INVALID_SETTINGS)
                    }
                    val clamped = settings.clampedTo(model.metadata, availableProcessors)
                    val updated =
                        current.copy(
                            transcriptionSettings =
                                current.transcriptionSettings.filterNot { it.modelId == modelId } +
                                    AiTranscriptionSettingsRecord(modelId, clamped),
                        )
                    val affected =
                        updated.assignments
                            .filter { it.modelId == modelId && it.feature.requiredCapability == capability }
                            .filterNot { model.isReadyFor(capability, updated.settingsFor(modelId, capability)) }
                            .mapTo(mutableSetOf()) { it.feature }
                    updated.copy(
                        assignments = updated.assignments.filterNot { it.feature in affected },
                        enabledFeatures = updated.enabledFeatures - affected,
                    )
                }
            }
        }

    suspend fun deleteModel(modelId: String): Result<Unit> =
        repositoryResult {
            mutations.withLock { withContext(ioDispatcher) { deleteLocked(modelId) } }
        }

    suspend fun reconcile(): Result<Unit> =
        repositoryResult {
            mutations.withLock { withContext(ioDispatcher) { reconcileLocked() } }
        }

    private suspend fun importLocked(
        uri: Uri,
        capability: AiModelCapability,
        onProgress: (Long, Long?) -> Unit,
    ): AiModelRecord {
        ensureModelDirectory()
        val metadata = advisoryMetadata(uri)
        val totalBytes = metadata.sizeBytes?.takeIf { it > 0 }
        reportImportProgress(0, totalBytes, onProgress)
        var temporary: File? = null
        var installed: File? = null
        var committed = false
        try {
            ensureSpace(0)
            temporary = createImportTemporary()
            val copied = copySource(uri, temporary, totalBytes, onProgress)
            preflight(temporary, copied.prefix, copied.bytesCopied)
            val id = copied.sha256
            val destination = modelFile(id)
            val inspectionFile =
                if (destination.exists()) {
                    if (destination.length() != copied.bytesCopied || sha256(destination) != id) {
                        throw AiLabsException(AiLabsFailureKind.CORRUPT_MODEL)
                    }
                    destination
                } else {
                    temporary
                }
            val inspected = inspect(id, inspectionFile, capability)
            if (!destination.exists()) {
                atomicMove(temporary, destination, AiLabsFailureKind.ATOMIC_INSTALL)
                installed = destination
                temporary = null
            }
            val record = addImportedModel(id, cleanDisplayName(metadata.displayName), copied.bytesCopied, capability, inspected)
            committed = true
            return record
        } finally {
            transientImportState.value = AiImportState.Idle
            temporary?.deleteQuietly()
            if (!committed) installed?.deleteQuietly()
        }
    }

    private suspend fun advisoryMetadata(uri: Uri): AiModelSourceMetadata =
        try {
            suspendCancellableCoroutine { continuation ->
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancelQuietly() }
                try {
                    val metadata = source.metadata(uri, cancellationSignal)
                    if (continuation.isActive) continuation.resumeWith(Result.success(metadata))
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            AiModelSourceMetadata()
        }

    private fun createImportTemporary(): File =
        try {
            File.createTempFile(IMPORT_PREFIX, TEMP_SUFFIX, modelDirectory)
        } catch (_: IOException) {
            if (hasSpace(0)) throw AiLabsException(AiLabsFailureKind.STORAGE_WRITE)
            throw AiLabsException(AiLabsFailureKind.INSUFFICIENT_SPACE)
        }

    private suspend fun copySource(
        uri: Uri,
        destination: File,
        totalBytes: Long?,
        onProgress: (Long, Long?) -> Unit,
    ): CopiedModel {
        val context = currentCoroutineContext()
        return suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            val activeInput = AtomicReference<InputStream?>()
            continuation.invokeOnCancellation {
                cancellationSignal.cancelQuietly()
                activeInput.get()?.closeQuietly()
            }
            try {
                context.ensureActive()
                val input = openSource(uri, cancellationSignal)
                activeInput.set(input)
                context.ensureActive()
                val digest = MessageDigest.getInstance("SHA-256")
                val prefix = ByteArray(4)
                var prefixBytes = 0
                var copied = 0L
                val buffer = ByteArray(copyBufferBytes.coerceAtLeast(4))
                input.use { sourceStream ->
                    val output =
                        try {
                            FileOutputStream(destination)
                        } catch (_: IOException) {
                            throw AiLabsException(AiLabsFailureKind.STORAGE_WRITE)
                        }
                    output.use { target ->
                        while (true) {
                            context.ensureActive()
                            val count =
                                try {
                                    sourceStream.read(buffer)
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (_: SecurityException) {
                                    throw AiLabsException(AiLabsFailureKind.SOURCE_PERMISSION)
                                } catch (_: IOException) {
                                    throw AiLabsException(AiLabsFailureKind.SOURCE_READ)
                                }
                            if (count < 0) break
                            if (count == 0) continue
                            if (copied > maximumModelBytes - count) throw AiLabsException(AiLabsFailureKind.TOO_LARGE)
                            ensureSpace(count.toLong())
                            try {
                                target.write(buffer, 0, count)
                            } catch (_: IOException) {
                                if (!hasSpace(0)) throw AiLabsException(AiLabsFailureKind.INSUFFICIENT_SPACE)
                                throw AiLabsException(AiLabsFailureKind.STORAGE_WRITE)
                            }
                            if (prefixBytes < prefix.size) {
                                val prefixCount = minOf(prefix.size - prefixBytes, count)
                                buffer.copyInto(prefix, prefixBytes, 0, prefixCount)
                                prefixBytes += prefixCount
                            }
                            digest.update(buffer, 0, count)
                            copied += count
                            reportImportProgress(copied, totalBytes, onProgress)
                        }
                        context.ensureActive()
                        try {
                            target.fd.sync()
                        } catch (_: IOException) {
                            throw AiLabsException(AiLabsFailureKind.STORAGE_WRITE)
                        }
                    }
                }
                context.ensureActive()
                ensureSpace(0)
                val result = CopiedModel(copied, digest.digest().lowerHex(), prefix.copyOf(prefixBytes))
                if (continuation.isActive) continuation.resumeWith(Result.success(result))
            } catch (failure: Throwable) {
                val mapped =
                    when (failure) {
                        is CancellationException,
                        is AiLabsException,
                        -> failure

                        is SecurityException -> AiLabsException(AiLabsFailureKind.SOURCE_PERMISSION)

                        is IOException -> AiLabsException(AiLabsFailureKind.SOURCE_READ)

                        else -> failure
                    }
                if (continuation.isActive) continuation.resumeWith(Result.failure(mapped))
            } finally {
                activeInput.getAndSet(null)?.closeQuietly()
            }
        }
    }

    private fun openSource(
        uri: Uri,
        cancellationSignal: CancellationSignal,
    ): InputStream =
        try {
            source.open(uri, cancellationSignal)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: SecurityException) {
            throw AiLabsException(AiLabsFailureKind.SOURCE_PERMISSION)
        } catch (_: FileNotFoundException) {
            throw AiLabsException(AiLabsFailureKind.SOURCE_OPEN)
        } catch (_: IOException) {
            throw AiLabsException(AiLabsFailureKind.SOURCE_OPEN)
        } catch (_: Exception) {
            throw AiLabsException(AiLabsFailureKind.SOURCE_OPEN)
        }

    private fun reportImportProgress(
        copied: Long,
        total: Long?,
        callback: (Long, Long?) -> Unit,
    ) {
        transientImportState.value = AiImportState.Importing(copied, total)
        callback(copied, total)
    }

    private fun preflight(
        file: File,
        prefix: ByteArray = readPrefix(file),
        sizeBytes: Long = file.length(),
    ) {
        if (sizeBytes < 4 || prefix.size < 4) throw AiLabsException(AiLabsFailureKind.TRUNCATED_MODEL)
        val gguf = prefix.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        val whisper = prefix.contentEquals(byteArrayOf(0x6c, 0x6d, 0x67, 0x67))
        when {
            gguf -> throw AiLabsException(AiLabsFailureKind.UNSUPPORTED_ROLE)
            !whisper -> throw AiLabsException(AiLabsFailureKind.INVALID_FORMAT)
        }
    }

    private suspend fun inspect(
        id: String,
        file: File,
        capability: AiModelCapability,
    ): AiModelMetadata =
        try {
            runtime.inspect(id, file, capability)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: AiRuntimeException) {
            throw AiLabsException(
                when (failure.failure) {
                    AiRuntimeFailure.INVALID_FORMAT -> AiLabsFailureKind.INVALID_FORMAT

                    AiRuntimeFailure.TRUNCATED_MODEL -> AiLabsFailureKind.TRUNCATED_MODEL

                    AiRuntimeFailure.CORRUPT_MODEL,
                    AiRuntimeFailure.MODEL_OPEN,
                    -> AiLabsFailureKind.CORRUPT_MODEL

                    AiRuntimeFailure.UNSUPPORTED_ARCHITECTURE -> AiLabsFailureKind.UNSUPPORTED_ARCHITECTURE

                    AiRuntimeFailure.OUT_OF_MEMORY -> AiLabsFailureKind.NATIVE_OUT_OF_MEMORY

                    else -> AiLabsFailureKind.RUNTIME_FAILURE
                },
            )
        } catch (_: OutOfMemoryError) {
            throw AiLabsException(AiLabsFailureKind.NATIVE_OUT_OF_MEMORY)
        } catch (_: Exception) {
            throw AiLabsException(AiLabsFailureKind.RUNTIME_FAILURE)
        }

    private suspend fun addImportedModel(
        id: String,
        displayName: String,
        sizeBytes: Long,
        capability: AiModelCapability,
        inspected: AiModelMetadata,
    ): AiModelRecord {
        var result: AiModelRecord? = null
        editState { current ->
            val existing = current.models.firstOrNull { it.id == id }
            val record =
                existing?.copy(
                    sizeBytes = sizeBytes,
                    metadata = inspected,
                ) ?: AiModelRecord(
                    id,
                    displayName,
                    sizeBytes,
                    AiModelFormat.WHISPER_GGML,
                    setOf(capability),
                    inspected,
                    now().coerceAtLeast(0),
                )
            result = record
            current.copy(models = current.models.filterNot { it.id == id } + record).ensureSettings(record)
        }
        return checkNotNull(result)
    }

    private suspend fun deleteLocked(modelId: String) {
        if (!isValidAiModelId(modelId)) throw AiLabsException(AiLabsFailureKind.MODEL_NOT_FOUND)
        ensureModelDirectory()
        val before = readStateForMutation()
        if (before.models.none { it.id == modelId }) throw AiLabsException(AiLabsFailureKind.MODEL_NOT_FOUND)
        try {
            runtime.unloadForDeletion(modelId)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            throw AiLabsException(AiLabsFailureKind.DELETION)
        }

        val canonical = modelFile(modelId)
        val staged = File(modelDirectory, "$DELETE_PREFIX$modelId$TEMP_SUFFIX")
        if (staged.exists() && !staged.delete()) throw AiLabsException(AiLabsFailureKind.DELETION)
        val moved = canonical.exists()
        if (moved) atomicMove(canonical, staged, AiLabsFailureKind.DELETION)
        var stateChanged = false
        try {
            editState { it.withoutModels(setOf(modelId)) }
            stateChanged = true
            if (moved && !staged.delete()) throw AiLabsException(AiLabsFailureKind.DELETION)
        } catch (failure: CancellationException) {
            rollbackDeletion(before, canonical, staged, moved, stateChanged)
            throw failure
        } catch (failure: AiLabsException) {
            rollbackDeletion(before, canonical, staged, moved, stateChanged)
            throw failure
        } catch (_: Exception) {
            rollbackDeletion(before, canonical, staged, moved, stateChanged)
            throw AiLabsException(AiLabsFailureKind.DELETION)
        }
    }

    private suspend fun rollbackDeletion(
        before: AiLabsState,
        canonical: File,
        staged: File,
        moved: Boolean,
        stateChanged: Boolean,
    ) {
        var failed = false
        if (moved && staged.exists()) {
            try {
                atomicMove(staged, canonical, AiLabsFailureKind.DELETION)
            } catch (_: AiLabsException) {
                failed = true
            }
        }
        if (stateChanged) {
            try {
                replaceState(before)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                failed = true
            }
        }
        if (failed) throw AiLabsException(AiLabsFailureKind.DELETION)
    }

    private suspend fun reconcileLocked() {
        ensureModelDirectory()
        val current = readStateForMutation()
        recoverStagedDeletions(current)
        val files = modelDirectory.listFiles() ?: throw AiLabsException(AiLabsFailureKind.DELETION)
        files.filter { it.name.startsWith(IMPORT_PREFIX) && it.name.endsWith(TEMP_SUFFIX) }.forEach(::deleteOrFail)

        val invalid = mutableSetOf<String>()
        current.models.forEach { model ->
            currentCoroutineContext().ensureActive()
            val file = modelFile(model.id)
            val valid =
                file.isFile &&
                    file.length() == model.sizeBytes &&
                    try {
                        preflight(file)
                        sha256(file) == model.id
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        false
                    }
            if (!valid) invalid += model.id
        }

        val reconciled = normalized(current.withoutModels(invalid))
        if (reconciled != current) {
            replaceState(reconciled)
        }

        modelDirectory.listFiles().orEmpty().forEach { file ->
            currentCoroutineContext().ensureActive()
            val canonicalId =
                file.name
                    .removeSuffix(MODEL_SUFFIX)
                    .takeIf { file.name.endsWith(MODEL_SUFFIX) && isValidAiModelId(it) }
            when {
                file.name.startsWith(DELETE_PREFIX) && file.name.endsWith(TEMP_SUFFIX) -> deleteOrFail(file)

                // Retired imports have no voice record. Keep their weights; never load or delete them.
                canonicalId in invalid -> deleteOrFail(file)

                canonicalId == null -> deleteOrFail(file)
            }
        }
    }

    private fun recoverStagedDeletions(current: AiLabsState) {
        val records = current.models.mapTo(mutableSetOf()) { it.id }
        modelDirectory
            .listFiles { file -> file.name.startsWith(DELETE_PREFIX) && file.name.endsWith(TEMP_SUFFIX) }
            .orEmpty()
            .forEach { staged ->
                val id = staged.name.removePrefix(DELETE_PREFIX).removeSuffix(TEMP_SUFFIX)
                val canonical = id.takeIf(::isValidAiModelId)?.let(::modelFile)
                if (id in records && canonical != null && !canonical.exists()) {
                    atomicMove(staged, canonical, AiLabsFailureKind.DELETION)
                } else {
                    deleteOrFail(staged)
                }
            }
    }

    private suspend fun readStateForMutation(): AiLabsState {
        var result: AiLabsState? = null
        editPreferences { preferences ->
            result = normalized(decodeForMutation(preferences[AI_LABS_STATE]))
            preferences[AI_LABS_STATE] = encode(checkNotNull(result))
        }
        return checkNotNull(result)
    }

    private suspend fun editState(transform: (AiLabsState) -> AiLabsState) {
        editPreferences { preferences ->
            val current = normalized(decodeForMutation(preferences[AI_LABS_STATE]))
            preferences[AI_LABS_STATE] = encode(normalized(transform(current)))
        }
    }

    private suspend fun replaceState(value: AiLabsState) {
        editPreferences { it[AI_LABS_STATE] = encode(normalized(value)) }
    }

    private suspend fun editPreferences(transform: suspend (MutablePreferences) -> Unit) {
        try {
            store.edit(transform)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: AiLabsException) {
            throw failure
        } catch (_: Exception) {
            throw AiLabsException(AiLabsFailureKind.PERSISTENCE)
        }
    }

    private fun normalized(raw: AiLabsState): AiLabsState {
        val models = raw.models.distinctBy { it.id }
        var value =
            raw.copy(
                models = models,
                assignments = raw.assignments.distinctBy { it.feature },
                transcriptionSettings = raw.transcriptionSettings.distinctBy { it.modelId },
                importState = AiImportState.Idle,
            )
        models.forEach { model ->
            value = value.ensureSettings(model)
        }
        value =
            value.copy(
                transcriptionSettings =
                    value.transcriptionSettings.mapNotNull { record ->
                        models
                            .firstOrNull { it.id == record.modelId && AiModelCapability.TRANSCRIPTION in it.capabilities }
                            ?.let { model -> record.copy(settings = record.settings.clampedTo(model.metadata, availableProcessors)) }
                    },
            )
        val validAssignments =
            value.assignments.filter { assignment ->
                val model = models.firstOrNull { it.id == assignment.modelId } ?: return@filter false
                model.isReadyFor(
                    assignment.feature.requiredCapability,
                    value.settingsFor(assignment.modelId, assignment.feature.requiredCapability),
                )
            }
        return value.copy(
            assignments = validAssignments,
            enabledFeatures = value.enabledFeatures.intersect(validAssignments.mapTo(mutableSetOf()) { it.feature }),
        )
    }

    private fun AiLabsState.ensureSettings(model: AiModelRecord): AiLabsState =
        if (transcriptionSettings.any { it.modelId == model.id }) {
            this
        } else {
            copy(
                transcriptionSettings =
                    transcriptionSettings +
                        AiTranscriptionSettingsRecord(model.id, TranscriptionSettings.defaults(model.metadata, availableProcessors)),
            )
        }

    private fun AiLabsState.withoutModels(ids: Set<String>): AiLabsState {
        if (ids.isEmpty()) return this
        val affected = assignments.filter { it.modelId in ids }.mapTo(mutableSetOf()) { it.feature }
        return copy(
            enabledFeatures = enabledFeatures - affected,
            models = models.filterNot { it.id in ids },
            assignments = assignments.filterNot { it.modelId in ids },
            transcriptionSettings = transcriptionSettings.filterNot { it.modelId in ids },
        )
    }

    private fun ensureModelDirectory() {
        if ((!modelDirectory.exists() && !modelDirectory.mkdirs()) || !modelDirectory.isDirectory) {
            throw AiLabsException(AiLabsFailureKind.STORAGE_WRITE)
        }
    }

    private fun ensureSpace(bytesToWrite: Long) {
        if (hasSpace(bytesToWrite)) return
        try {
            allocateBytes(modelDirectory, bytesToWrite + freeSpaceReserveBytes)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            throw AiLabsException(AiLabsFailureKind.INSUFFICIENT_SPACE)
        }
        if (!hasSpace(bytesToWrite)) throw AiLabsException(AiLabsFailureKind.INSUFFICIENT_SPACE)
    }

    private fun hasSpace(bytesToWrite: Long): Boolean {
        val available =
            try {
                availableBytes(modelDirectory)
            } catch (_: Exception) {
                0L
            }
        return bytesToWrite >= 0 && available >= freeSpaceReserveBytes &&
            bytesToWrite <= available - freeSpaceReserveBytes
    }

    private fun atomicMove(
        source: File,
        destination: File,
        failureKind: AiLabsFailureKind,
    ) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            throw AiLabsException(failureKind)
        } catch (_: IOException) {
            throw AiLabsException(failureKind)
        } catch (_: SecurityException) {
            throw AiLabsException(failureKind)
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(copyBufferBytes.coerceAtLeast(4))
        try {
            FileInputStream(file).use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: IOException) {
            throw AiLabsException(AiLabsFailureKind.CORRUPT_MODEL)
        }
        return digest.digest().lowerHex()
    }

    private fun readPrefix(file: File): ByteArray =
        try {
            FileInputStream(file).use { input ->
                val bytes = ByteArray(4)
                var read = 0
                while (read < bytes.size) {
                    val count = input.read(bytes, read, bytes.size - read)
                    if (count < 0) break
                    read += count
                }
                bytes.copyOf(read)
            }
        } catch (_: IOException) {
            throw AiLabsException(AiLabsFailureKind.CORRUPT_MODEL)
        }

    private fun deleteOrFail(file: File) {
        if (file.exists() && !file.delete()) throw AiLabsException(AiLabsFailureKind.DELETION)
    }

    private fun File.deleteQuietly() {
        try {
            Files.deleteIfExists(toPath())
        } catch (_: Exception) {
            // reconcile() removes import temporaries; unregistered weights are retained.
        }
    }

    private fun decode(raw: String?): AiLabsState? =
        if (raw == null) {
            AiLabsState()
        } else {
            try {
                val document = AI_LABS_JSON.parseToJsonElement(raw).jsonObject
                val voiceOnly =
                    JsonObject(
                        document.toMutableMap().apply {
                            // state_v1 also held text models. Filter retired enums before decoding.
                            put(
                                "enabledFeatures",
                                JsonArray(document["enabledFeatures"]?.jsonArray.orEmpty().filter { it.jsonPrimitive.content == "TRANSCRIPTION" }),
                            )
                            put(
                                "models",
                                JsonArray(document["models"]?.jsonArray.orEmpty().filter { it.jsonObject["format"]?.jsonPrimitive?.content == "WHISPER_GGML" }),
                            )
                            put(
                                "assignments",
                                JsonArray(document["assignments"]?.jsonArray.orEmpty().filter { it.jsonObject["feature"]?.jsonPrimitive?.content == "TRANSCRIPTION" }),
                            )
                            remove("generationSettings")
                            remove("embeddingSettings")
                        },
                    )
                AI_LABS_JSON.decodeFromJsonElement<AiLabsState>(voiceOnly)
            } catch (_: Exception) {
                null
            }
        }

    private fun decodeForMutation(raw: String?): AiLabsState = decode(raw) ?: throw AiLabsException(AiLabsFailureKind.PERSISTENCE)

    private fun encode(state: AiLabsState): String = AI_LABS_JSON.encodeToString(state.copy(importState = AiImportState.Idle))

    private data class CopiedModel(
        val bytesCopied: Long,
        val sha256: String,
        val prefix: ByteArray,
    )
}

private fun CancellationSignal.cancelQuietly() {
    try {
        cancel()
    } catch (_: Exception) {
        // Cancellation must still close the active stream.
    }
}

private fun InputStream.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Cancellation already owns the operation result.
    }
}

private fun ByteArray.lowerHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        this@lowerHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0xf])
        }
    }
}

private fun cleanDisplayName(value: String?): String {
    val cleaned =
        value
            .orEmpty()
            .filterNot(Char::isISOControl)
            .trim()
            .take(200)
    return cleaned.ifBlank { "Imported model" }
}

private suspend inline fun <T> repositoryResult(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: AiLabsException) {
        Result.failure(failure)
    } catch (_: OutOfMemoryError) {
        Result.failure(AiLabsException(AiLabsFailureKind.NATIVE_OUT_OF_MEMORY))
    } catch (_: Throwable) {
        Result.failure(AiLabsException(AiLabsFailureKind.INTERNAL))
    }
