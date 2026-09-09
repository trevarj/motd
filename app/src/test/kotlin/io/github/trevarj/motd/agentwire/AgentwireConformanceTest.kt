package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.AgentwireReassembler
import io.github.trevarj.motd.irc.agentwire.AgentwireValue
import io.github.trevarj.motd.irc.agentwire.decodeAgentwireValue
import io.github.trevarj.motd.irc.agentwire.encodeAgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.parseAgentwireTopic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-implementation conformance against the upstream Agentwire reference implementation.
 *
 * Every envelope in the corpus was built and encoded by agentwire's own `protocol.py`, and every
 * expected state was produced by agentwire's own reference renderer. Disagreeing with this file
 * means disagreeing with the bridge, which is the failure mode that is otherwise only discoverable
 * against a live deployment. Regenerate with `test/agentwire/generate-conformance.py`.
 */
class AgentwireConformanceTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `every corpus envelope decodes and re-encodes byte for byte`() {
        CORPORA.forEach { name ->
            corpus(name).forEach { step ->
                val tag = step.tag
                val decoded = decodeAgentwireValue(tag)
                assertTrue("${step.kind} in $name failed to decode: ${decoded.exceptionOrNull()}", decoded.isSuccess)
                val envelope = (decoded.getOrThrow() as AgentwireValue.Envelope).value
                assertEquals("${step.kind} in $name did not survive a re-encode", tag, encodeAgentwireEnvelope(envelope))
            }
        }
    }

    @Test
    fun `the reducer agrees with the reference renderer at every step`() {
        CORPORA.forEach { name ->
            val reducer = AgentwireReducer()
            var state = activeState()
            corpus(name).forEach { step ->
                val envelope = (decodeAgentwireValue(step.tag).getOrThrow() as AgentwireValue.Envelope).value
                state = reducer.reduce(state, envelope)
                val where = "${step.kind} in $name"
                assertEquals("$where: epoch", step.state.text("epoch"), state.epoch)
                assertEquals("$where: backend", step.state.text("backend") ?: TOPIC_BACKEND, state.backend)
                assertEquals("$where: sid", step.state.text("sid"), state.activeSid)
                assertEquals("$where: tid", step.state.text("tid"), state.currentTid)
                assertEquals("$where: busy", step.state.flag("busy") ?: false, state.busy)
                assertEquals("$where: settings", step.state.strings("settings"), state.settings)
                assertEquals("$where: queue", step.state.queueIds(), state.queue.map { it.iid })
                assertEquals("$where: open requests", step.state.requestIds(), state.requests.map { it.rid }.sorted())
            }
        }
    }

    @Test
    fun `replay corpus keeps historic activity isolated from the live turn`() {
        val reducer = AgentwireReducer()
        var state = activeState()
        val steps = corpus("replay-and-isolation")
        steps.take(4).forEach { step ->
            val envelope = (decodeAgentwireValue(step.tag).getOrThrow() as AgentwireValue.Envelope).value
            state = reducer.reduce(state, envelope)
        }
        // Production only accepts history correlated with the request it sent. The canonical
        // replay corpus deliberately isolates reducer behavior, so establish that context here
        // instead of weakening the live transport gate.
        state = state.copy(historySid = "sess-conformance", historyRequestId = "history-1")
        steps.drop(4).forEach { step ->
            val envelope = (decodeAgentwireValue(step.tag).getOrThrow() as AgentwireValue.Envelope).value
            state = reducer.reduce(state, envelope)
        }

        assertEquals("sess-conformance", state.activeSid)
        assertEquals("turn-1", state.currentTid)
        assertTrue(state.busy)
        val expected = steps.last().state
        assertEquals(expected.getValue("tools").jsonObject, toolProjection(state))
        assertEquals(
            expected.getValue("assistant").jsonArray.map { it.jsonObject.text("content") },
            state.timeline.filter { it.kind == "assistant.completed" }.map { it.body },
        )
    }

    @Test
    fun `an oversized envelope fragments and reassembles to the same bytes on both sides`() {
        val document = json.parseToJsonElement(resource("fragmented.json")).jsonObject
        val expected = document.getValue("envelope").jsonPrimitive.content
        val fragments = document.getValue("fragments").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("the corpus must exercise real fragmentation", fragments.size > 1)

        val reassembler = AgentwireReassembler()
        var reassembled: AgentwireEnvelope? = null
        fragments.forEach { raw ->
            val fragment = (decodeAgentwireValue(raw).getOrThrow() as AgentwireValue.Fragment).value
            reassembled = reassembler.accept(fragment).getOrThrow() ?: reassembled
        }

        assertNotNull("fragments produced by the bridge must reassemble here", reassembled)
        assertEquals(expected, encodeAgentwireEnvelope(reassembled!!))
    }

    @Test
    fun `the corpus topic activates and advertises only what Claude accepts`() {
        val document = json.parseToJsonElement(resource("claude-session.json")).jsonObject
        val topic = parseAgentwireTopic(document.getValue("topic").jsonPrimitive.content)
        assertEquals(TOPIC_BACKEND, topic?.backend)
        assertEquals("agentwire", topic?.agentAccount)

        val hello = (decodeAgentwireValue(corpus("claude-session").first().tag).getOrThrow() as AgentwireValue.Envelope).value
        val state = AgentwireReducer().reduce(activeState(), hello)
        // Claude takes its model from deployment configuration, so the settings UI must be driven
        // by this list rather than by the full safe-setting vocabulary.
        assertEquals(setOf("delivery"), state.supportedSettings)
        // The abridged upstream fixtures omit `actions`; without it every outbound action is
        // refused before it reaches the wire, so this is the assertion that matters most.
        assertTrue("turn.prompt must be advertised", "turn.prompt" in state.actions)
        assertTrue("request.respond must be advertised", "request.respond" in state.actions)
        assertTrue(
            "optional lifecycle actions are not advertised by this bridge",
            "session.fork" !in state.actions,
        )
    }

    @Test
    fun `imported corpus records its Agentwire commit and file hashes`() {
        val provenance = json.parseToJsonElement(upstreamResource()).jsonObject
        assertTrue(provenance.text("upstreamCommit")?.matches(Regex("[0-9a-f]{40}")) == true)
        val files = provenance.getValue("files").jsonObject
        files.forEach { (source, expected) ->
            val name = source.removePrefix("protocol/conformance/")
            assertEquals(source, expected.jsonPrimitive.content, sha(resourceBytes(name)))
        }
    }

    private fun activeState() =
        AgentwireUiState(
            gate = AgentwireGate.ACTIVE,
            channel = "#claude",
            controllerAccount = "trev",
            backendAccount = "agentwire",
            backend = TOPIC_BACKEND,
        )

    private data class Step(
        val kind: String,
        val tag: String,
        val state: JsonObject,
    )

    private fun corpus(name: String): List<Step> =
        json.parseToJsonElement(resource("$name.json")).jsonObject.getValue("steps").jsonArray.map { entry ->
            val step = entry.jsonObject
            Step(
                kind = step.getValue("kind").jsonPrimitive.content,
                tag = step.getValue("tag").jsonPrimitive.content,
                state = step.getValue("state").jsonObject,
            )
        }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.strings(key: String): Map<String, String> =
        (this[key] as? JsonObject)
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
            ?.toMap()
            .orEmpty()

    private fun JsonObject.queueIds(): List<String> = (this["queue"] as? JsonArray)?.mapNotNull { it.jsonObject.text("iid") }.orEmpty()

    private fun JsonObject.requestIds(): List<String> = (this["requests"] as? JsonObject)?.keys?.sorted().orEmpty()

    private fun toolProjection(state: AgentwireUiState): JsonObject {
        val tools = linkedMapOf<String, AgentwireTimelineItem>()
        (state.timeline + state.historyStaged)
            .filter { it.kind.startsWith("tool.") && it.backendItemId != null }
            .forEach { item ->
                val key =
                    json.encodeToString(
                        JsonArray(
                            listOf(
                                item.sid?.let(::JsonPrimitive) ?: JsonNull,
                                item.tid?.let(::JsonPrimitive) ?: JsonNull,
                                JsonPrimitive(requireNotNull(item.backendItemId)),
                            ),
                        ),
                    )
                val prior = tools[key]
                tools[key] =
                    when {
                        prior == null -> {
                            item
                        }

                        toolRank(prior.kind) > toolRank(item.kind) -> {
                            prior.copy(data = JsonObject(item.data + prior.data))
                        }

                        else -> {
                            item.copy(data = JsonObject(prior.data + item.data))
                        }
                    }
            }
        return JsonObject(
            tools.mapValues { (_, item) ->
                JsonObject(
                    item.data +
                        mapOf(
                            "sid" to (item.sid?.let(::JsonPrimitive) ?: JsonNull),
                            "tid" to (item.tid?.let(::JsonPrimitive) ?: JsonNull),
                            "iid" to JsonPrimitive(requireNotNull(item.backendItemId)),
                            "event" to JsonPrimitive(item.kind),
                        ),
                )
            },
        )
    }

    private fun toolRank(kind: String): Int =
        when (kind) {
            "tool.started" -> 0
            "tool.updated" -> 1
            "tool.completed" -> 2
            else -> -1
        }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("agentwire/conformance/$name")) {
            "missing conformance resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    private fun resourceBytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("agentwire/conformance/$name")) {
            "missing conformance resource $name"
        }.readBytes()

    private fun upstreamResource(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("agentwire/upstream.json")) {
            "missing Agentwire provenance"
        }.readBytes().toString(Charsets.UTF_8)

    private fun sha(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TOPIC_BACKEND = "claude"
        val CORPORA = listOf("claude-session", "queue-and-acks", "replay-and-isolation")
    }
}
