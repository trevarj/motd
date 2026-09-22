package io.github.trevarj.motd.service

/**
 * Cancellable, pure timing boundary for settings previews. It waits for every selected sample to
 * load before beginning a phrase, and owns every runnable it posts so a dismissed sheet cannot
 * leave delayed notes behind.
 */
internal class ChatSoundPreview(
    private val nowMs: () -> Long,
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val stopPlaying: () -> Unit,
) {
    private val lock = Any()
    private val tasks = mutableSetOf<Runnable>()
    private var generation = 0L

    fun start(
        noteCount: Int,
        ready: () -> Boolean,
        playNote: (Int) -> Unit,
    ) {
        require(noteCount == 1 || noteCount == PHRASE_DELAYS_MS.size)
        synchronized(lock) {
            stopLocked()
            val currentGeneration = generation
            val deadline = nowMs() + READY_TIMEOUT_MS
            scheduleAwait(currentGeneration, deadline, noteCount, ready, playNote, delayMs = 0L)
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun scheduleAwait(
        currentGeneration: Long,
        deadline: Long,
        noteCount: Int,
        ready: () -> Boolean,
        playNote: (Int) -> Unit,
        delayMs: Long,
    ) {
        lateinit var await: Runnable
        await =
            Runnable {
                synchronized(lock) {
                    tasks.remove(await)
                    if (currentGeneration != generation) return@synchronized
                    if (nowMs() > deadline) return@synchronized
                    if (ready()) {
                        PHRASE_DELAYS_MS.take(noteCount).forEachIndexed { index, delay ->
                            scheduleNote(currentGeneration, index, delay, playNote)
                        }
                    } else if (nowMs() < deadline) {
                        scheduleAwait(currentGeneration, deadline, noteCount, ready, playNote, RETRY_DELAY_MS)
                    }
                }
            }
        tasks += await
        post(await, delayMs)
    }

    private fun scheduleNote(
        currentGeneration: Long,
        index: Int,
        delayMs: Long,
        playNote: (Int) -> Unit,
    ) {
        lateinit var note: Runnable
        note =
            Runnable {
                synchronized(lock) {
                    tasks.remove(note)
                    if (currentGeneration == generation) playNote(index)
                }
            }
        tasks += note
        post(note, delayMs)
    }

    private fun stopLocked() {
        generation++
        tasks.forEach(remove)
        tasks.clear()
        stopPlaying()
    }

    private companion object {
        const val READY_TIMEOUT_MS = 1_000L
        const val RETRY_DELAY_MS = 24L
        val PHRASE_DELAYS_MS = longArrayOf(0L, 180L, 480L, 660L, 1_020L)
    }
}
