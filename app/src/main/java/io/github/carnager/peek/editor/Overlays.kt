package io.github.carnager.peek.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class TextStyleMode(val label: String) { PLAIN("Normal"), SHADOW("Schatten"), OUTLINE("Kontur"), BOX("Box"), SOFT_BOX("Box hell") }

enum class TextAlign(val layout: Layout.Alignment) {
    LEFT(Layout.Alignment.ALIGN_NORMAL),
    CENTER(Layout.Alignment.ALIGN_CENTER),
    RIGHT(Layout.Alignment.ALIGN_OPPOSITE),
}

/**
 * A movable text block. Position and size are relative to the image (centre in 0..1, text size as a
 * fraction of the shorter image side), so the same layer renders identically on the preview and the
 * full-resolution bitmap.
 */
data class TextLayer(
    val id: Long,
    val text: String,
    val fontId: String,
    val color: Int = Color.WHITE,
    val style: TextStyleMode = TextStyleMode.SHADOW,
    val align: TextAlign = TextAlign.CENTER,
    val bold: Boolean = false,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val size: Float = 0.11f,
    val rotation: Float = 0f,
)

/** One freehand line, points relative to the image (0..1). */
data class Stroke(
    val points: List<Pair<Float, Float>>,
    val color: Int,
    /** Line width as a fraction of the shorter image side. */
    val width: Float,
    val marker: Boolean,
    /** A straight arrow from the first to the last point. */
    val arrow: Boolean = false,
)

object OverlayRenderer {

    private fun contrastOn(color: Int): Int {
        val l = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return if (l > 150) Color.BLACK else Color.WHITE
    }

    private fun layoutFor(context: Context, layer: TextLayer, textSize: Float): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Fonts.typeface(context, layer.fontId)
            this.textSize = textSize
            isFakeBoldText = layer.bold
        }
        val text = layer.text.ifEmpty { " " }
        val width = text.split('\n').maxOf { ceil(Layout.getDesiredWidth(it, paint)).toInt() } + 2
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(width, 1))
            .setAlignment(layer.align.layout)
            .setIncludePad(true)
            .build()
    }

    private fun padding(textSize: Float) = textSize * 0.28f

    /** Size of the layer's box (including background padding) in pixels for an image of [w]x[h]. */
    fun measure(context: Context, layer: TextLayer, w: Float, h: Float): Pair<Float, Float> {
        val ts = layer.size * min(w, h)
        val l = layoutFor(context, layer, ts)
        val p = padding(ts)
        return (l.width + 2 * p) to (l.height + 2 * p)
    }

    /**
     * Draws [layer] onto [canvas], whose image area starts at ([ox], [oy]) and is [w]x[h] pixels.
     */
    fun drawText(context: Context, canvas: Canvas, layer: TextLayer, ox: Float, oy: Float, w: Float, h: Float, selected: Boolean = false, density: Float = 1f) {
        val ts = layer.size * min(w, h)
        val layout = layoutFor(context, layer, ts)
        val paint = layout.paint
        val pad = padding(ts)
        val bw = layout.width + 2 * pad
        val bh = layout.height + 2 * pad

        canvas.save()
        canvas.translate(ox + layer.cx * w, oy + layer.cy * h)
        canvas.rotate(layer.rotation)
        canvas.translate(-bw / 2, -bh / 2)

        val textColor: Int
        when (layer.style) {
            TextStyleMode.BOX, TextStyleMode.SOFT_BOX -> {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = layer.color
                    if (layer.style == TextStyleMode.SOFT_BOX) alpha = 150
                }
                canvas.drawRoundRect(RectF(0f, 0f, bw, bh), ts * 0.22f, ts * 0.22f, bg)
                textColor = contrastOn(layer.color)
            }
            else -> textColor = layer.color
        }

        canvas.translate(pad, pad)
        when (layer.style) {
            TextStyleMode.SHADOW -> {
                paint.color = textColor
                paint.setShadowLayer(ts * 0.12f, ts * 0.03f, ts * 0.04f, Color.argb(170, 0, 0, 0))
                layout.draw(canvas)
                paint.clearShadowLayer()
            }
            TextStyleMode.OUTLINE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = ts * 0.12f
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = contrastOn(layer.color)
                layout.draw(canvas)
                paint.style = Paint.Style.FILL
                paint.color = textColor
                layout.draw(canvas)
            }
            else -> {
                paint.color = textColor
                layout.draw(canvas)
            }
        }
        canvas.translate(-pad, -pad)

        if (selected) {
            val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
                color = Color.WHITE
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
                setShadowLayer(2 * density, 0f, 0f, Color.BLACK)
            }
            val m = 4 * density
            canvas.drawRoundRect(RectF(-m, -m, bw + m, bh + m), 6 * density, 6 * density, frame)
        }
        canvas.restore()
    }

    /** True when the point (image pixels, relative to the image origin) is inside the rotated layer box. */
    fun hit(context: Context, layer: TextLayer, px: Float, py: Float, w: Float, h: Float, slop: Float): Boolean {
        val (bw, bh) = measure(context, layer, w, h)
        val dx = px - layer.cx * w
        val dy = py - layer.cy * h
        val a = Math.toRadians(-layer.rotation.toDouble())
        val lx = dx * cos(a) - dy * sin(a)
        val ly = dx * sin(a) + dy * cos(a)
        return kotlin.math.abs(lx) <= bw / 2 + slop && kotlin.math.abs(ly) <= bh / 2 + slop
    }

    fun drawStroke(canvas: Canvas, stroke: Stroke, ox: Float, oy: Float, w: Float, h: Float) {
        if (stroke.points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.color
            style = Paint.Style.STROKE
            strokeWidth = stroke.width * min(w, h)
            strokeJoin = Paint.Join.ROUND
            strokeCap = if (stroke.marker) Paint.Cap.SQUARE else Paint.Cap.ROUND
            if (stroke.marker) alpha = 110
        }
        val pts = stroke.points
        if (stroke.arrow) {
            drawArrow(canvas, paint, ox + pts.first().first * w, oy + pts.first().second * h, ox + pts.last().first * w, oy + pts.last().second * h)
            return
        }
        if (pts.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(ox + pts[0].first * w, oy + pts[0].second * h, paint.strokeWidth / 2, paint)
            return
        }
        val path = Path()
        path.moveTo(ox + pts[0].first * w, oy + pts[0].second * h)
        // Quadratic smoothing through the midpoints keeps fast finger strokes from looking jagged.
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]
            val (x1, y1) = pts[i]
            path.quadTo(ox + x0 * w, oy + y0 * h, ox + (x0 + x1) / 2 * w, oy + (y0 + y1) / 2 * h)
        }
        val last = pts.last()
        path.lineTo(ox + last.first * w, oy + last.second * h)
        canvas.drawPath(path, paint)
    }

    private fun drawArrow(canvas: Canvas, paint: Paint, x0: Float, y0: Float, x1: Float, y1: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val sw = paint.strokeWidth
        // Head scales with the line, but never longer than half the arrow.
        val head = minOf(sw * 4.2f, len * 0.5f)
        val half = head * 0.62f
        val bx = x1 - ux * head
        val by = y1 - uy * head
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x0, y0, bx + ux * sw * 0.5f, by + uy * sw * 0.5f, paint)
        val tip = Path().apply {
            moveTo(x1, y1)
            lineTo(bx - uy * half, by + ux * half)
            lineTo(bx + uy * half, by - ux * half)
            close()
        }
        val fill = Paint(paint).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = sw * 0.35f
        }
        canvas.drawPath(tip, fill)
    }
}
