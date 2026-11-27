package io.github.trevarj.motd.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.KeywordHighlightRule
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeywordHighlightSettingsUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun addsEditsRejectsDuplicateAndDeletesRules() {
        val rules = mutableStateOf(listOf(KeywordHighlightRule("seed", false)))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MotdTheme(dynamicColor = false) {
                KeywordHighlightSettings(rules.value, onBack = {}) { old, replacement ->
                    val next = rules.value.toMutableList()
                    val index = if (old == null) next.size else next.indexOf(old)
                    if (old != null) next.removeAt(index)
                    if (replacement != null) next.add(index, replacement)
                    rules.value = next
                    true
                }
            }
        }

        val add = compose.onNodeWithTag("keyword_highlight_add")
        val phrase = compose.onNodeWithTag("keyword_highlight_phrase")
        val alerts = compose.onNodeWithTag("keyword_highlight_alerts")
        val save = compose.onNodeWithTag("keyword_highlight_save")
        add.performClick()
        phrase.performTextReplacement(" SEED ")
        save.assertIsNotEnabled()
        compose.onNodeWithText("A rule already matches this phrase.").assertExists()
        phrase.performTextReplacement("  Garden party  ")
        alerts
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOn()
        save.performClick()
        compose.runOnIdle { assertEquals(KeywordHighlightRule("Garden party", true), rules.value[1]) }

        compose
            .onNodeWithTag("keyword_highlight_edit_1")
            .assertContentDescriptionEquals("Edit keyword “Garden party”")
            .performScrollTo()
            .performClick()
        phrase.performTextReplacement("Orchid")
        alerts
            .performScrollTo()
            .assertIsOn()
            .performClick()
            .assertIsOff()
        restoration.emulateSavedInstanceStateRestore()
        alerts.performScrollTo().assertIsOff()
        save.performClick()
        compose.runOnIdle { assertEquals(KeywordHighlightRule("Orchid", false), rules.value[1]) }

        compose
            .onNodeWithTag("keyword_highlight_delete_1")
            .assertContentDescriptionEquals("Delete keyword “Orchid”")
            .performScrollTo()
            .performClick()
        compose.runOnIdle { assertEquals(listOf(KeywordHighlightRule("seed", false)), rules.value) }
    }
}
