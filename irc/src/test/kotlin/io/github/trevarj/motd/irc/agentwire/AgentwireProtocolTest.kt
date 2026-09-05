package io.github.trevarj.motd.irc.agentwire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

class AgentwireProtocolTest {
    @Test
    fun `trusted agent topic activates and preserves unknown options`() {
        val fixture = resource("agentwire/fixtures/trusted-topic.txt").trimEnd()
        val topic = parseAgentwireTopic(fixture.replace(" | ", ";ignored=value | "))

        assertEquals("trev", topic?.account)
        assertEquals("agentwire", topic?.agentAccount)
        assertEquals("codex", topic?.backend)
        assertEquals("value", topic?.options?.get("ignored"))
        assertEquals("trev+mobile", parseAgentwireTopic("agentwire:v1;account=trev%2Bmobile;agent=agentwire;backend=codex")?.account)
        assertNull(parseAgentwireTopic("Welcome agentwire:v1;account=trev;agent=agentwire;backend=codex"))
        assertNull(parseAgentwireTopic("agentwire:v1;account=trev"))
        assertNull(parseAgentwireTopic(resource("agentwire/fixtures/topic.txt").trimEnd()))
        assertNull(parseAgentwireTopic("agentwire:v1;account=trev;backend=codex"))
        assertEquals("trev", parseAgentwireTopic("agentwire:v1;account=trev;agent=trev;backend=codex")?.agentAccount)
        assertNull(parseAgentwireTopic("agentwire:v1;account=%ZZ;agent=agentwire;backend=codex"))
        assertNull(parseAgentwireTopic("agentwire:v1;account=%ff;agent=agentwire;backend=codex"))
    }

    @Test
    fun `a broken marker stays distinguishable from an ordinary topic`() {
        // An ordinary channel is not a failure and must never be reported as one.
        assertEquals(AgentwireTopicParse.NotMarked, parseAgentwireTopicResult("Welcome to the channel"))
        assertEquals(
            AgentwireTopicParse.NotMarked,
            parseAgentwireTopicResult("Welcome agentwire:v1;account=trev;agent=agentwire;backend=codex"),
        )
        // The upgrade that made `agent=` required names the one field to add, not all three.
        assertEquals(
            AgentwireTopicParse.Invalid(AgentwireTopicDefect.MISSING_FIELD, listOf("agent")),
            parseAgentwireTopicResult(resource("agentwire/fixtures/claude-topic.txt").trimEnd()),
        )
        assertEquals(
            AgentwireTopicParse.Invalid(AgentwireTopicDefect.MISSING_FIELD, listOf("agent", "backend")),
            parseAgentwireTopicResult("agentwire:v1;account=trev"),
        )
        assertEquals(
            AgentwireTopicParse.Invalid(AgentwireTopicDefect.INVALID_ENCODING, listOf("account")),
            parseAgentwireTopicResult("agentwire:v1;account=%ZZ;agent=agentwire;backend=codex"),
        )
        assertEquals(
            AgentwireTopicParse.Invalid(AgentwireTopicDefect.DUPLICATE_PARAMETER, listOf("account")),
            parseAgentwireTopicResult("agentwire:v1;account=trev;account=other;agent=agentwire;backend=codex"),
        )
        assertEquals(
            AgentwireTopicParse.Invalid(AgentwireTopicDefect.MALFORMED_PARAMETER),
            parseAgentwireTopicResult("agentwire:v1;account"),
        )
    }

    @Test
    fun `provisioned identities fold exactly as the bridge folds them`() {
        // "backend=Claude" would otherwise activate here while suspending the bridge.
        val topic = parseAgentwireTopic("agentwire:v1;account=Trev;agent=Agentwire;backend=Claude | Work")

        assertEquals("trev", topic?.account)
        assertEquals("agentwire", topic?.agentAccount)
        assertEquals("claude", topic?.backend)
        assertEquals("Work", topic?.title)
    }

    @Test
    fun `canonical fixtures validate and re-encode byte for byte`() {
        listOf(
            "hello.json",
            "prompt-action.json",
            "claude-hello.json",
            "pi-hello.json",
            "observed-status.json",
            "subagent-update.json",
        ).forEach { name ->
            val fixture = resource("agentwire/fixtures/$name").trimEnd()
            val envelope = (decodeAgentwireValue(fixture).getOrThrow() as AgentwireValue.Envelope).value
            assertEquals(fixture, encodeAgentwireEnvelope(envelope))
        }
    }

