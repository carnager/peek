package io.github.carnager.peek.editor

import android.graphics.ColorMatrix
import androidx.annotation.StringRes
import io.github.carnager.peek.R

/** Slider values, each -1..1 except vignette (0..1). Zero means untouched. */
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val fade: Float = 0f,
    val vignette: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val sharpen: Float = 0f,
) {
    val isIdentity get() = this == Adjustments()

    /** The parts that need per-pixel work on the CPU (everything else is a GPU colour matrix). */
    val pixelPart get() = Triple(highlights, shadows, sharpen)

    fun matrix(): ColorMatrix {
        val m = ColorMatrix()
        if (saturation != 0f) m.postConcat(ColorMatrix().apply { setSaturation(1f + saturation) })
        if (warmth != 0f) {
            val w = warmth * 0.18f
            m.postConcat(scaleOffset(1f + w, 1f, 1f - w, 0f, 0f, 0f))
        }
        if (tint != 0f) {
            // Positive pushes towards magenta, negative towards green.
            val t = tint * 0.12f
            m.postConcat(scaleOffset(1f + t / 2, 1f - t, 1f + t / 2, 0f, 0f, 0f))
        }
        if (contrast != 0f) {
            val s = if (contrast > 0) 1f + contrast * 0.9f else 1f + contrast * 0.7f
            val o = 128f * (1f - s)
            m.postConcat(scaleOffset(s, s, s, o, o, o))
        }
        if (brightness != 0f) {
            val o = brightness * 90f
            m.postConcat(scaleOffset(1f, 1f, 1f, o, o, o))
        }
        if (fade != 0f) {
            // Lifts the blacks and pulls in the whites, like matte film.
            val f = fade.coerceAtLeast(0f) * 0.35f
            m.postConcat(scaleOffset(1f - f, 1f - f, 1f - f, f * 110f, f * 110f, f * 110f))
        }
        return m
    }
}

enum class AdjustParam(@StringRes val label: Int, val min: Float) {
    BRIGHTNESS(R.string.edit_adjust_brightness, -1f),
    CONTRAST(R.string.edit_adjust_contrast, -1f),
    HIGHLIGHTS(R.string.edit_adjust_highlights, -1f),
    SHADOWS(R.string.edit_adjust_shadows, -1f),
    SATURATION(R.string.edit_adjust_saturation, -1f),
    WARMTH(R.string.edit_adjust_warmth, -1f),
    TINT(R.string.edit_adjust_tint, -1f),
    FADE(R.string.edit_adjust_fade, 0f),
    SHARPEN(R.string.edit_adjust_sharpen, 0f),
    VIGNETTE(R.string.edit_adjust_vignette, 0f);

    fun get(a: Adjustments) = when (this) {
        BRIGHTNESS -> a.brightness
        CONTRAST -> a.contrast
        SATURATION -> a.saturation
        WARMTH -> a.warmth
        TINT -> a.tint
        FADE -> a.fade
        VIGNETTE -> a.vignette
        HIGHLIGHTS -> a.highlights
        SHADOWS -> a.shadows
        SHARPEN -> a.sharpen
    }

    fun set(a: Adjustments, v: Float) = when (this) {
        BRIGHTNESS -> a.copy(brightness = v)
        CONTRAST -> a.copy(contrast = v)
        SATURATION -> a.copy(saturation = v)
        WARMTH -> a.copy(warmth = v)
        TINT -> a.copy(tint = v)
        FADE -> a.copy(fade = v)
        VIGNETTE -> a.copy(vignette = v)
        HIGHLIGHTS -> a.copy(highlights = v)
        SHADOWS -> a.copy(shadows = v)
        SHARPEN -> a.copy(sharpen = v)
    }
}

class FilterPreset(@StringRes val name: Int, val vignette: Float = 0f, private val build: () -> ColorMatrix) {
    fun matrix(intensity: Float): ColorMatrix = lerp(ColorMatrix(), build(), intensity)
}

private fun scaleOffset(r: Float, g: Float, b: Float, ro: Float, go: Float, bo: Float) = ColorMatrix(
    floatArrayOf(
        r, 0f, 0f, 0f, ro,
        0f, g, 0f, 0f, go,
        0f, 0f, b, 0f, bo,
        0f, 0f, 0f, 1f, 0f,
    )
)

private fun chain(vararg ms: ColorMatrix) = ColorMatrix().apply { ms.forEach { postConcat(it) } }

private fun saturate(s: Float) = ColorMatrix().apply { setSaturation(s) }

private fun contrast(c: Float): ColorMatrix {
    val o = 128f * (1f - c)
    return scaleOffset(c, c, c, o, o, o)
}

private fun lerp(a: ColorMatrix, b: ColorMatrix, t: Float): ColorMatrix {
    val x = a.array
    val y = b.array
    return ColorMatrix(FloatArray(20) { x[it] + (y[it] - x[it]) * t })
}

val FILTERS: List<FilterPreset> = listOf(
    FilterPreset(R.string.edit_original) { ColorMatrix() },
    FilterPreset(R.string.edit_filter_vivid) { chain(saturate(1.45f), contrast(1.12f)) },
    FilterPreset(R.string.edit_filter_warm) { chain(scaleOffset(1.10f, 1.02f, 0.88f, 8f, 4f, -6f), saturate(1.1f)) },
    FilterPreset(R.string.edit_filter_cool) { chain(scaleOffset(0.90f, 1.0f, 1.12f, -4f, 2f, 10f), saturate(0.95f)) },
    FilterPreset(R.string.edit_filter_matte) { chain(saturate(0.85f), scaleOffset(0.82f, 0.82f, 0.82f, 32f, 30f, 34f)) },
    FilterPreset(R.string.edit_filter_vintage, vignette = 0.35f) {
        chain(saturate(0.7f), scaleOffset(1.0f, 0.95f, 0.78f, 24f, 16f, 6f), contrast(0.95f))
    },
    FilterPreset(R.string.edit_filter_drama, vignette = 0.45f) { chain(contrast(1.35f), saturate(0.75f), scaleOffset(1f, 1f, 1f, -10f, -10f, -10f)) },
    FilterPreset(R.string.edit_filter_chrome) { chain(contrast(1.2f), saturate(1.25f), scaleOffset(1.02f, 1f, 1.06f, 0f, 0f, 4f)) },
    FilterPreset(R.string.edit_filter_pastel) { chain(saturate(0.75f), scaleOffset(0.85f, 0.85f, 0.85f, 38f, 34f, 42f)) },
    FilterPreset(R.string.edit_filter_bw) { saturate(0f) },
    FilterPreset(R.string.edit_filter_noir, vignette = 0.4f) { chain(saturate(0f), contrast(1.45f)) },
    FilterPreset(R.string.edit_filter_sepia) {
        chain(
            saturate(0f),
            ColorMatrix(
                floatArrayOf(
                    1.07f, 0f, 0f, 0f, 20f,
                    0f, 0.95f, 0f, 0f, 8f,
                    0f, 0f, 0.78f, 0f, -6f,
                    0f, 0f, 0f, 1f, 0f,
                )
            ),
        )
    },
    FilterPreset(R.string.edit_filter_lomo, vignette = 0.6f) { chain(contrast(1.25f), saturate(1.3f), scaleOffset(1.04f, 1.02f, 0.92f, 0f, 0f, 0f)) },
    FilterPreset(R.string.edit_filter_invert) {
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    },
)
