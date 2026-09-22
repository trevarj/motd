package io.github.trevarj.motd.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSoundPreviewTest {
    @Test
    fun `readiness wait preserves every phrase note`() {
        val queue = Queue()
        val played = mutableListOf<Int>()
        var ready = false
        val preview = queue.preview()

        preview.start(5, ready = { ready }, playNote = played::add)
        queue.runDue()
        ready = true
        queue.advanceBy(24)
        queue.runAll()

        assertEquals(listOf(0, 1, 2, 3, 4), played)
    }

    @Test
    fun `readiness timeout posts no notes`() {
        val queue = Queue()
        val played = mutableListOf<Int>()
        queue.preview().start(1, ready = { false }, playNote = played::add)

        queue.runUntil(1_100)

        assertTrue(played.isEmpty())
        assertTrue(queue.pending.isEmpty())
    }

    @Test
    fun `stop during wait cancels retry`() {
        val queue = Queue()
        val played = mutableListOf<Int>()
        val preview = queue.preview()
        preview.start(5, ready = { false }, playNote = played::add)
        queue.runDue()
        preview.stop()
        queue.runAll(includeRemoved = true)

        assertTrue(played.isEmpty())
    }

    @Test
    fun `stop after first note cancels phrase remainder`() {
        val queue = Queue()
        val played = mutableListOf<Int>()
        val preview = queue.preview()
        preview.start(5, ready = { true }, playNote = played::add)
        queue.runDue()
        queue.runDue()
        preview.stop()
        queue.runAll(includeRemoved = true)

        assertEquals(listOf(0), played)
    }

    @Test
    fun `superseded callbacks cannot play after restart`() {
        val queue = Queue()
        val played = mutableListOf<Int>()
        val preview = queue.preview()
        preview.start(5, ready = { true }, playNote = { played += 10 + it })
        val stale = queue.pending.toList()
        preview.start(1, ready = { true }, playNote = { played += it })
        stale.forEach { it.runnable.run() }
        queue.runAll()

        assertEquals(listOf(0), played)
    }

    private class Queue {
        var now = 0L
        val pending = mutableListOf<Entry>()
        private val removed = mutableListOf<Entry>()
        private var stopped = 0

        fun preview() =
            ChatSoundPreview({ now }, { runnable, delay -> pending += Entry(runnable, now + delay) }, { runnable ->
                removed += pending.filter { it.runnable === runnable }
                pending.removeAll { it.runnable === runnable }
            }, { stopped++ })

        fun advanceBy(delta: Long) {
            now += delta
            runDue()
        }

        fun runDue() {
            while (true) {
                val next = pending.filter { it.whenMs <= now }.minByOrNull { it.whenMs } ?: return
                pending.remove(next)
                next.runnable.run()
            }
        }

        fun runUntil(limit: Long) {
            while (pending.isNotEmpty()) {
                val next = pending.minByOrNull { it.whenMs } ?: return
                if (next.whenMs > limit) return
                now = next.whenMs
                runDue()
            }
        }

        fun runAll(includeRemoved: Boolean = false) {
            if (includeRemoved) {
                val stale = removed.toList()
                removed.clear()
                stale.forEach { it.runnable.run() }
            }
            while (pending.isNotEmpty()) {
                val next = pending.minByOrNull { it.whenMs } ?: return
                now = next.whenMs
                pending.remove(next)
                next.runnable.run()
            }
        }

        data class Entry(
            val runnable: Runnable,
            val whenMs: Long,
        )
    }
}
