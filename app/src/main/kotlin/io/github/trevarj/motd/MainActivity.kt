package io.github.trevarj.motd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.ui.nav.MotdNavGraph
import io.github.trevarj.motd.ui.theme.MotdTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MotdTheme {
                MotdNavGraph()
            }
        }
    }
}
