package io.github.trevarj.motd.ui.components

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** A raw display list, never a capture of its own consumers. Parent fills uncovered sheet space. */
internal class SurfaceBackdropSource internal constructor(
    internal val raw: GraphicsLayer,
    val parent: SurfaceBackdropSource?,
) {
    internal var coordinates: LayoutCoordinates? = null
    internal var users by mutableIntStateOf(0)
    internal var revision by mutableIntStateOf(0)

    private val consumers = ArrayList<SurfaceBackdropConsumer>()
    private var producer: View? = null
    private var observation: SurfaceBackdropFrameObserver? = null

    private fun invalidateCommittedConsumers() {
        val producerRoot = producer?.rootView
        var index = 0
        while (index < consumers.size) {
            val consumer = consumers[index++]
            if (producerRoot != null && consumer.view.rootView !== producerRoot) {
                consumer.invalidateFromProducer()
            }
        }
    }

    internal fun bindProducer(view: View) {
        producer = view
        updateObservation()
    }

    internal fun unbindProducer(view: View) {
        if (producer === view) {
            producer = null
            updateObservation()
        }
    }

    internal fun acquire(consumer: SurfaceBackdropConsumer) {
        consumers.add(consumer)
        users = consumers.size
        updateObservation()
        parent?.acquire(consumer)
    }

    internal fun release(consumer: SurfaceBackdropConsumer) {
        consumers.remove(consumer)
        users = consumers.size
        updateObservation()
        parent?.release(consumer)
    }

    private fun updateObservation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val next = if (consumers.isNotEmpty()) producer else null
        if (observation?.view === next) return
        observation?.dispose()
        observation = next?.let { SurfaceBackdropFrameObserver(it, ::invalidateCommittedConsumers) }
    }
}

/**
 * Pre-draw registration joins this producer frame: ViewRootImpl captures commit callbacks before
 * dispatching OnDraw. Waiting for submission also covers independently updated child RenderNodes.
 */
@RequiresApi(Build.VERSION_CODES.S)
private class SurfaceBackdropFrameObserver(
    val view: View,
    private val onCommitted: () -> Unit,
) : ViewTreeObserver.OnPreDrawListener {
    private val initialObserver = view.viewTreeObserver
    private var active = true
    private val frameCommitted = Runnable { if (active) onCommitted() }

    init {
        initialObserver.addOnPreDrawListener(this)
    }

    override fun onPreDraw(): Boolean {
        if (active && view.isHardwareAccelerated) {
            val observer = view.viewTreeObserver
            if (observer.isAlive) {
                // A cancelled pre-draw must not accumulate duplicate pending callbacks.
                observer.unregisterFrameCommitCallback(frameCommitted)
                observer.registerFrameCommitCallback(frameCommitted)
            }
        }
        return true
    }

    fun dispose() {
        // Already captured callbacks cannot always be unregistered; make those harmless too.
        active = false
        removeFrom(initialObserver)
        val current = view.viewTreeObserver
        if (current !== initialObserver) removeFrom(current)
    }

    private fun removeFrom(observer: ViewTreeObserver) {
        if (!observer.isAlive) return
        observer.removeOnPreDrawListener(this)
        observer.unregisterFrameCommitCallback(frameCommitted)
    }
}

/** Keep this call and its host unconditional: changing effects must not recreate application UI. */
@Composable
internal fun rememberSurfaceBackdropSource(parent: SurfaceBackdropSource? = null): SurfaceBackdropSource {
    val raw = rememberGraphicsLayer()
    return remember(raw, parent) { SurfaceBackdropSource(raw, parent) }
}

/** Record only raw page/sheet content. Popup consumers belong outside this capture. */
internal fun Modifier.captureSurfaceBackdrop(source: SurfaceBackdropSource): Modifier =
    composed {
        // Resolve at application, not at factory creation outside a native dialog/popup.
        val view = LocalView.current
        DisposableEffect(source, view) {
            source.bindProducer(view)
            onDispose { source.unbindProducer(view) }
        }
        onGloballyPositioned { source.coordinates = it }
            .onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) {
                Snapshot.withoutReadObservation { source.revision++ }
            }
            // A consumer invalidation must not redraw its source and publish another revision.
            .graphicsLayer()
            .drawWithCache {
                onDrawWithContent {
                    if (source.users > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        source.raw.record { this@onDrawWithContent.drawContent() }
                        Snapshot.withoutReadObservation { source.revision++ }
                        drawLayer(source.raw)
                    } else {
                        drawContent()
                    }
                }
            }
    }

