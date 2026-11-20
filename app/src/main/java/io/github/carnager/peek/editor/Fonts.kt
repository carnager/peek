package io.github.carnager.peek.editor

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** A font the text tool can use. [id] is stable across sessions so the last choice can be remembered. */
data class FontOption(val id: String, val label: String)

object Fonts {
    private val cache = HashMap<String, Typeface>()

    private val system = listOf(
        FontOption("sys:sans-serif", "Sans"),
        FontOption("sys:sans-serif-black", "Sans Black"),
        FontOption("sys:sans-serif-condensed", "Condensed"),
        FontOption("sys:serif", "Serif"),
        FontOption("sys:monospace", "Mono"),
    )

    // Bundled under OFL / Apache 2.0, see assets/fonts/NOTICE.
    private val bundled = listOf(
        "bebas_neue" to "Bebas Neue",
        "anton" to "Anton",
        "oswald" to "Oswald",
        "righteous" to "Righteous",
        "bangers" to "Bangers",
        "abril_fatface" to "Abril Fatface",
        "playfair_display" to "Playfair",
        "lobster" to "Lobster",
        "pacifico" to "Pacifico",
        "dancing_script" to "Dancing Script",
        "caveat" to "Caveat",
        "permanent_marker" to "Marker",
        "amatic_sc" to "Amatic",
        "special_elite" to "Schreibmaschine",
        "press_start_2p" to "Pixel",
    ).map { (file, label) -> FontOption("asset:fonts/$file.ttf", label) }

    fun userDir(context: Context) = File(context.filesDir, "fonts").apply { mkdirs() }

    fun all(context: Context): List<FontOption> {
        val user = userDir(context).listFiles().orEmpty()
            .filter { it.extension.lowercase() in setOf("ttf", "otf") }
            .sortedBy { it.name.lowercase() }
            .map { FontOption("file:${it.absolutePath}", it.nameWithoutExtension) }
        return system + bundled + user
    }

    fun typeface(context: Context, id: String): Typeface = cache.getOrPut(id) {
        runCatching {
            when {
                id.startsWith("sys:") -> Typeface.create(id.removePrefix("sys:"), Typeface.NORMAL)
                id.startsWith("asset:") -> Typeface.createFromAsset(context.assets, id.removePrefix("asset:"))
                id.startsWith("file:") -> Typeface.createFromFile(id.removePrefix("file:"))
                else -> Typeface.DEFAULT
            }
        }.getOrDefault(Typeface.DEFAULT)
    }

    /** Copies a picked TTF/OTF into app storage. Returns the new font, or null if the file is not a usable font. */
    fun import(context: Context, uri: Uri): FontOption? {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "font.ttf"
        val ext = name.substringAfterLast('.', "ttf").lowercase().takeIf { it == "ttf" || it == "otf" } ?: "ttf"
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "font" }
        val target = File(userDir(context), "$base.$ext")
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return null
        val ok = runCatching { Typeface.createFromFile(target) }.getOrNull()?.let { it != Typeface.DEFAULT } ?: false
        if (!ok) {
            target.delete()
            return null
        }
        val option = FontOption("file:${target.absolutePath}", base)
        cache.remove(option.id)
        return option
    }
}
