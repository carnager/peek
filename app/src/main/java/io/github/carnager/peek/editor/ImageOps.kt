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

    /**
     * How much an image rotated by [degrees] must be enlarged so it still fills its own frame,
     * i.e. no empty corners show after straightening.
     */
    fun straightenScale(w: Float, h: Float, degrees: Float): Float {
        if (degrees == 0f || w <= 0f || h <= 0f) return 1f
        val a = Math.toRadians(kotlin.math.abs(degrees).toDouble())
        val c = kotlin.math.cos(a).toFloat()
        val s = kotlin.math.sin(a).toFloat()
        return max((w * c + h * s) / w, (w * s + h * c) / h)
    }

    /** Rotates by a small angle around the centre, zoomed so the frame stays filled. Same size as [src]. */
    fun straighten(src: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cx = src.width / 2f
        val cy = src.height / 2f
        val sc = straightenScale(src.width.toFloat(), src.height.toFloat(), degrees)
        canvas.rotate(degrees, cx, cy)
        canvas.scale(sc, sc, cx, cy)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
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

    /**
     * Highlights and shadows as a tone curve applied per channel. Each control acts on its own
     * part of the tonal range (shadows peak around a third, highlights around two thirds), so
     * black and white points stay put.
     */
    fun tone(src: Bitmap, highlights: Float, shadows: Float): Bitmap {
        if (highlights == 0f && shadows == 0f) return src
        val lut = IntArray(256) { i ->
            val x = i / 255f
            val ws = 4f * x * (1 - x) * (1 - x)
            val wh = 4f * x * x * (1 - x)
            ((x + 0.45f * shadows * ws + 0.45f * highlights * wh).coerceIn(0f, 1f) * 255f).roundToInt()
        }
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (c and -0x1000000) or (lut[(c shr 16) and 0xFF] shl 16) or (lut[(c shr 8) and 0xFF] shl 8) or lut[c and 0xFF]
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Unsharp mask; the blur radius scales with the image so preview and full size look alike. */
    fun sharpen(src: Bitmap, amount: Float): Bitmap {
        if (amount <= 0f) return src
        val w = src.width
        val h = src.height
        val r = (minOf(w, h) / 1100f).roundToInt().coerceIn(1, 6)
        val orig = IntArray(w * h)
        src.getPixels(orig, 0, w, 0, 0, w, h)
        val blur = boxBlur(boxBlur(orig, w, h, r, horizontal = true), w, h, r, horizontal = false)
        val k = amount * 1.6f
        val out = IntArray(w * h)
        for (i in orig.indices) {
            val o = orig[i]
            val b = blur[i]
            fun ch(shift: Int): Int {
                val ov = (o shr shift) and 0xFF
                val bv = (b shr shift) and 0xFF
                return (ov + k * (ov - bv)).roundToInt().coerceIn(0, 255)
            }
            out[i] = (o and -0x1000000) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** One pass of a running-sum box blur over RGB, along rows or columns. */
    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean): IntArray {
        val out = IntArray(src.size)
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(p: Int) = if (horizontal) line * w + p else p * w + line
            var sr = 0; var sg = 0; var sb = 0
            for (p in -r..r) {
                val c = src[idx(p.coerceIn(0, len - 1))]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (p in 0 until len) {
                out[idx(p)] = (src[idx(p)] and -0x1000000) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = src[idx((p + r + 1).coerceAtMost(len - 1))]
                val rem = src[idx((p - r).coerceAtLeast(0))]
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
        return out
    }

    /** The per-pixel part of [adj] (tone, then sharpening). */
    fun pixelAdjust(src: Bitmap, adj: Adjustments): Bitmap = sharpen(tone(src, adj.highlights, adj.shadows), adj.sharpen)

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