internal class SurfaceBackdropTarget {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
    var revision by mutableIntStateOf(0)
}

@Composable
internal fun rememberSurfaceBackdropTarget() = remember { SurfaceBackdropTarget() }

/** The first child of M3's sheet Column locates the native moving Surface, handle and insets. */
internal fun Modifier.surfaceBackdropTargetMarker(target: SurfaceBackdropTarget): Modifier =
    onGloballyPositioned { target.coordinates = it.parentLayoutCoordinates?.parentLayoutCoordinates }
        .onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) { target.revision++ }

/**
 * Replay screen-aligned raw sources into a bounded native effect; draw tint and foreground separately.
 * Native widget containerColor must be transparent when using this backing, retaining its own shape,
 * elevation, scrim, padding and animations. Zero opacity intentionally removes the entire backing.
 */
@Composable
internal fun Modifier.surfaceBackdrop(
    source: SurfaceBackdropSource,
    tint: Color,
    opacityPercent: Int,
    blurPercent: Int,
    effectsEnabled: Boolean = true,
    shape: Shape = RectangleShape,
    target: SurfaceBackdropTarget? = null,
): Modifier {
    val opacity = opacityPercent.coerceIn(0, 100)
    val blur = blurPercent.coerceIn(0, 100)
    val alpha = if (!effectsEnabled && opacity > 0) 1f else opacity / 100f
    val backing = tint.copy(alpha = alpha)
    if (opacity == 0) return this
    if (target == null && (!effectsEnabled || opacity == 100 || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
        return background(backing, shape)
    }
    return composed {
        val replaySource = effectsEnabled && opacity < 100 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val view = LocalView.current
        val consumerLease = remember(source, view) { SurfaceBackdropConsumer(view) }
        DisposableEffect(source, consumerLease, replaySource) {
            if (replaySource) source.acquire(consumerLease)
            onDispose {
                if (replaySource) source.release(consumerLease)
                consumerLease.cancelPendingInvalidation()
            }
        }
        val replay = if (replaySource) rememberGraphicsLayer() else null
        var coordinates by remember(source) { mutableStateOf<LayoutCoordinates?>(null) }
        var positionRevision by remember(source) { mutableIntStateOf(0) }
        then(BackdropDrawInvalidatorElement(consumerLease))
            .onGloballyPositioned { coordinates = it }
            .onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) { positionRevision++ }
            .drawWithCache {
                val targetRevision = target?.revision
                val targetCoordinates = target?.coordinates
                val backingSize = targetCoordinates?.size?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: size
                val radius = (32.dp * (if (effectsEnabled) blur / 100f else 0f)).toPx()
                // Three sigma supplies blur input outside the visible panel, without a window-sized effect.
                val padding = ceil(radius * 3f).toInt()
                val extent = IntSize(ceil(backingSize.width).toInt() + padding * 2, ceil(backingSize.height).toInt() + padding * 2)
                replay?.compositingStrategy = CompositingStrategy.Offscreen
                replay?.renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Clamp) else null
                val mask = Path().apply { addOutline(shape.createOutline(backingSize, layoutDirection, this@drawWithCache)) }
                val matrix = Matrix()
                val targetMatrix = Matrix()
                var recordedRevision = Long.MIN_VALUE
                var recordedPosition = targetRevision ?: Int.MIN_VALUE
                var recordedWindowRevision = Long.MIN_VALUE
                onDrawBehind {
                    val position = positionRevision
                    val revision = source.revisionStamp()
                    val windowRevision = consumerLease.windowRevision
                    val host = coordinates
                    val consumer = targetCoordinates ?: host
                    if (host?.isAttached == true && consumer?.isAttached == true) {
                        if (replay != null && (recordedRevision != revision || recordedPosition != position || recordedWindowRevision != windowRevision)) {
                            replay.record(size = extent) {
                                translate(padding.toFloat(), padding.toFloat()) { drawSources(source, consumer, matrix) }
                            }
                            recordedRevision = revision
                            recordedPosition = position
                            recordedWindowRevision = windowRevision
                        }
                        if (coordinateMatrix(consumer, host, targetMatrix)) {
                            withTransform({ transform(targetMatrix) }) {
                                clipPath(mask) {
                                    if (replay != null) translate(-padding.toFloat(), -padding.toFloat()) { drawLayer(replay) }
                                    drawRect(backing, size = backingSize)
                                }
                            }
                        }
                    }
                }
            }
    }
}

