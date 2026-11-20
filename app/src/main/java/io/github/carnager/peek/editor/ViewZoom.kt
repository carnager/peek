package io.github.carnager.peek.editor

import android.graphics.RectF
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The editor's view zoom, shared by all tools so switching tools keeps your place.
 * A point p of the fitted image lands on screen at p * scale + offset.
 */
class ViewZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    var ox by mutableFloatStateOf(0f)
        private set
    var oy by mutableFloatStateOf(0f)
        private set

    val isZoomed get() = scale > 1.01f

    fun reset() {
        scale = 1f; ox = 0f; oy = 0f
    }

    fun apply(base: RectF): RectF = RectF(
        base.left * scale + ox,
        base.top * scale + oy,
        base.right * scale + ox,
        base.bottom * scale + oy,
    )

    fun transform(base: RectF, boxW: Float, boxH: Float, centroid: Offset, zoom: Float, pan: Offset) {
        val s = (scale * zoom).coerceIn(1f, 8f)
        val z = s / scale
        var nx = (ox - centroid.x) * z + centroid.x + pan.x
        var ny = (oy - centroid.y) * z + centroid.y + pan.y
        // Keep the image on screen: centred while it fits, otherwise no gap at the edges.
        val w = base.width() * s
        val h = base.height() * s
        val l = base.left * s
        val t = base.top * s
        nx = if (w <= boxW) (boxW - w) / 2 - l else nx.coerceIn(boxW - w - l, -l)
        ny = if (h <= boxH) (boxH - h) / 2 - t else ny.coerceIn(boxH - h - t, -t)
        scale = s
        ox = if (s == 1f) 0f else nx
        oy = if (s == 1f) 0f else ny
    }
}

/**
 * Two-finger pinch and pan for the view. Runs before the tool's own gestures and only takes
 * multi-touch, so one finger still draws, drags handles or moves text.
 */
fun Modifier.viewZoom(zoom: ViewZoom?, enabled: () -> Boolean, base: () -> RectF, box: () -> Pair<Float, Float>): Modifier =
    if (zoom == null) this else pointerInput(zoom) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (ev.changes.count { it.pressed } >= 2 && enabled()) {
                    val (bw, bh) = box()
                    zoom.transform(base(), bw, bh, ev.calculateCentroid(), ev.calculateZoom(), ev.calculatePan())
                    ev.changes.forEach { it.consume() }
                }
            } while (ev.changes.any { it.pressed })
        }
    }
