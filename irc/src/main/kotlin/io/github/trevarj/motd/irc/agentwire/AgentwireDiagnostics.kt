package io.github.trevarj.motd.irc.agentwire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal

data class AgentwireDiagnosticCheck(
    val code: String,
    val status: String,
    val explanation: String,
    val facts: JsonObject,
    val nextStep: String? = null,
)

data class AgentwireDiagnosticReport(
    val generatedAt: Long,
    val checks: List<AgentwireDiagnosticCheck>,
)

private val diagnosticBooleanFacts =
    setOf("ready", "closed", "connected", "authenticated", "capabilitiesReady", "active", "bound", "busy", "available", "private", "valid")
private val diagnosticCountFacts = setOf("pendingRequests", "sessionCount", "socketCount", "queueDepth", "oldestQueuedMs")
private val diagnosticStatuses = setOf("ok", "warning", "error", "unknown")
private val diagnosticCode = Regex("[a-z][a-z0-9.]{0,79}")
private val diagnosticMaximum = BigDecimal.valueOf(Long.MAX_VALUE)

/** Validates a bridge report before any remote text reaches presentation. */
fun parseAgentwireDiagnosticReport(data: JsonObject): AgentwireDiagnosticReport {
    require(data.keys == setOf("schemaVersion", "generatedAt", "source", "checks")) { "invalid diagnostic report fields" }
    require(data.diagnosticInteger("schemaVersion") == 1L) { "unsupported diagnostics schema version" }
    require(data.diagnosticString("source") == "bridge") { "diagnostic source must be bridge" }
    val generatedAt = data.diagnosticInteger("generatedAt")
    require(generatedAt >= 0) { "invalid diagnostic timestamp" }
    val checks = data["checks"] as? JsonArray ?: error("diagnostic checks must be an array")
    require(checks.size in 1..64) { "invalid diagnostic check count" }
    return AgentwireDiagnosticReport(
        generatedAt,
        checks.map { raw ->
            val check = raw as? JsonObject ?: error("diagnostic check must be an object")
            require(check.keys.containsAll(setOf("code", "status", "explanation", "facts"))) { "missing diagnostic check fields" }
            require(check.keys.all { it in setOf("code", "status", "explanation", "facts", "nextStep") }) { "invalid diagnostic check fields" }
            val code = check.diagnosticString("code")
            require(diagnosticCode.matches(code)) { "invalid diagnostic check code" }
            val status = check.diagnosticString("status")
            require(status in diagnosticStatuses) { "invalid diagnostic status" }
            val explanation = check.diagnosticText("explanation")
            val nextStep = if ("nextStep" in check) check.diagnosticText("nextStep") else null
            val facts = check["facts"] as? JsonObject ?: error("diagnostic facts must be an object")
            facts.forEach { (key, rawFact) ->
                val fact = rawFact as? JsonPrimitive ?: error("diagnostic fact must be scalar")
                require(!fact.isString) { "diagnostic facts cannot contain strings" }
                when (key) {
                    in diagnosticBooleanFacts -> {
                        require(fact.booleanOrNull != null) { "diagnostic fact must be boolean" }
                    }

                    in diagnosticCountFacts -> {
                        val number = fact.content.toBigDecimalOrNull() ?: error("diagnostic fact must be numeric")
                        require(number >= BigDecimal.ZERO && number <= diagnosticMaximum) { "diagnostic number is out of range" }
                    }

                    else -> {
                        error("diagnostic fact is not allowlisted")
                    }
                }
            }
            AgentwireDiagnosticCheck(code, status, explanation, facts, nextStep)
        },
    )
}

private fun JsonObject.diagnosticString(key: String): String {
    val value = get(key) as? JsonPrimitive ?: error("$key must be a string")
    require(value.isString) { "$key must be a string" }
    return value.content
}

private fun JsonObject.diagnosticText(key: String): String {
    val value = diagnosticString(key)
    require(value.codePointCount(0, value.length) in 1..300) { "diagnostic text must be bounded" }
    return value
}

private fun JsonObject.diagnosticInteger(key: String): Long {
    val value = get(key) as? JsonPrimitive ?: error("$key must be an integer")
    require(!value.isString) { "$key must be an integer" }
    return value.content.toBigDecimalOrNull()?.let { runCatching { it.longValueExact() }.getOrNull() }
        ?: error("$key must be an integer")
}
