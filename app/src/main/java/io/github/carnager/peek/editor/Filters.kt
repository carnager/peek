package io.github.carnager.peek.editor

import android.graphics.ColorMatrix

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

enum class AdjustParam(val label: String, val min: Float) {
    BRIGHTNESS("Helligkeit", -1f),
    CONTRAST("Kontrast", -1f),
    HIGHLIGHTS("Lichter", -1f),
    SHADOWS("Schatten", -1f),
    SATURATION("Sättigung", -1f),
    WARMTH("Wärme", -1f),
    TINT("Tönung", -1f),
    FADE("Verblassen", 0f),
    SHARPEN("Schärfe", 0f),
    VIGNETTE("Vignette", 0f);

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

class FilterPreset(val name: String, val vignette: Float = 0f, private val build: () -> ColorMatrix) {
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
    FilterPreset("Original") { ColorMatrix() },
    FilterPreset("Lebhaft") { chain(saturate(1.45f), contrast(1.12f)) },
    FilterPreset("Warm") { chain(scaleOffset(1.10f, 1.02f, 0.88f, 8f, 4f, -6f), saturate(1.1f)) },
    FilterPreset("Kühl") { chain(scaleOffset(0.90f, 1.0f, 1.12f, -4f, 2f, 10f), saturate(0.95f)) },
    FilterPreset("Matt") { chain(saturate(0.85f), scaleOffset(0.82f, 0.82f, 0.82f, 32f, 30f, 34f)) },
    FilterPreset("Vintage", vignette = 0.35f) {
        chain(saturate(0.7f), scaleOffset(1.0f, 0.95f, 0.78f, 24f, 16f, 6f), contrast(0.95f))
    },
    FilterPreset("Drama", vignette = 0.45f) { chain(contrast(1.35f), saturate(0.75f), scaleOffset(1f, 1f, 1f, -10f, -10f, -10f)) },
    FilterPreset("Chrom") { chain(contrast(1.2f), saturate(1.25f), scaleOffset(1.02f, 1f, 1.06f, 0f, 0f, 4f)) },
    FilterPreset("Pastell") { chain(saturate(0.75f), scaleOffset(0.85f, 0.85f, 0.85f, 38f, 34f, 42f)) },
    FilterPreset("S/W") { saturate(0f) },
    FilterPreset("Noir", vignette = 0.4f) { chain(saturate(0f), contrast(1.45f)) },
    FilterPreset("Sepia") {
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
    FilterPreset("Lomo", vignette = 0.6f) { chain(contrast(1.25f), saturate(1.3f), scaleOffset(1.04f, 1.02f, 0.92f, 0f, 0f, 0f)) },
    FilterPreset("Invertiert") {
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
