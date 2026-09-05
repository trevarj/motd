package io.github.trevarj.motd.ai

import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiDerivedCacheCleaner
    @Inject
    constructor(
        private val transcriptCache: AiTranscriptCache,
    ) {
        suspend fun clear(): Result<Unit> =
            try {
                transcriptCache.clear()
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
    }
