package io.github.trevarj.motd

import android.os.Build
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ThemeStateRetentionUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchingFromDynamicToFixedPalette_preservesStatefulContent() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        lateinit var selectTheme: (ColorThemePreset) -> Unit
        compose.setContent {
            var theme by remember { mutableStateOf(ColorThemePreset.DARK) }
            selectTheme = { theme = it }
            MotdTheme(themePreset = theme, dynamicColor = true) {
                var marker by remember { mutableStateOf("initial") }
                Button(
                    onClick = { marker = "retained" },
                    modifier = Modifier.testTag("theme_state_marker"),
                ) {
                    Text(marker)
                }
            }
        }

        compose.onNodeWithTag("theme_state_marker").performClick().assertTextEquals("retained")
        compose.runOnUiThread { selectTheme(ColorThemePreset.AYU_DARK) }
        compose.waitForIdle()

        compose.onNodeWithTag("theme_state_marker").assertTextEquals("retained")
    }
}
