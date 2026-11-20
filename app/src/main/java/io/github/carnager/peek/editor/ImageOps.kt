package io.github.carnager.peek.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pixel operations shared by the live preview and the final render. Anything drawn on screen
 * goes through the same functions as the saved file, so what you see is what gets written.
 */
object ImageOps {

    /** Quarter turns clockwise, then mirroring, in that order. */
    fun transform(src: Bitmap, quarterTurns: Int, flipH: Boolean, flipV: Boolean): Bitmap {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0 && !flipH && !flipV) return src
        val m = Matrix()
        m.postRotate(90f * turns)
        m.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Crops to a rectangle given in 0..1 coordinates of [src]. */
    fun crop(src: Bitmap, rect: RectF): Bitmap {
        val x = (rect.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val y = (rect.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val w = (rect.width() * src.width).roundToInt().coerceIn(1, src.width - x)
        val h = (rect.height() * src.height).roundToInt().coerceIn(1, src.height - y)
        if (x == 0 && y == 0 && w == src.width && h == src.height) return src
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    /** High-quality resize; large reductions go in halving steps so the bilinear filter does not alias. */
    fun resize(src: Bitmap, width: Int, height: Int): Bitmap {
        var cur = src
        while (cur.width / 2 >= width && cur.height / 2 >= height) {
            cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        }
        if (cur.width == width && cur.height == height) return cur
        return Bitmap.createScaledBitmap(cur, width, height, true)
    }

    /** A copy no larger than [maxSide] on its long edge, for fast on-screen work. */
    fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val long = max(src.width, src.height)
        if (long <= maxSide) return src
        val f = maxSide.toFloat() / long
        return resize(src, (src.width * f).roundToInt().coerceAtLeast(1), (src.height * f).roundToInt().coerceAtLeast(1))
    }

    fun applyColor(src: Bitmap, matrix: ColorMatrix, vignette: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
        drawVignette(canvas, 0f, 0f, src.width.toFloat(), src.height.toFloat(), vignette)
        return out
    }

    /** Darkens the edges; [strength] 0..1. */
    fun drawVignette(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, strength: Float) {
        if (strength <= 0.001f) return
        val cx = left + width / 2
        val cy = top + height / 2
        val radius = hypot(width, height) / 2
        val alpha = (strength.coerceIn(0f, 1f) * 235).roundToInt()
        val shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(0, 0, (alpha shl 24)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, top, left + width, top + height, Paint().apply { this.shader = shader })
    }

    /** A copy of [src] with overlays drawn by [draw] in full-resolution pixel coordinates. */
    fun drawOver(src: Bitmap, draw: (Canvas, Float, Float) -> Unit): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        draw(Canvas(out), out.width.toFloat(), out.height.toFloat())
        return out
    }

    /** Where an image of the given size lands when fitted, centred, into a box. */
    fun fitRect(imageW: Int, imageH: Int, boxW: Float, boxH: Float): RectF {
        if (imageW <= 0 || imageH <= 0 || boxW <= 0 || boxH <= 0) return RectF()
        val scale = minOf(boxW / imageW, boxH / imageH)
        val w = imageW * scale
        val h = imageH * scale
        val l = (boxW - w) / 2
        val t = (boxH - h) / 2
        return RectF(l, t, l + w, t + h)
    }
}
