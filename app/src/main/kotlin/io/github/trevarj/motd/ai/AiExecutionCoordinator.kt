package io.github.trevarj.motd.ai

import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.di.DefaultDispatcher
import io.github.trevarj.motd.service.AppVisibility
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

class AiExecutionUnavailableException : CancellationException("AI execution is unavailable while the app is backgrounded")

/**
 * Owns the app's single native-model residency and serializes all local inference.
 *
 * The background path releases the small registration lock before it waits for child cleanup or
 * the execution mutex. That lets it reject and cancel both the active request and mutex waiters
 * without reversing the lock order.
 */
@Singleton
class AiExecutionCoordinator
    @Inject
    constructor(
        private val speechRuntime: SpeechModelRuntime,
        private val appVisibility: AppVisibility,
        @DefaultDispatcher private val unloadDispatcher: CoroutineDispatcher,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) {
        private val executionMutex = Mutex()
        private val registryLock = Any()
        private val started = AtomicBoolean(false)
        private val operations = mutableSetOf<Job>()

        private var acceptingOperations = false
        private var activeOperation: Job? = null
        private var residentModelId: String? = null
        private var residentReady = false

        fun start() {
            if (!started.compareAndSet(false, true)) return
            synchronized(registryLock) {
                acceptingOperations = appVisibility.onScreen.value
            }
            applicationScope.launch {
                appVisibility.onScreen.collect { onScreen ->
                    if (onScreen) {
                        synchronized(registryLock) { acceptingOperations = true }
                    } else {
                        moveToBackground()
                    }
                }
            }
        }

        suspend fun inspect(
            modelId: String,
            modelFile: File,
            capability: AiModelCapability,
        ): AiModelMetadata {
            requireModelId(modelId)
            return withRegisteredExecution {
                unloadResident()
                residentModelId = modelId
                residentReady = false
                var operationFailure: Throwable? = null
                try {
                    val metadata = speechRuntime.inspect(modelFile, capability)
                    currentCoroutineContext().ensureActive()
                    metadata
                } catch (failure: Throwable) {
                    operationFailure = failure
                    throw failure
                } finally {
                    try {
                        unloadResident()
                    } catch (cleanupFailure: Throwable) {
                        operationFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                    }
                }
            }
        }

        suspend fun transcribe(
            modelId: String,
            modelFile: File,
            capability: AiModelCapability,
            pcmWav: File,
            request: AiTranscriptionRequest,
            onProgress: (Int) -> Unit,
        ): String {
            requireModelId(modelId)
            return withRegisteredExecution {
                ensureResident(modelId, modelFile, capability)
                val operation = currentCoroutineContext()[Job]!!
                val result =
                    speechRuntime.transcribe(pcmWav, request) { progress ->
                        if (operation.isActive) onProgress(progress)
                    }
                currentCoroutineContext().ensureActive()
                result
            }
        }

        /** Must complete before the caller removes the matching model file. */
        suspend fun unloadForDeletion(modelId: String) {
            requireModelId(modelId)
            withRegisteredExecution {
                if (residentModelId == modelId) unloadResident()
            }
        }

        private suspend fun moveToBackground() {
            val registered =
                synchronized(registryLock) {
                    acceptingOperations = false
                    val active = activeOperation
                    listOfNotNull(active) + operations.filter { it !== active }
                }
            val cancellation = CancellationException("AI execution cancelled because the app entered the background")
            registered.forEach { it.cancel(cancellation) }
            registered.joinAll()
            executionMutex.withLock { unloadResident() }
        }

        private suspend fun ensureResident(
            modelId: String,
            modelFile: File,
            capability: AiModelCapability,
        ) {
            if (residentModelId == modelId && residentReady) return
            unloadResident()
            // Record partial loads so cancellation always unloads the native model.
            residentModelId = modelId
            residentReady = false
            try {
                speechRuntime.load(modelFile, capability)
                residentReady = true
                currentCoroutineContext().ensureActive()
            } catch (failure: Throwable) {
                residentReady = false
                try {
                    unloadResident()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }

        private suspend fun unloadResident() {
            if (residentModelId == null) return
            withContext(NonCancellable + unloadDispatcher) {
                speechRuntime.unload()
                residentModelId = null
                residentReady = false
            }
        }

        private suspend fun <T> withRegisteredExecution(block: suspend () -> T): T =
            kotlinx.coroutines.coroutineScope {
                val operation =
                    async(start = CoroutineStart.LAZY) {
                        executionMutex.withLock {
                            val job = currentCoroutineContext()[Job]!!
                            synchronized(registryLock) { activeOperation = job }
                            try {
                                currentCoroutineContext().ensureActive()
                                block()
                            } finally {
                                synchronized(registryLock) {
                                    if (activeOperation === job) activeOperation = null
                                }
                            }
                        }
                    }
                val accepted =
                    synchronized(registryLock) {
                        if (!appVisibility.onScreen.value) acceptingOperations = false
                        if (!acceptingOperations) {
                            false
                        } else {
                            operations += operation
                            true
                        }
                    }
                if (!accepted) {
                    val rejection = AiExecutionUnavailableException()
                    operation.cancel(rejection)
                    throw rejection
                }
                operation.invokeOnCompletion {
                    synchronized(registryLock) { operations -= operation }
                }
                operation.start()
                operation.await()
            }

        private fun requireModelId(modelId: String) {
            require(isValidAiModelId(modelId)) { "Model ID must be a lowercase SHA-256" }
        }
    }
