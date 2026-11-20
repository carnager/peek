package io.github.carnager.peek.editor

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import io.github.carnager.peek.R
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.height
import kotlin.math.roundToInt
import io.github.carnager.peek.ui.PillChip
import io.github.carnager.peek.ui.ToolSheet
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draws [bitmap] fitted into the available space, then lets [overlay] paint on top in screen pixels.
 * [onRect] reports where the image ended up so gesture handlers can map touches into image space.
 */
@Composable
fun ImageStage(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    colorMatrix: android.graphics.ColorMatrix? = null,
    vignette: Float = 0f,
    /** Straightening angle in degrees; the image is zoomed so the frame stays filled. */
    angle: Float = 0f,
    /** Shared view zoom; null disables pinch-zoom on this stage. */
    zoom: ViewZoom? = null,
    /** Whether two fingers may zoom right now (the text tool wants them for its layers). */
    zoomEnabled: () -> Boolean = { true },
    gestures: Modifier = Modifier,
    onRect: (RectF) -> Unit = {},
    overlay: androidx.compose.ui.graphics.drawscope.DrawScope.(RectF) -> Unit = {},
) {
    val padPx = with(LocalDensity.current) { padding.toPx() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val base = remember(bitmap, boxW, boxH, padPx) {
            ImageOps.fitRect(bitmap.width, bitmap.height, boxW - 2 * padPx, boxH - 2 * padPx).apply { offset(padPx, padPx) }
        }
        val rect = if (zoom != null) zoom.apply(base) else base
        androidx.compose.runtime.SideEffect { onRect(rect) }
        val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
        val baseState by rememberUpdatedState(base)
        val enabledState by rememberUpdatedState(zoomEnabled)
        Canvas(
            Modifier.fillMaxSize()
                .clipToBounds()
                .viewZoom(zoom, { enabledState() }, { baseState }, { boxW to boxH })
                .then(gestures)
        ) {
            drawIntoCanvas { c ->
                paint.colorFilter = colorMatrix?.let { ColorMatrixColorFilter(it) }
                val nc = c.nativeCanvas
                if (angle != 0f) {
                    val sc = ImageOps.straightenScale(rect.width(), rect.height(), angle)
                    nc.save()
                    nc.clipRect(rect)
                    nc.rotate(angle, rect.centerX(), rect.centerY())
                    nc.scale(sc, sc, rect.centerX(), rect.centerY())
                    nc.drawBitmap(bitmap, null, rect, paint)
                    nc.restore()
                } else {
                    nc.drawBitmap(bitmap, null, rect, paint)
                }
                ImageOps.drawVignette(c.nativeCanvas, rect.left, rect.top, rect.width(), rect.height(), vignette)
            }
            overlay(rect)
        }
        if (zoom != null) {
            androidx.compose.animation.AnimatedVisibility(
                visible = zoom.isZoomed,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            ) {
                Text(
                    "%.1f×".format(zoom.scale),
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable { zoom.reset() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private enum class Handle { TL, T, TR, R, BR, B, BL, L, MOVE }

/** [label] is shown as is; [labelRes], when set, is a translated label used instead. */
private class AspectOption(val label: String, val ratio: Float?, val original: Boolean = false, @StringRes val labelRes: Int = 0)

private val ASPECTS = listOf(
    AspectOption("", null, labelRes = R.string.edit_aspect_free),
    AspectOption("", null, original = true, labelRes = R.string.edit_original),
    AspectOption("1:1", 1f),
    AspectOption("4:3", 4f / 3f),
    AspectOption("3:4", 3f / 4f),
    AspectOption("16:9", 16f / 9f),
    AspectOption("9:16", 9f / 16f),
    AspectOption("3:2", 3f / 2f),
    AspectOption("2:3", 2f / 3f),
)

/** Largest centred rectangle of pixel ratio [ratio] in an image of [w]x[h], in 0..1 coordinates. */
private fun fitAspect(ratio: Float, w: Int, h: Int): RectF {
    val imgRatio = w.toFloat() / h
    return if (imgRatio > ratio) {
        val nw = ratio / imgRatio
        RectF((1 - nw) / 2, 0f, (1 + nw) / 2, 1f)
    } else {
        val nh = imgRatio / ratio
        RectF(0f, (1 - nh) / 2, 1f, (1 + nh) / 2)
    }
}

private const val MIN = 0.04f

/**
 * Moves the grabbed [handle] of [r] to the touch point ([x], [y]) in 0..1 image space.
 * [rn] is the locked aspect in normalised units (width/height), or null for free cropping.
 */
private fun dragRect(r: RectF, handle: Handle, x: Float, y: Float, dx: Float, dy: Float, rn: Float?): RectF {
    if (handle == Handle.MOVE) {
        val ox = dx.coerceIn(-r.left, 1 - r.right)
        val oy = dy.coerceIn(-r.top, 1 - r.bottom)
        return RectF(r.left + ox, r.top + oy, r.right + ox, r.bottom + oy)
    }
    if (rn == null) {
        val out = RectF(r)
        if (handle in setOf(Handle.TL, Handle.L, Handle.BL)) out.left = x.coerceIn(0f, r.right - MIN)
        if (handle in setOf(Handle.TR, Handle.R, Handle.BR)) out.right = x.coerceIn(r.left + MIN, 1f)
        if (handle in setOf(Handle.TL, Handle.T, Handle.TR)) out.top = y.coerceIn(0f, r.bottom - MIN)
        if (handle in setOf(Handle.BL, Handle.B, Handle.BR)) out.bottom = y.coerceIn(r.top + MIN, 1f)
        return out
    }
    return when (handle) {
        Handle.TL, Handle.TR, Handle.BL, Handle.BR -> {
            val ax = if (handle == Handle.TL || handle == Handle.BL) r.right else r.left
            val ay = if (handle == Handle.TL || handle == Handle.TR) r.bottom else r.top
            val sx = if (handle == Handle.TL || handle == Handle.BL) -1f else 1f
            val sy = if (handle == Handle.TL || handle == Handle.TR) -1f else 1f
            var w = max(abs(x - ax), abs(y - ay) * rn)
            val maxW = if (sx < 0) ax else 1 - ax
            val maxH = if (sy < 0) ay else 1 - ay
            w = w.coerceAtMost(min(maxW, maxH * rn)).coerceAtLeast(MIN)
            val h = w / rn
            RectF(min(ax, ax + sx * w), min(ay, ay + sy * h), max(ax, ax + sx * w), max(ay, ay + sy * h))
        }
        Handle.L, Handle.R -> {
            val ax = if (handle == Handle.L) r.right else r.left
            val cy = r.centerY()
            var w = abs(x - ax)
            val maxW = if (handle == Handle.L) ax else 1 - ax
            w = w.coerceAtMost(min(maxW, 2 * min(cy, 1 - cy) * rn)).coerceAtLeast(MIN)
            val h = w / rn
            val l = if (handle == Handle.L) ax - w else ax
            RectF(l, cy - h / 2, l + w, cy + h / 2)
        }
        Handle.T, Handle.B -> {
            val ay = if (handle == Handle.T) r.bottom else r.top
            val cx = r.centerX()
            var h = abs(y - ay)
            val maxH = if (handle == Handle.T) ay else 1 - ay
            h = h.coerceAtMost(min(maxH, 2 * min(cx, 1 - cx) / rn)).coerceAtLeast(MIN)
            val w = h * rn
            val t = if (handle == Handle.T) ay - h else ay
            RectF(cx - w / 2, t, cx + w / 2, t + h)
        }
        Handle.MOVE -> r
    }
}

@Composable
fun CropTool(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val base = vm.preview ?: return
    var turns by remember { mutableIntStateOf(0) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    val shown = remember(base, turns, flipH, flipV) { ImageOps.transform(base, turns, flipH, flipV) }
    var aspectIndex by remember { mutableIntStateOf(0) }
    var crop by remember { mutableStateOf(RectF(0f, 0f, 1f, 1f)) }
    var dragging by remember { mutableStateOf(false) }
    var angle by remember { mutableFloatStateOf(0f) }
    var straightening by remember { mutableStateOf(false) }

    val originalRatio = base.width.toFloat() / base.height
    fun ratioPx(): Float? {
        val a = ASPECTS[aspectIndex]
        return when {
            a.original -> if (turns % 2 == 0) originalRatio else 1f / originalRatio
            else -> a.ratio
        }
    }
    fun resetCrop() {
        crop = ratioPx()?.let { fitAspect(it, shown.width, shown.height) } ?: RectF(0f, 0f, 1f, 1f)
    }

    Column(modifier) {
        var imgRect by remember { mutableStateOf(RectF()) }
        val rectState by rememberUpdatedState(imgRect)
        val handleSlop = with(LocalDensity.current) { 28.dp.toPx() }
        val stroke = with(LocalDensity.current) { 1.dp.toPx() }
        val corner = with(LocalDensity.current) { 18.dp.toPx() }
        val thick = with(LocalDensity.current) { 3.dp.toPx() }

        ImageStage(
            bitmap = shown,
            padding = 28.dp,
            zoom = vm.zoom,
            angle = angle,
            modifier = Modifier.weight(1f),
            onRect = { imgRect = it },
            gestures = Modifier.pointerInput(shown, aspectIndex) {
                var handle: Handle? = null
                var pos = Offset.Zero
                detectDragGestures(
                    onDragStart = { start ->
                        val ir = rectState
                        val c = crop
                        val l = ir.left + c.left * ir.width()
                        val r = ir.left + c.right * ir.width()
                        val t = ir.top + c.top * ir.height()
                        val b = ir.top + c.bottom * ir.height()
                        fun near(x: Float, y: Float) = hypot(start.x - x, start.y - y) < handleSlop
                        val inX = start.x in (l - handleSlop)..(r + handleSlop)
                        val inY = start.y in (t - handleSlop)..(b + handleSlop)
                        handle = when {
                            near(l, t) -> Handle.TL
                            near(r, t) -> Handle.TR
                            near(l, b) -> Handle.BL
                            near(r, b) -> Handle.BR
                            abs(start.x - l) < handleSlop && inY -> Handle.L
                            abs(start.x - r) < handleSlop && inY -> Handle.R
                            abs(start.y - t) < handleSlop && inX -> Handle.T
                            abs(start.y - b) < handleSlop && inX -> Handle.B
                            start.x in l..r && start.y in t..b -> Handle.MOVE
                            else -> null
                        }
                        pos = start
                        dragging = handle != null
                    },
                    onDragEnd = { dragging = false; handle = null },
                    onDragCancel = { dragging = false; handle = null },
                    onDrag = { change, delta ->
                        val h = handle ?: return@detectDragGestures
                        change.consume()
                        val ir = rectState
                        pos += delta
                        val x = ((pos.x - ir.left) / ir.width()).coerceIn(0f, 1f)
                        val y = ((pos.y - ir.top) / ir.height()).coerceIn(0f, 1f)
                        val rn = ratioPx()?.let { it * shown.height / shown.width }
                        crop = dragRect(crop, h, x, y, delta.x / ir.width(), delta.y / ir.height(), rn)
                    },
                )
            },
        ) { ir ->
            val c = crop
            val l = ir.left + c.left * ir.width()
            val t = ir.top + c.top * ir.height()
            val w = c.width() * ir.width()
            val h = c.height() * ir.height()
            clipRect(l, t, l + w, t + h, clipOp = ClipOp.Difference) {
                drawRect(Color.Black.copy(alpha = 0.6f), Offset(ir.left, ir.top), Size(ir.width(), ir.height()))
            }
            if (straightening) {
                for (i in 1..5) {
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(l + w * i / 6, t), Offset(l + w * i / 6, t + h), stroke)
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(l, t + h * i / 6), Offset(l + w, t + h * i / 6), stroke)
                }
            } else if (dragging) {
                for (i in 1..2) {
                    drawLine(Color.White.copy(alpha = 0.6f), Offset(l + w * i / 3, t), Offset(l + w * i / 3, t + h), stroke)
                    drawLine(Color.White.copy(alpha = 0.6f), Offset(l, t + h * i / 3), Offset(l + w, t + h * i / 3), stroke)
                }
            }
            drawRect(Color.White, Offset(l, t), Size(w, h), style = Stroke(stroke * 1.5f))
            val cl = min(corner, min(w, h) / 2)
            listOf(Offset(l, t) to Offset(1f, 1f), Offset(l + w, t) to Offset(-1f, 1f), Offset(l, t + h) to Offset(1f, -1f), Offset(l + w, t + h) to Offset(-1f, -1f))
                .forEach { (p, d) ->
                    drawLine(Color.White, p, Offset(p.x + d.x * cl, p.y), thick)
                    drawLine(Color.White, p, Offset(p.x, p.y + d.y * cl), thick)
                }
        }

        ToolSheet {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ASPECTS.forEachIndexed { i, a ->
                PillChip(selected = i == aspectIndex, label = if (a.labelRes != 0) stringResource(a.labelRes) else a.label) { aspectIndex = i; resetCrop() }
            }
        }
        AngleDial(
            value = angle,
            onChange = { angle = it },
            onActive = { straightening = it },
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { vm.zoom.reset(); turns = (turns + 3) % 4; crop = RectF(0f, 0f, 1f, 1f); aspectIndex = 0 }) {
                Icon(Icons.AutoMirrored.Rounded.RotateLeft, stringResource(R.string.edit_rotate_left))
            }
            IconButton(onClick = { vm.zoom.reset(); turns = (turns + 1) % 4; crop = RectF(0f, 0f, 1f, 1f); aspectIndex = 0 }) {
                Icon(Icons.AutoMirrored.Rounded.RotateRight, stringResource(R.string.edit_rotate_right))
            }
            IconButton(onClick = { flipH = !flipH; crop = RectF(1 - crop.right, crop.top, 1 - crop.left, crop.bottom) }) {
                Icon(Icons.Rounded.Flip, stringResource(R.string.edit_flip_horizontal))
            }
            IconButton(onClick = { flipV = !flipV; crop = RectF(crop.left, 1 - crop.bottom, crop.right, 1 - crop.top) }) {
                Icon(Icons.Rounded.Flip, stringResource(R.string.edit_flip_vertical), Modifier.rotate(90f))
            }
            IconButton(onClick = { turns = 0; flipH = false; flipV = false; angle = 0f; aspectIndex = 0; crop = RectF(0f, 0f, 1f, 1f) }) {
                Icon(Icons.Rounded.RestartAlt, stringResource(R.string.edit_reset))
            }
        }
        ToolActions(
            title = stringResource(R.string.edit_tool_crop),
            onCancel = vm::closeTool,
            onApply = {
                val c = RectF(crop)
                val t = turns; val fh = flipH; val fv = flipV; val a = angle
                vm.commit { ImageOps.crop(ImageOps.straighten(ImageOps.transform(it, t, fh, fv), a), c) }
            },
        )
        }
    }
}

/**
 * A horizontal ruler you drag to straighten the image, ±45°. Ticks click under the finger and it
 * snaps back to exactly 0° when you pass near it.
 */
@Composable
fun AngleDial(value: Float, onChange: (Float) -> Unit, onActive: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = LocalDensity.current
    val pxPerDegree = with(density) { 9.dp.toPx() }
    val current by rememberUpdatedState(value)
    val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val onSurface = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (value == 0f) "0°" else "%+.1f°".format(value),
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            color = if (value == 0f) onSurface.copy(alpha = 0.7f) else primary,
            modifier = Modifier.clickable { onChange(0f) },
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .pointerInput(Unit) {
                    var raw = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { raw = current; onActive(true) },
                        onDragEnd = { onActive(false) },
                        onDragCancel = { onActive(false) },
                    ) { change, dx ->
                        change.consume()
                        val before = current
                        raw = (raw - dx / pxPerDegree).coerceIn(-45f, 45f)
                        // Sticky zero: within half a degree it locks to straight.
                        val next = if (abs(raw) < 0.5f) 0f else (raw * 10).roundToInt() / 10f
                        if (next != before) {
                            if (next == 0f) {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                            } else if (next.toInt() != before.toInt() || (next < 0) != (before < 0)) {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentFrequentTick)
                            }
                            onChange(next)
                        }
                    }
                },
        ) {
            val cx = size.width / 2
            val first = kotlin.math.floor(current - cx / pxPerDegree).toInt().coerceAtLeast(-45)
            val last = kotlin.math.ceil(current + cx / pxPerDegree).toInt().coerceAtMost(45)
            for (d in first..last) {
                val x = cx + (d - current) * pxPerDegree
                val major = d % 5 == 0
                val fade = (1f - abs(x - cx) / cx).coerceIn(0f, 1f)
                val len = if (d == 0) size.height * 0.75f else if (major) size.height * 0.5f else size.height * 0.28f
                drawLine(
                    onSurface.copy(alpha = 0.15f + 0.6f * fade),
                    Offset(x, (size.height - len) / 2),
                    Offset(x, (size.height + len) / 2),
                    strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                )
            }
            // Centre marker.
            drawLine(primary, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