    @Test
    fun `observed session status carries a sid the channel is not bound to`() {
        val fixture = resource("agentwire/fixtures/observed-status.json").trimEnd()
        val envelope = (decodeAgentwireValue(fixture).getOrThrow() as AgentwireValue.Envelope).value

        assertEquals("session.status", envelope.kind)
        assertEquals("event", envelope.type)
        assertEquals("observed-example", envelope.sid)
        assertEquals(true, envelope.data?.get("busy")?.let { (it as JsonPrimitive).boolean })
        assertEquals("/home/example/project", envelope.data?.get("cwd")?.let { (it as JsonPrimitive).content })
        assertEquals(true, envelope.data?.get("tuiAttached")?.let { (it as JsonPrimitive).boolean })
        assertEquals(listOf("waiting"), (envelope.data?.get("flags") as JsonArray).map { (it as JsonPrimitive).content })
    }

    @Test
    fun `subagent update carries the bound session's full agent list`() {
        val fixture = resource("agentwire/fixtures/subagent-update.json").trimEnd()
        val envelope = (decodeAgentwireValue(fixture).getOrThrow() as AgentwireValue.Envelope).value

        assertEquals("subagent.updated", envelope.kind)
        assertEquals("event", envelope.type)
        assertEquals("session-example", envelope.sid)
        val agents = envelope.data?.get("agents") as JsonArray
        assertEquals(2, agents.size)
        val running = agents[0] as JsonObject
        assertEquals("agent-1", (running["id"] as JsonPrimitive).content)
        assertEquals("Explore", (running["type"] as JsonPrimitive).content)
        assertEquals("running", (running["status"] as JsonPrimitive).content)
        assertEquals(true, (running["isBackground"] as JsonPrimitive).boolean)
        val completed = agents[1] as JsonObject
        assertEquals("completed", (completed["status"] as JsonPrimitive).content)
        assertEquals(7, (completed["toolUses"] as JsonPrimitive).int)
        assertEquals(4200, (completed["durationMs"] as JsonPrimitive).int)
        assertEquals(1234, (completed["tokens"] as JsonPrimitive).int)
    }

