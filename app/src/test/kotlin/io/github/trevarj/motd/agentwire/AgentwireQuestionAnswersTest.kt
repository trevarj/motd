package io.github.trevarj.motd.agentwire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The question sheet gates its submit button on a pure projection of the picked options and typed
 * text, so completeness and the free-form precedence rule are testable without Compose.
 */
class AgentwireQuestionAnswersTest {

    private fun question(
        id: String,
        options: List<String> = listOf("Alpha", "Beta"),
        multiple: Boolean = false,
        custom: Boolean = true,
    ) = AgentwireQuestion(
        id = id,
        header = "Header",
        prompt = "Prompt",
        options = options,
        multiple = multiple,
        custom = custom,
    )

    private fun values(answers: List<JsonArray>?) = answers?.map { array -> array.map { it.toString().trim('"') } }

    @Test
    fun `an unanswered question blocks the whole submission`() {
        val questions = listOf(question("1"), question("2"))
        val partial = agentwireQuestionAnswers(questions, mapOf("1" to setOf("Alpha")), emptyMap())

        assertNull(partial)
        assertNull(agentwireQuestionAnswers(questions, emptyMap(), emptyMap()))
        // Blank text is not an answer.
        assertNull(agentwireQuestionAnswers(listOf(question("1")), emptyMap(), mapOf("1" to "   ")))
    }

    @Test
    fun `a single-answer question carries its chosen option`() {
        val answers = agentwireQuestionAnswers(
            listOf(question("1"), question("2")),
            mapOf("1" to setOf("Alpha"), "2" to setOf("Beta")),
            emptyMap(),
        )

        assertEquals(listOf(listOf("Alpha"), listOf("Beta")), values(answers))
        assertEquals(JsonArray(listOf(JsonPrimitive("Alpha"))), answers?.first())
    }

    @Test
    fun `typed text replaces a selection on a single-answer question`() {
        val answers = agentwireQuestionAnswers(
            listOf(question("1")),
            mapOf("1" to setOf("Alpha")),
            mapOf("1" to " Gamma "),
        )

        assertEquals(listOf(listOf(" Gamma ")), values(answers))
    }

    @Test
    fun `a question with no options is answered by text alone`() {
        val answers = agentwireQuestionAnswers(
            listOf(question("1", options = emptyList(), custom = false)),
            emptyMap(),
            mapOf("1" to "free form"),
        )

        assertEquals(listOf(listOf("free form")), values(answers))
    }

    @Test
    fun `a multi-answer question keeps every pick in offered order plus any text`() {
        val questions = listOf(question("1", options = listOf("Alpha", "Beta", "Gamma"), multiple = true))

        assertEquals(
            listOf(listOf("Alpha", "Gamma")),
            values(agentwireQuestionAnswers(questions, mapOf("1" to setOf("Gamma", "Alpha")), emptyMap())),
        )
        assertEquals(
            listOf(listOf("Beta", "Delta")),
            values(agentwireQuestionAnswers(questions, mapOf("1" to setOf("Beta")), mapOf("1" to "Delta"))),
        )
    }
}
