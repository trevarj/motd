package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.identityRamp
import kotlin.math.min

private const val DEVICON_VIEWPORT = 600f

/** An offline, tintable monochrome channel mark: alias set plus pre-extracted SVG path data. */
internal interface ChannelMark {
    val markName: String
    val aliases: Set<String>
    val viewportWidth: Float
    val viewportHeight: Float
    val translateX: Float
    val translateY: Float
    val paths: List<Path>

    /** JVM-safe source validation; Android path conversion happens lazily when Compose renders it. */
    fun hasParseablePathData(): Boolean
}

/**
 * Generated catalog entry (see tools/gen-channel-devicons). Aliases come from devicon's own
 * name/altnames metadata plus a small fixup map, so channel tokens reverse-match the icon
 * catalog instead of a hand-curated list. [pathData] pairs an evenodd flag with path data;
 * honoring the source fill rule keeps counters (letter holes, rings) from filling solid.
 */
internal class CatalogChannelMark(
    override val markName: String,
    override val aliases: Set<String>,
    override val viewportWidth: Float,
    override val viewportHeight: Float,
    pathDataLoader: () -> List<Pair<Boolean, String>>,
) : ChannelMark {
    override val translateX = 0f
    override val translateY = 0f
    private val pathData by lazy(LazyThreadSafetyMode.NONE, pathDataLoader)

    override val paths: List<Path> by lazy(LazyThreadSafetyMode.NONE) {
        pathData.map { (evenOdd, data) ->
            PathParser().parsePathString(data).toPath().apply {
                if (evenOdd) fillType = PathFillType.EvenOdd
            }
        }
    }

    override fun hasParseablePathData(): Boolean =
        pathData.all { (_, data) ->
            PathParser().parsePathString(data)
            true
        }
}

/**
 * Hand-kept override marks the generated devicon catalog cannot supply: Guix uses its separately
 * credited official mark, and the rest keep their Devicons v1.1.0 art because devicon v2.16.0
 * has no plain variant for them (Emacs, Rust, GitHub, Tor, Zig, Clojure) or the plain variant
 * fails the generator's validation (Arch Linux).
 */
