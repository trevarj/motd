package io.github.trevarj.motd.irc.agentwire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentwireDiagnosticsTest {
    @Test
    fun `bridge report retains bounded safe facts and instructions`() {
        val report = parseAgentwireDiagnosticReport(report())
        assertEquals(123L, report.generatedAt)
        assertEquals("backend.ready", report.checks.single().code)
        assertEquals("unknown", report.checks.single().status)
        assertEquals("Check connectivity.", report.checks.single().nextStep)
        assertEquals(
            "0",
            report.checks
                .single()
                .facts["sessionCount"]
                .toString(),
        )
    }

    @Test
    fun `arbitrary nested string negative or mistyped facts are rejected`() {
        listOf(
            "\"path\":\"private\"",
            "\"ready\":\"yes\"",
            "\"queueDepth\":true",
            "\"queueDepth\":-1",
            "\"queueDepth\":{\"nested\":1}",
            "\"queueDepth\":9223372036854775808",
            "\"ready\":null",
            "\"queueDepth\":null",
        ).forEach { facts ->
            assertTrue(facts, runCatching { parseAgentwireDiagnosticReport(report(facts = facts)) }.isFailure)
        }
    }

    @Test
    fun `report rejects unsupported headers and text while accepting integral JSON floats`() {
        val raw = report().toString()
        listOf(
            raw.replace("\"bridge\"", "\"doctor\""),
            raw.replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
            raw.replace("\"generatedAt\":123", "\"generatedAt\":-1"),
            raw.replace("\"generatedAt\":123", "\"generatedAt\":123.5"),
            raw.replace("\"unknown\"", "\"invalid\""),
            raw.replace("\"backend.ready\"", "\"backend.ready\\n\""),
            raw.replace("\"Check connectivity.\"", "null"),
        ).forEach { value ->
            assertTrue(value, runCatching { parseAgentwireDiagnosticReport(Json.parseToJsonElement(value).jsonObject) }.isFailure)
        }
        assertEquals(123L, parseAgentwireDiagnosticReport(Json.parseToJsonElement(raw.replace(":123", ":123.0")).jsonObject).generatedAt)
    }

    private fun report(facts: String = "\"ready\":false,\"sessionCount\":0"): JsonObject =
        Json
            .parseToJsonElement(
                """{"schemaVersion":1,"generatedAt":123,"source":"bridge","checks":[{"code":"backend.ready","status":"unknown","explanation":"No cached observation.","facts":{$facts},"nextStep":"Check connectivity."}]}""",
            ).jsonObject
}