    @Test
    fun `claude backend activates like any other and its hello decodes unchanged`() {
        val topic = parseAgentwireTopic(resource("agentwire/fixtures/trusted-claude-topic.txt").trimEnd())

        assertEquals("claude", topic?.backend)
        assertEquals("trev", topic?.account)
        assertEquals("agentwire", topic?.agentAccount)
        // The backend name is opaque to the client: only the topic's shape is validated.
        assertEquals("opencode", parseAgentwireTopic("agentwire:v1;account=trev;agent=a;backend=opencode")?.backend)
        assertEquals("claude", parseAgentwireTopic("agentwire:v1;backend=claude;account=trev;agent=a")?.backend)
        // Agentwire's own canonical claude topic still omits the agent account this client requires.
        assertNull(parseAgentwireTopic(resource("agentwire/fixtures/claude-topic.txt").trimEnd()))

        val hello = resource("agentwire/fixtures/claude-hello.json").trimEnd()
        val envelope = (decodeAgentwireValue(hello).getOrThrow() as AgentwireValue.Envelope).value
        assertEquals("agent.hello", envelope.kind)
        assertEquals("claude", envelope.data?.get("backend")?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `strict schema shape rejects unknown fields mismatched kinds and action without device`() {
        assertFailure("""{"v":1,"k":"agent.hello","t":"event","id":"11111111-1111-4111-8111-111111111111","at":1,"inst":"x","extra":1}""")
        assertFailure("""{"v":1,"k":"turn.prompt","t":"event","id":"11111111-1111-4111-8111-111111111111","at":1,"inst":"x"}""")
        assertFailure("""{"v":1,"k":"turn.prompt","t":"action","id":"11111111-1111-4111-8111-111111111111","at":1,"inst":"x"}""")
        assertFailure("""{"v":1,"k":"agent.hello","t":"event","id":"not-a-uuid","at":1,"inst":"x"}""")
        assertTrue(decodeAgentwireValue("""{"v":1.0,"k":"agent.hello","t":"event","id":"11111111-1111-4111-8111-111111111111","at":1.0,"inst":"x"}""").isSuccess)
        assertFailure("""{"v":1.5,"k":"agent.hello","t":"event","id":"11111111-1111-4111-8111-111111111111","at":1,"inst":"x"}""")
    }

    @Test
    fun `fragmented envelope reassembles out of order and tolerates exact duplicate`() {
        val envelope = event(content = incompressibleText())
        val fragments =
            fragmentAgentwireEnvelope(envelope).map { raw ->
                (decodeAgentwireValue(raw).getOrThrow() as AgentwireValue.Fragment).value
            }
        assertTrue(fragments.size > 1)
        val reassembler = AgentwireReassembler()
        assertNull(reassembler.accept(fragments.last()).getOrThrow())
        assertNull(reassembler.accept(fragments.last()).getOrThrow())
        var decoded: AgentwireEnvelope? = null
        fragments.dropLast(1).reversed().forEach { decoded = reassembler.accept(it).getOrThrow() ?: decoded }
        assertEquals(envelope.id, decoded?.id)
        assertEquals(envelope.data, decoded?.data)
    }

    @Test
    fun `compressed envelope uses one bounded fragment and reassembles`() {
        val envelope = event(content = "λ".repeat(8_000))
        val fragments = fragmentAgentwireEnvelope(envelope)
        assertEquals(1, fragments.size)
        val fragment = (decodeAgentwireValue(fragments.single()).getOrThrow() as AgentwireValue.Fragment).value
        assertEquals("zlib", fragment.encoding)
        assertEquals(envelope, AgentwireReassembler().accept(fragment).getOrThrow())
        assertTrue(AgentwireReassembler().accept(fragment.copy(bytes = 1)).isFailure)
    }

    @Test
    fun `long prompt has a bounded preview without losing structured context`() {
        val content = "sender: λ technical discussion\n".repeat(500)
        val prompt = event(content).copy(kind = "turn.prompt", type = "action", device = "mobile")
        val preview = requireNotNull(prompt.readablePreview())
        assertTrue(preview.toByteArray(Charsets.UTF_8).size <= 400)
        val reassembler = AgentwireReassembler()
        var received: AgentwireEnvelope? = null
        fragmentAgentwireEnvelope(prompt).forEach { raw ->
            received =
                when (val value = decodeAgentwireValue(raw).getOrThrow()) {
                    is AgentwireValue.Envelope -> value.value
                    is AgentwireValue.Fragment -> reassembler.accept(value.value).getOrThrow() ?: received
                }
        }
        assertEquals(content, (received?.data?.get("content") as? JsonPrimitive)?.content)
    }

    @Test
    fun `conflicting duplicate invalidates assembly`() {
        val fragments =
            fragmentAgentwireEnvelope(event(incompressibleText())).map {
                (decodeAgentwireValue(it).getOrThrow() as AgentwireValue.Fragment).value
            }
        val reassembler = AgentwireReassembler()
        reassembler.accept(fragments.first()).getOrThrow()
        assertTrue(reassembler.accept(fragments.first().copy(b64 = fragments.first().b64.reversed())).isFailure)
        // The invalidated ID may start a fresh assembly rather than inheriting poisoned bytes.
        assertNull(reassembler.accept(fragments.first()).getOrThrow())
    }

    @Test
    fun `required capability comparison ignores capability values`() {
        val caps =
            AGENTWIRE_REQUIRED_CAPS.mapTo(mutableSetOf()) { cap ->
                if (cap == "draft/multiline") "$cap=max-bytes=4096" else cap
            }
        assertTrue(agentwireMissingCaps(caps).isEmpty())
        assertEquals(setOf("account-tag"), agentwireMissingCaps(caps - "account-tag"))
    }

    @Test
    fun `copied canonical resources match upstream content hashes`() {
        assertEquals("5bd092183028c0596b1f032b425814a20105c7d153d4674f9bcae4069359c009", sha(resourceBytes("agentwire/agentwire-v1.schema.json")))
        assertEquals("966305fc7e635122c98c3b272666bf1e425c9d2a09f17fe12a7f2de959d52e7a", sha(resourceBytes("agentwire/fixtures/hello.json")))
        assertEquals("81d2de30a1eb81f391ce003ba529555a3ba0b9aaf6556f115d3e7b232933d42b", sha(resourceBytes("agentwire/fixtures/claude-hello.json")))
        assertEquals("2b37531bcf2780315f1735d7d35995580e2a32983fcbee07691a8d3fe0f11db4", sha(resourceBytes("agentwire/fixtures/pi-hello.json")))
        assertEquals("e354c350de1de04cf396aa595c90c46658db9e72b381fc54b7f7559ee7f38465", sha(resourceBytes("agentwire/fixtures/prompt-action.json")))
        assertEquals("24c6fed5b79f794b3f31f8ede35b199fbe6746bc24311b4a50d08aeacbb26c03", sha(resourceBytes("agentwire/fixtures/observed-status.json")))
        assertEquals("be00b7e2f858801ddb9fca1b2c33d090c20d45141b0e82c5ee1f55e2078e6b23", sha(resourceBytes("agentwire/fixtures/subagent-update.json")))
        assertEquals("058c5ad375bd89d3074349e4b01650525eb8138bd3d651d90236e72b4d4f964c", sha(resourceBytes("agentwire/fixtures/topic.txt")))
    }

    private fun event(content: String) =
        AgentwireEnvelope(
            kind = "assistant.completed",
            type = "event",
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            at = 1,
            instance = "instance",
            epoch = "epoch",
            sid = "session",
            data = buildJsonObject { put("content", content) },
        )

    private fun incompressibleText(): String = Base64.getEncoder().encodeToString(Random(0).nextBytes(10_000))

    private fun assertFailure(raw: String) {
        if (decodeAgentwireValue(raw).isSuccess) fail("expected validation failure: $raw")
    }

    private fun resource(path: String): String = resourceBytes(path).toString(Charsets.UTF_8)

    private fun resourceBytes(path: String): ByteArray = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)).readBytes()

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