internal enum class ChannelDevicon(
    override val aliases: Set<String>,
    override val viewportWidth: Float = DEVICON_VIEWPORT,
    override val viewportHeight: Float = DEVICON_VIEWPORT,
    override val translateX: Float = 0f,
    override val translateY: Float = 0f,
    private val pathData: List<String>,
) : ChannelMark {
    GUIX(
        aliases = setOf("guix", "guixsd"),
        viewportWidth = 273f,
        viewportHeight = 170f,
        translateX = -1299.1761f,
        translateY = -41.051394f,
        pathData =
            listOf(
                """m 1557.9367,46.602435 c -2.6825,4.90263 -5.4059,8.89401 -8.1809,12.03897 -2.6825,3.05258 -5.6406,5.49427 -8.8781,7.34422 -3.1451,1.75757 -6.7135,3.02514 -10.691,3.76506 -3.885,0.64751 -8.3459,0.97629 -13.4334,0.9761 -3.7529,0 -7.1256,-0.1922 -10.0867,-0.55773 -0.012,0 -0.034,0 -0.047,0 -0.3754,-0.0273 -1.1508,-0.15334 -1.9523,-0.27891 -0.8442,-0.13218 -1.7071,-0.25955 -3.0678,-0.5113 -24.9421,-4.61489 -33.2141,12.7003 -35.0013,17.47744 -0.2797,0.74767 -0.4183,1.20845 -0.4183,1.20845 l -30.3066,84.505085 -18.593,31.60821 37.1395,0 c 15.2535,-33.96261 29.9183,-104.875515 45.5993,-111.139545 3.3988,0.47537 7.6118,0.69713 12.6897,0.69713 6.6601,0 12.6232,-0.79843 17.8957,-2.46349 5.2726,-1.66502 10.0151,-4.24757 14.2702,-7.76263 4.255,-3.60748 8.1051,-8.20868 11.5276,-13.75881 3.515,-5.54998 6.8486,-12.21732 9.9937,-19.98743 l -8.4597,-3.16082 z""",
                """m 1313.7642,46.602455 c 2.6826,4.90263 5.4059,8.89399 8.1809,12.03895 2.6825,3.05258 5.6407,5.49427 8.8782,7.34422 3.145,1.75757 6.7134,3.02514 10.6909,3.76506 3.8851,0.6477 8.3459,0.97629 13.4334,0.9761 3.7529,0 7.1256,-0.1922 10.0867,-0.55773 0.012,0 0.035,0 0.047,0 0.3754,-0.0269 1.1508,-0.15333 1.9523,-0.27891 0.8442,-0.13218 1.7071,-0.25955 3.0678,-0.5113 24.9421,-4.61489 33.2141,12.7003 35.0013,17.47744 0.2797,0.74767 0.4183,1.20845 0.4183,1.20845 l 30.3066,84.505085 18.593,31.60821 -37.1395,0 c -15.2535,-33.96261 -29.9183,-104.875515 -45.5993,-111.139545 -3.3988,0.47537 -7.6118,0.69713 -12.6897,0.69713 -6.6601,0 -12.6232,-0.79843 -17.8957,-2.46349 -5.2726,-1.66502 -10.0151,-4.24757 -14.2702,-7.76263 -4.255,-3.60748 -8.105,-8.20867 -11.5276,-13.75881 -3.515,-5.54993 -6.8486,-12.21728 -9.9937,-19.98743 l 8.4598,-3.1608 z""",
                """m 1435.8193,172.5566 -18.52,31.62143 37.1209,0""",
            ),
    ),

    ARCH_LINUX(
        aliases = setOf("arch", "archlinux"),
        pathData =
            listOf(
                """M300 0c-26.8 65.5-42.9 108.3-72.6 171.8a468 468 0 0 0 77 67.3c-39.1-16.1-65.8-32.2-85.7-49C180.7 269.5 121 382.6 0 600c95.1-54.9 168.8-88.7 237.5-101.7a174 174 0 0 1-4.5-40.7l.1-3c1.5-61 33.2-107.8 70.8-104.6s66.7 55.2 65.2 116a172 172 0 0 1-3.8 32.8c68 13.3 140.9 47 234.7 101.2-18.5-34-35-64.7-50.8-94-24.8-19.2-50.7-44.3-103.6-71.4a309 309 0 0 1 82.6 32.5C368 168.7 355 129 300 0""",
            ),
    ),

    CLOJURE(
        aliases = setOf("clojure", "clj", "clojurescript"),
        pathData =
            listOf(
                """M0 300C0 134.6 134.6 0 300 0s300 134.6 300 300-134.6 300-300 300A300.3 300.3 0 0 1 0 300m80-157.4A271.1 271.1 0 0 1 569.2 335c-7.5 29.4-20.7 49-36.6 62.4-24.3 20.4-56.8 27-86.8 27q-12.9 0-24.5-1.3A172.3 172.3 0 0 0 231 142.9q-4-2.9-8.3-5.3a146 146 0 0 0-68.2-16.6c-22.8-.2-49 5.6-74.5 21.6m294.2 269.8q2.7 1.3 10.7 3.7a143 143 0 0 0 58.7-115.6 143.6 143.6 0 0 0-188.3-136c29 33.1 43 80.6 56.6 132.4v.2l.1.2c.7 2.2 4.9 15.7 11.7 33.1 7.3 19 17.8 42.6 29.2 59.8 7.5 11.5 15.7 19.8 21.3 22.2m51 52.5c-19-2.4-34.7-5.2-48.4-10a172.2 172.2 0 0 1-190-283.8q-14.5-3.6-30-3.7c-50.5.4-103.8 28.4-126 104-1.8 9.1-1.7 16.5-1.6 24.4v4.7A271.1 271.1 0 0 0 522 456.4c-26.5 6.6-52 9.8-73.9 9.8q-12.2 0-23-1.3M156.8 300.5c.1-48.5 24.2-91.2 61-117.2q12.2 7 21.4 16.5c11.8 12 25 38.7 34.2 61.7l6.7 17.5a2475 2475 0 0 1-27.1 53.4c-18.2 34.7-30.6 58.7-36.7 84.1a143 143 0 0 1-59.4-116m123.4 24.3q4.6-10.6 8.7-19.4c17 60.5 27.8 96.5 47 121.1q4.4 5.5 9.5 10a144 144 0 0 1-94.6-1.5q-.5-6-.5-11.6.1-14.3 2.5-24.7a492 492 0 0 1 27.4-74""",
            ),
    ),

    EMACS(
        aliases = setOf("emacs", "emacsen", "spacemacs", "doomemacs"),
        pathData =
            listOf(
                """M300 600C136.2 600 3 465.4 3 300S136.2 0 300 0s297 134.6 297 300-133.2 300-297 300m0-583.5C145.3 16.5 19.5 143.7 19.5 300S145.3 583.5 300 583.5 580.5 456.3 580.5 300 454.7 16.5 300 16.5M200.8 505s24.4 1.8 55.8-1a903 903 0 0 0 97.3-13.8s44.1-9.5 67.7-18.2c24.7-9 38.1-16.8 44.1-27.7-.2-2.2 1.9-10.2-9.5-15-29-12.2-62.8-10-129.6-11.4-74-2.6-98.7-15-111.8-25-12.6-10-6.2-38 47.7-62.7 27.1-13.2 133.6-37.5 133.6-37.5-35.9-17.7-102.7-48.8-116.5-55.6-12-5.8-31.3-14.7-35.5-25.5-4.7-10.3 11.2-19.2 20.1-21.7a440 440 0 0 1 106-14c18.5-.3 21.5-1.5 21.5-1.5 25.6-4.2 42.4-21.7 35.4-49.4-6.3-28.2-39.5-44.9-71-39.1-29.7 5.4-101.3 26.2-101.3 26.2 88.5-.8 103.3.7 109.9 10 3.9 5.4-1.8 12.9-25.4 16.7-25.7 4.2-79 9.3-79 9.3-51.3 3-87.4 3.2-98.2 26.1-7 15 7.6 28.3 14 36.5 27 30.1 66.1 46.3 91.3 58.3 9.5 4.5 37.2 13 37.2 13-81.6-4.5-140.5 20.6-175 49.4-39 36.1-21.8 79.2 58.3 105.7 47.2 15.7 70.7 23 141.2 16.7 41.5-2.2 48-.9 48.4 2.5.6 4.8-46.1 16.7-58.9 20.4A3129 3129 0 0 1 200.7 505""",
            ),
    ),

    RUST(
        aliases = setOf("rust", "rustacean", "cargo"),
        pathData =
            listOf(
                """M595.9 292.6 570.7 277l-.7-7.3 21.6-20.2a8.6 8.6 0 0 0-2.8-14.4L561 224.7l-2.2-7.1 17.3-24a8.7 8.7 0 0 0-5.7-13.6l-29.1-4.8-3.5-6.5 12.2-27a8.6 8.6 0 0 0-8.2-12.2l-29.6 1q-2.2-2.9-4.7-5.7l6.8-28.8A8.7 8.7 0 0 0 504 85.6l-28.8 6.8-5.7-4.7 1-29.6a8.6 8.6 0 0 0-12.2-8.2l-27 12.3-6.5-3.6-4.7-29.1a8.7 8.7 0 0 0-13.7-5.7l-24 17.3-7-2.2L365 11.2a8.7 8.7 0 0 0-14.5-2.8L330.3 30l-7.3-.7-15.6-25.2a8.7 8.7 0 0 0-14.7 0L277 29.3l-7.4.7-20.2-21.6a8.7 8.7 0 0 0-14.4 2.8L224.7 39l-7.1 2.2-24-17.3a8.6 8.6 0 0 0-13.6 5.7l-4.8 29.1-6.5 3.6-27-12.3a8.6 8.6 0 0 0-12.2 8.2l1 29.6-5.7 4.7L96 85.6A8.7 8.7 0 0 0 85.6 96l6.8 28.8-4.7 5.7-29.6-1c-3 0-5.9 1.3-7.5 3.8a9 9 0 0 0-.7 8.5l12.3 26.9-3.6 6.5-29.1 4.8a8.7 8.7 0 0 0-5.7 13.6l17.3 24-2.2 7.1-27.7 10.4a8.7 8.7 0 0 0-2.8 14.4L30 269.7l-.7 7.3-25.2 15.6a8.7 8.7 0 0 0 0 14.8L29.3 323l.7 7.3-21.6 20.2a8.7 8.7 0 0 0 2.8 14.5L39 375.3l2.2 7.2-17.3 24a8.7 8.7 0 0 0 5.7 13.6l29.1 4.7 3.6 6.6L50 458.3a8.7 8.7 0 0 0 8.2 12.2l29.6-1q2.2 3 4.7 5.7L85.6 504A8.6 8.6 0 0 0 96 514.4l28.8-6.8 5.7 4.7-1 29.6a8.7 8.7 0 0 0 12.2 8.2l27-12.2 6.5 3.5 4.8 29.1a8.7 8.7 0 0 0 13.6 5.7l24-17.3 7.1 2.2 10.4 27.7a8.6 8.6 0 0 0 14.4 2.8l20.2-21.6 7.4.7 15.5 25.2a8.7 8.7 0 0 0 14.8 0l15.6-25.2 7.3-.7 20.2 21.6a8.6 8.6 0 0 0 14.5-2.9l10.3-27.6 7.2-2.2 24 17.3a8.7 8.7 0 0 0 13.6-5.7l4.7-29.1 6.6-3.5 26.9 12.2a8.7 8.7 0 0 0 12.2-8.2l-1-29.6q3-2.2 5.7-4.7l28.8 6.8a8.6 8.6 0 0 0 10.4-10.4l-6.8-28.8 4.7-5.7 29.6 1a8.6 8.6 0 0 0 8.2-12.2l-12.2-27 3.5-6.5 29.1-4.7a8.6 8.6 0 0 0 5.7-13.7l-17.3-24 2.2-7 27.7-10.4a8.6 8.6 0 0 0 2.8-14.5L570 330.3l.7-7.3 25.2-15.6a8.6 8.6 0 0 0 0-14.8m-168.6 209a17.9 17.9 0 1 1 7.6-34.9 17.9 17.9 0 0 1-7.6 34.9m-8.6-57.9a16 16 0 0 0-19.2 12.5l-9 41.7a219 219 0 0 1-183-.9l-8.9-41.7a16 16 0 0 0-19.2-12.5l-36.9 8q-10.2-10.6-19-22.5h179.2c2 0 3.4-.4 3.4-2.2v-63.4c0-1.9-1.3-2.2-3.4-2.2h-52.4v-40.2H307c5.2 0 27.7 1.4 34.9 30.2 2.2 8.8 7.2 37.6 10.6 46.8 3.3 10.4 17 31 31.7 31h89.3l3.3-.3q-9.3 12.6-20.4 23.8zm-247.9 57a17.8 17.8 0 1 1-7.5-34.8 17.8 17.8 0 0 1 7.5 34.8m-68-275.7a17.8 17.8 0 1 1-32.6 14.4 17.8 17.8 0 0 1 32.6-14.4m-20.9 49.5 38.4-17a16.3 16.3 0 0 0 8.2-21.5l-7.9-17.9h31.1v140.1H89a220 220 0 0 1-7.1-83.7M250.3 261v-41.3h74c3.9 0 27 4.4 27 21.7 0 14.4-17.7 19.6-32.3 19.6zm269 37.2q0 8.2-.5 16.3h-22.5q-3.3.1-3.2 3.6v10.4c0 24.3-13.7 29.6-25.7 31-11.5 1.2-24.2-4.8-25.7-11.8-6.8-38-18-46.1-35.8-60.2 22-14 45-34.6 45-62.3 0-29.8-20.5-48.6-34.4-57.9a98 98 0 0 0-47.1-15.5H136.6a219 219 0 0 1 122.7-69.2l27.5 28.8a16 16 0 0 0 23 .5l30.6-29.4c64.3 12 118.8 52 150.2 107l-21 47.4c-3.7 8.3 0 17.9 8.2 21.5l40.5 18q1 10.7 1 21.8M286.9 58a17.8 17.8 0 1 1 24.6 25.8A17.8 17.8 0 1 1 286.8 58m208.5 167.8a17.8 17.8 0 1 1 32.6 14.5 17.8 17.8 0 1 1-32.6-14.5""",
            ),
    ),

    GITHUB(
        aliases = setOf("github", "octocat", "gh"),
        pathData =
            listOf(
                """M115.7 288.2H65.2c-1.3 0-2.4 1-2.4 2.4v24.6c0 1.3 1.1 2.4 2.4 2.4h19.7v30.6s-4.4 1.6-16.7 1.6c-14.4 0-34.5-5.3-34.5-49.6s21-50.1 40.6-50.1c17 0 24.4 3 29.1 4.4 1.5.5 2.8-1 2.8-2.3l5.7-23.8q.1-1-1-1.9c-1.8-1.3-13.4-7.8-42.7-7.8C34.6 218.7 0 233 0 301.9s39.6 79.2 72.9 79.2c27.6 0 44.4-11.8 44.4-11.8.6-.4.7-1.3.7-1.8v-77c0-1.2-1-2.3-2.3-2.3m259.9-60.9c0-1.3-1-2.4-2.4-2.4h-28.4a2.3 2.3 0 0 0-2.3 2.4v55h-44.3v-55c0-1.3-1-2.4-2.4-2.4h-28.4a2.3 2.3 0 0 0-2.3 2.4V376c0 1.3 1 2.4 2.3 2.4h28.4c1.3 0 2.4-1 2.4-2.4v-63.6h44.3l-.1 63.6c0 1.3 1 2.4 2.4 2.4h28.4c1.3 0 2.4-1 2.4-2.4zm-206.4 19.9c0-10.2-8.2-18.5-18.3-18.5a18.4 18.4 0 0 0-18.4 18.5c0 10.2 8.3 18.5 18.4 18.5s18.3-8.3 18.3-18.5m-2.3 97.5v-68.6c0-1.3-1-2.4-2.3-2.4h-28.3c-1.3 0-2.5 1.4-2.5 2.7v98.3c0 2.9 1.8 3.7 4.1 3.7h25.5c2.8 0 3.5-1.3 3.5-3.8zm317.3-71H456c-1.3 0-2.4 1-2.4 2.4V349s-7.1 5.2-17.3 5.2-12.9-4.6-12.9-14.5V276c0-1.3-1-2.4-2.3-2.4h-28.6c-1.3 0-2.4 1-2.4 2.4v68.4c0 29.5 16.5 36.8 39.2 36.8a66 66 0 0 0 33.6-10.3s.7 5.4 1 6q.6 1.2 2 1.4l18.3-.1c1.3 0 2.3-1.1 2.3-2.4V276c0-1.3-1-2.4-2.3-2.4m65 80.6a35 35 0 0 1-16.5-4.7v-47.1s6.6-4 14.6-4.8c10.2-.9 20 2.2 20 26.4 0 25.6-4.5 30.6-18.2 30.2m11-83.8a53 53 0 0 0-26.8 7.2v-50.4c0-1.3-1.1-2.4-2.4-2.4h-28.5a2.3 2.3 0 0 0-2.3 2.4V376c0 1.3 1 2.4 2.3 2.4h19.8q1.4 0 2-1.3c.5-.8 1.2-6.8 1.2-6.8s11.7 11 33.8 11c25.9 0 40.7-13.1 40.7-59 0-45.8-23.7-51.8-39.7-51.8m-311.5 3h-21.3v-28.2q0-1.5-1.8-1.6h-29q-1.8 0-1.8 1.6v29l-15.5 3.9q-1.6.6-1.7 2.2v18.3c0 1.3 1 2.4 2.3 2.4h14.9v44c0 32.7 23 35.9 38.4 35.9 7 0 15.5-2.3 16.9-2.8q1.3-.6 1.3-2.1v-20.2q-.2-2.1-2.3-2.3c-1.2 0-4.4.5-7.7.5-10.5 0-14-4.9-14-11.2v-41.8h21.3c1.3 0 2.4-1 2.4-2.4v-22.9q-.2-2.1-2.4-2.3""",
            ),
    ),

    TOR(
        aliases = setOf("tor", "torproject", "onion"),
        pathData =
            listOf(
                """M449.5 28.5a211 211 0 0 0-111.7 78A536 536 0 0 1 412.8 0a311 311 0 0 0-100 109l16-63.8A366 366 0 0 0 281.3 179l28 12A828 828 0 0 1 449.5 28.5M470 355.2c-20.5-38-73-75.2-128.2-109.7-12.7-7.5-15.2-35.3-13.2-47.3l-12.8-6c-2.4 20-1 40.3 4 59.8 6 18.7 25 40 38 70q12 35.8 18 73c7.6 51.3 3 103.7-13.5 153a107 107 0 0 1-35.7 48l-6 4c104 3.2 220.7-115.8 149.4-244.8M335 475c-.8-17-4.4-34-10.4-50a310 310 0 0 0-18.8-36 156 156 0 0 1-4-51.7 175 175 0 0 0 8 48.5 277 277 0 0 1 17.3 32.5 225 225 0 0 1 14.7 56.7c.7 25.3-2.2 50.6-8.7 75q-3.7 13-10 25 12-17.4 17.5-38a311 311 0 0 0 9-112q-3.9-24-11.5-47.2c-11-30-26.8-55.7-28.8-61.7a405 405 0 0 1-9.7-84q1 39.6 11 78c2 6 20 34.5 32.5 64.5q8.5 20.9 12 43.2a268 268 0 0 1-13.3 129q-3.2 10.7-8.7 20.5a93 93 0 0 0 20-35.3 404 404 0 0 0 0-202.7c-10.5-32-35.3-60-41.3-79.2a192 192 0 0 1-2-59.8l-53.7-25c14 36 16.5 64 2 75-56.5 46.8-150 100-150 177.8 0 83 50 172.7 176.2 179.4a325 325 0 0 1-42.5-14 86 86 0 0 1-32.7-22.5l-3.3-3.5a234 234 0 0 1-50-91 68 68 0 0 1-2-38 167 167 0 0 1 82-100q13-6.8 25-15.2c11.8-7.2 19.3-38.2 27-63.2-4 19.2-8.5 56.5-26.5 70.5a421 421 0 0 1-23.2 16c-32 22-63.5 42.5-79.3 95.2a71 71 0 0 0 2 34.5 233 233 0 0 0 48.8 87.7s3.2 3.3 3.2 4a75 75 0 0 0 48.5 28.8c-7.2-4-13.2-8.8-18.5-12a109 109 0 0 1-50-90 93 93 0 0 1 54.5-84.7 90 90 0 0 0 42-59.5 78 78 0 0 1-39.7 62.5 100 100 0 0 0-52.5 79.2 142 142 0 0 0 47.2 85 102 102 0 0 0 39.8 18 72 72 0 0 1-12-12.5 125 125 0 0 1-11.3-25 75 75 0 0 1-6.7-25 90 90 0 0 1 16-59.3 71 71 0 0 0 22.5-33.7 72 72 0 0 1-18 37.7 75 75 0 0 0-14 56q2.2 12.1 7.2 23.3a100 100 0 0 0 12.8 25c4.5 5.2 6.5 9.2 13.7 12a162 162 0 0 0 12.3-58.8q2-22 0-44c-3.3-20-10-40-10-56 3 14.8 10.7 34.5 15.2 55.3q4.7 21.5 2.8 43.2c0 14-2 25-4 37.3a50 50 0 0 1-11.3 23.2c15-12.7 25-30.5 27.8-50q8.4-28.8 8-59""",
            ),
    ),

    ZIG(
        aliases = setOf("zig"),
        pathData =
            listOf(
                """M227.2 194 106.7 337.9h69L147 371.2H78.8L4.5 406l120.8-145.4H56l28.8-33.3H153zM69.7 227.3l-27.3 33.3h-9V338H47l-28.8 33.3H0v-144zm162 0v143.9h-71.1l28.7-33.3h9.1V262h-13.6l28.8-34.8zm309.3-.6a93 93 0 0 1 59 21.2l-19.7 22.7a61 61 0 0 0-39.2-15.1 44.7 44.7 0 1 0 .2 89.5c8.4 0 19-3.4 28.3-8.1v-20.2L524 295.5h72.7v59a101 101 0 0 1-57 18.2c-44 0-77.5-31.5-77.5-73.2 0-41.3 34-72.8 78.8-72.8m-147.2.6V250l-80.2 94h81.8v27.2H266.6V350l80.3-95.4H268v-27.3zm51.5 0v143.9H412v-144z""",
            ),
    ),

    ;

    override val markName: String = name.lowercase()

    override val paths: List<Path> by lazy(LazyThreadSafetyMode.NONE) {
        pathData.map { PathParser().parsePathString(it).toPath() }
    }

    override fun hasParseablePathData(): Boolean =
        pathData.all { path ->
            PathParser().parsePathString(path)
            true
        }
}