/** Each lease owns a draw-only nonce; producer frames must never publish global Snapshot state. */
internal class SurfaceBackdropConsumer(
    val view: View,
) {
    var windowRevision = 0L
        private set
    var invalidateDraw: (() -> Unit)? = null
    private var invalidationPosted = false
    private val invalidateAfterProducer =
        Runnable {
            invalidationPosted = false
            if (invalidateDraw != null) {
                windowRevision++
                invalidateDraw?.invoke()
            }
        }

    fun invalidateFromProducer() {
        if (invalidationPosted || invalidateDraw == null) return
        invalidationPosted = true
        // This notification follows producer frame commitment; coalesce downstream work onto
        // the consumer's next animation frame without waking the producer window.
        view.postOnAnimation(invalidateAfterProducer)
    }

    fun cancelPendingInvalidation() {
        if (invalidationPosted) view.removeCallbacks(invalidateAfterProducer)
        invalidationPosted = false
    }
}

private data class BackdropDrawInvalidatorElement(
    val consumer: SurfaceBackdropConsumer,
) : ModifierNodeElement<BackdropDrawInvalidatorNode>() {
    override fun create() = BackdropDrawInvalidatorNode(consumer)

    override fun update(node: BackdropDrawInvalidatorNode) {
        node.update(consumer)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "surfaceBackdropInvalidator"
    }
}

private class BackdropDrawInvalidatorNode(
    private var consumer: SurfaceBackdropConsumer,
) : Modifier.Node(),
    DrawModifierNode {
    override fun onAttach() {
        consumer.invalidateDraw = { if (isAttached) invalidateDraw() }
    }

    override fun onDetach() {
        consumer.cancelPendingInvalidation()
        consumer.invalidateDraw = null
    }

    fun update(next: SurfaceBackdropConsumer) {
        if (consumer === next) return
        consumer.cancelPendingInvalidation()
        consumer.invalidateDraw = null
        consumer = next
        if (isAttached) onAttach()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
    }
}

private fun SurfaceBackdropSource.revisionStamp(): Long = revision.toLong() + (parent?.revisionStamp() ?: 0L)

private fun DrawScope.drawSources(
    source: SurfaceBackdropSource,
    consumer: LayoutCoordinates,
    matrix: Matrix,
) {
    source.parent?.let { drawSources(it, consumer, matrix) }
    val coordinates = source.coordinates ?: return
    if (!coordinates.isAttached || source.raw.isReleased) return
    if (!coordinateMatrix(coordinates, consumer, matrix)) return
    withTransform({ transform(matrix) }) { drawLayer(source.raw) }
}

private fun coordinateMatrix(
    coordinates: LayoutCoordinates,
    consumer: LayoutCoordinates,
    matrix: Matrix,
): Boolean {
    val origin = consumer.screenToLocal(coordinates.localToScreen(Offset.Zero))
    val x = consumer.screenToLocal(coordinates.localToScreen(Offset(1f, 0f)))
    val y = consumer.screenToLocal(coordinates.localToScreen(Offset(0f, 1f)))
    if (!origin.x.isFinite() || !origin.y.isFinite() || !x.x.isFinite() || !x.y.isFinite() || !y.x.isFinite() || !y.y.isFinite()) return false
    // Three points retain ancestor scale/rotation as well as translations between Android windows.
    matrix.reset()
    matrix[0, 0] = x.x - origin.x
    matrix[0, 1] = x.y - origin.y
    matrix[1, 0] = y.x - origin.x
    matrix[1, 1] = y.y - origin.y
    matrix[3, 0] = origin.x
    matrix[3, 1] = origin.y
    return true
}
