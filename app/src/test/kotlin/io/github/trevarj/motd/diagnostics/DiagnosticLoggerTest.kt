package io.github.trevarj.motd.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticLoggerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun cleanUp() {
        context.getSharedPreferences("diagnostic_logging", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "diagnostics").deleteRecursively()
    }

    @Test
    fun disabledLoggerDoesNotEvaluateOrExportEvents() = runTest {
        val logger = FileDiagnosticLogger(context, StandardTestDispatcher(testScheduler))
        var evaluated = false

        logger.record("messages", "received") {
            evaluated = true
            mapOf("buffer_id" to 1)
        }
        val output = ByteArrayOutputStream()
        logger.exportTo(output)

        assertFalse(evaluated)
        assertTrue(output.toString().contains("No diagnostic events captured"))
        assertFalse(output.toString().contains("event=received"))
    }

    @Test
    fun enablingStartsFreshOrderedTraceAndPersistsPreference() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = FileDiagnosticLogger(context, dispatcher)
        logger.setEnabled(true)
        logger.record("connections", "state_changed") { mapOf("state" to "Ready") }
        logger.record("history", "sync_finished") { mapOf("result" to "Complete") }

        val output = ByteArrayOutputStream()
        logger.exportTo(output)
        val exported = output.toString()

        assertTrue(exported.contains("component=diagnostics event=enabled"))
        assertTrue(exported.contains("component=connections event=state_changed state=Ready"))
        assertTrue(exported.contains("component=history event=sync_finished result=Complete"))
        assertTrue(exported.indexOf("state_changed") < exported.indexOf("sync_finished"))
        assertTrue(FileDiagnosticLogger(context, dispatcher).enabled.value)
    }

    @Test
    fun fingerprintsCorrelateWithoutExportingOriginalValue() {
        val first = diagnosticFingerprint("private message")
        val second = diagnosticFingerprint("private message")
        val other = diagnosticFingerprint("another message")

        assertEquals(first, second)
        assertNotEquals(first, other)
        assertFalse(first!!.contains("private"))
    }

    @Test
    fun journalRotationKeepsRecentEventsWithinTwoBoundedFiles() = runTest {
        val logger = FileDiagnosticLogger(context, StandardTestDispatcher(testScheduler)).apply {
            maxFileBytes = 600
        }
        logger.setEnabled(true)
        repeat(30) { index ->
            logger.record("test", "item_$index") { mapOf("padding" to "x".repeat(80)) }
        }

        val output = ByteArrayOutputStream()
        logger.exportTo(output)
        val exported = output.toString()

        assertTrue(exported.contains("event=item_29"))
        assertFalse(exported.contains("event=item_0 "))
        assertTrue(exported.toByteArray().size < 1_800)
    }

    @Test
    fun formatterOmitsSensitiveFieldsAndNormalizesLines() {
        val line = formatDiagnosticLine(
            timestamp = "2026-07-15T00:00:00Z",
            sequence = 4,
            component = "push\nreceiver",
            event = "done",
            fields = mapOf("error_fp" to "abc\ndef"),
        )
        assertFalse(line.contains('\n'))
        assertTrue(line.contains("component=push_receiver"))

        val redacted = formatDiagnosticLine(
            "now", 1, "messages", "received", mapOf("text" to "secret"),
        )
        assertTrue(redacted.contains("text=[omitted]"))
        assertFalse(redacted.contains("secret"))
    }
}