/** Overrides first, then the generated catalog; index keeps the tie-break stable. */
internal val allChannelMarks: List<ChannelMark> by lazy(LazyThreadSafetyMode.NONE) {
    ChannelDevicon.entries + ChannelDeviconCatalog.marks
}

private val channelMarkMatchCache =
    object : LinkedHashMap<String, ChannelMark?>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChannelMark?>?): Boolean = size > 512
    }

internal fun matchedChannelDevicon(channelName: String): ChannelMark? =
    synchronized(channelMarkMatchCache) {
        if (channelMarkMatchCache.containsKey(channelName)) return@synchronized channelMarkMatchCache[channelName]
        val tokens = channelTokens(channelName)
        var best: ChannelMark? = null
        var bestAliasLength = -1
        var bestIndex = -1
        allChannelMarks.forEachIndexed { index, mark ->
            mark.aliases.forEach { alias ->
                if (tokens.any { token -> tokenMatchesAlias(token, alias) } &&
                    (alias.length > bestAliasLength || alias.length == bestAliasLength && index > bestIndex)
                ) {
                    best = mark
                    bestAliasLength = alias.length
                    bestIndex = index
                }
            }
        }
        channelMarkMatchCache[channelName] = best
        best
    }

internal fun channelDeviconMatchCacheSize(): Int = synchronized(channelMarkMatchCache) { channelMarkMatchCache.size }

