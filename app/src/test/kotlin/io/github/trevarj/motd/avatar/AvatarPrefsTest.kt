package io.github.trevarj.motd.avatar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AvatarPrefsTest {
    @Test fun self_setting_encoding_preserves_three_distinct_states() {
        assertEquals(SelfAvatarSetting.Unmanaged, decodeSelfSetting(null))
        assertEquals(SelfAvatarSetting.Unmanaged, decodeSelfSetting(encodeSelfSetting(SelfAvatarSetting.Unmanaged)))
        assertEquals(
            SelfAvatarSetting.ExplicitlyCleared,
            decodeSelfSetting(encodeSelfSetting(SelfAvatarSetting.ExplicitlyCleared)),
        )
        val set = SelfAvatarSetting.Set("https://example.com/{size}.png")
        assertEquals(set, decodeSelfSetting(encodeSelfSetting(set)))
    }

    @Test fun defaults_and_per_network_state_round_trip() = runTest {
        val prefs = AvatarPrefsImpl(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(AvatarConfig(), prefs.config.first())
        assertEquals(SelfAvatarSetting.Unmanaged, prefs.selfSetting(41).first())

        prefs.setShowSharedAvatars(false)
        prefs.setSelfSetting(41, SelfAvatarSetting.Set("https://example.com/{size}.png"))
        assertEquals(AvatarConfig(showSharedAvatars = false), prefs.config.first())
        assertEquals(
            SelfAvatarSetting.Set("https://example.com/{size}.png"),
            prefs.selfSetting(41).first(),
        )
        assertEquals(SelfAvatarSetting.Unmanaged, prefs.selfSetting(42).first())
    }
}
