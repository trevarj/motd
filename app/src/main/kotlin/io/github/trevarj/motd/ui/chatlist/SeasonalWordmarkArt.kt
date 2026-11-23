package io.github.trevarj.motd.ui.chatlist

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser

internal fun seasonalPath(data: String): Path = PathParser().parsePathString(data).toPath()

internal class SeasonalPathMorph(
    vararg poses: String,
) {
    private val nodes: List<List<PathNode>>
    private val exact: List<Path>
    private val scratch = Path()

    init {
        val parsed = poses.map { PathParser().parsePathString(it) }
        nodes = parsed.map { it.toNodes() }
        exact = parsed.map { it.toPath() }
    }

    fun path(
        from: Int,
        to: Int = from,
        fraction: Float = 0f,
    ): Path {
        if (from == to || fraction <= 0f) return exact[from]
        if (fraction >= 1f) return exact[to]
        val start = nodes[from]
        val end = nodes[to]

        fun mix(
            a: Float,
            b: Float,
        ) = a + (b - a) * fraction

        scratch.rewind()
        for (i in start.indices) {
            val a = start[i]
            val b = end[i]
            when (a) {
                is PathNode.MoveTo -> {
                    b as PathNode.MoveTo
                    scratch.moveTo(mix(a.x, b.x), mix(a.y, b.y))
                }

                is PathNode.LineTo -> {
                    b as PathNode.LineTo
                    scratch.lineTo(mix(a.x, b.x), mix(a.y, b.y))
                }

                is PathNode.RelativeLineTo -> {
                    b as PathNode.RelativeLineTo
                    scratch.relativeLineTo(mix(a.dx, b.dx), mix(a.dy, b.dy))
                }

                is PathNode.QuadTo -> {
                    b as PathNode.QuadTo
                    scratch.quadraticTo(mix(a.x1, b.x1), mix(a.y1, b.y1), mix(a.x2, b.x2), mix(a.y2, b.y2))
                }

                is PathNode.CurveTo -> {
                    b as PathNode.CurveTo
                    scratch.cubicTo(mix(a.x1, b.x1), mix(a.y1, b.y1), mix(a.x2, b.x2), mix(a.y2, b.y2), mix(a.x3, b.x3), mix(a.y3, b.y3))
                }

                PathNode.Close -> {
                    scratch.close()
                }

                else -> {
                    error("Unsupported seasonal path command: $a")
                }
            }
        }
        return scratch
    }
}