/**
 * Channel names retain compact technical identifiers such as `k8s` in addition to the ordinary
 * nick-token split (which still makes `doomEmacs` match Emacs).
 */
private fun channelTokens(channelName: String): List<String> {
    val compactTokens =
        buildList {
            val current = StringBuilder()

            fun flush() {
                if (current.isNotEmpty()) {
                    add(current.toString().lowercase())
                    current.clear()
                }
            }
            for (char in channelName) {
                if (char.isLetterOrDigit()) current.append(char) else flush()
            }
            flush()
        }
    return (nickTokens(channelName) + compactTokens).distinct()
}

/**
 * Contextual channel badge: named technical channels get a Devicons mark; ordinary channels keep
 * a quiet IRC sigil. Channels are rounded-square tiles ([MotdShapes.channelAvatar]) filled with
 * the vivid mid tone of the deterministic channel hue, so they read as places while people stay
 * circular sprites.
 */
@Composable
internal fun IrcChannelBadge(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val glyph = remember(name) { matchedChannelDevicon(name) }
    val background = LocalNickColors.current.avatar(name)
    // The identity fill is the tile, its highlight step is the border, and the mark takes
    // whichever of black/white reads on the tile.
    val border = remember(background) { identityRamp(background).highlight }
    val mark = onColorFor(background)

    Box(
        modifier =
            modifier
                .size(size)
                .clip(MotdShapes.channelAvatar)
                .background(background)
                .border(1.dp, border.copy(alpha = 0.90f), MotdShapes.channelAvatar),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph == null) {
            Text(
                text = "#",
                color = mark,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = (size.value * 0.48f).sp,
            )
        } else {
            Canvas(modifier = Modifier.size(size)) {
                drawChannelDevicon(glyph, mark)
            }
        }
    }
}

internal fun DrawScope.drawChannelDevicon(
    glyph: ChannelMark,
    color: Color,
) {
    val diameter = min(size.width, size.height)
    // The rounded-square tile inscribes more than the old circle did; 0.70 keeps corner clearance
    // at the 30% radius while letting the mark breathe.
    val available = diameter * 0.70f
    val scale = min(available / glyph.viewportWidth, available / glyph.viewportHeight)
    val left = (size.width - glyph.viewportWidth * scale) / 2f
    val top = (size.height - glyph.viewportHeight * scale) / 2f

    withTransform({
        // Source paths are in their native view boxes. Guix's public mark has a translated SVG
        // group; its per-glyph translation normalizes only that source without an SVG runtime.
        translate(left + glyph.translateX * scale, top + glyph.translateY * scale)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        glyph.paths.forEach { path -> drawPath(path, color) }
    }
}
