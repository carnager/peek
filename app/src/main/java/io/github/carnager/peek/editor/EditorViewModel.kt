package io.github.carnager.peek.editor

import io.github.carnager.peek.R
import io.github.carnager.peek.trunk.TrunkClient
import io.github.carnager.peek.trunk.TrunkLibrary
import io.github.carnager.peek.trunk.sha256
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tool(@StringRes val label: Int) {
    CROP(R.string.edit_tool_crop), ADJUST(R.string.edit_tool_adjust), FILTER(R.string.edit_tool_filter),
    DRAW(R.string.edit_tool_draw), TEXT(R.string.edit_tool_text), RESIZE(R.string.edit_tool_resize),
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    var source by mutableStateOf<SourceInfo?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    /** Full-resolution states; [index] points at the one being shown. */
    private val history = ArrayList<Bitmap>()
    private var index by mutableStateOf(-1)

    var preview by mutableStateOf<Bitmap?>(null)
        private set
    var originalPreview by mutableStateOf<Bitmap?>(null)
        private set
    var tool by mutableStateOf<Tool?>(null)

    // Draw and text work survives rotation, so it lives here rather than in the composables.
    val strokes = mutableStateListOf<Stroke>()
    val textLayers = mutableStateListOf<TextLayer>()
    var selectedText by mutableStateOf<Long?>(null)
    var lastFontId by mutableStateOf("asset:fonts/bebas_neue.ttf")
    var lastTextColor by mutableStateOf(android.graphics.Color.WHITE)
    var lastTextStyle by mutableStateOf(TextStyleMode.SHADOW)

    val current: Bitmap? get() = history.getOrNull(index)
    val canUndo get() = index > 0
    val canRedo get() = index in 0 until history.lastIndex
    val isDirty get() = index > 0

    private val previewSide: Int = run {
        val dm = app.resources.displayMetrics
        maxOf(dm.widthPixels, dm.heightPixels).coerceIn(1280, 2560)
    }

    private var loadedUri: Uri? = null

    fun load(uri: Uri) {
        if (loadedUri == uri) return
        loadedUri = uri
        viewModelScope.launch {
            busy = true
            try {
                val (bmp, info) = ImageIO.load(getApplication(), uri)
                source = info
                history.clear()
                history += bmp
                index = 0
                preview = withContext(Dispatchers.Default) { ImageOps.downscale(bmp, previewSide) }
                originalPreview = preview
            } catch (e: Exception) {
                error = str(R.string.edit_load_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    /** Runs [op] on the full-resolution image off the main thread and records the result as a new step. */
    fun commit(op: (Bitmap) -> Bitmap) {
        val base = current ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                val out = withContext(Dispatchers.Default) { op(base) }
                if (out !== base) push(out)
                tool = null
            } catch (e: OutOfMemoryError) {
                error = str(R.string.edit_out_of_memory)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    private suspend fun push(bmp: Bitmap) {
        while (history.lastIndex > index) history.removeAt(history.lastIndex)
        history += bmp
        // Keep the original plus as many recent steps as fit in about a third of the heap.
        val budget = Runtime.getRuntime().maxMemory() / 3
        while (history.size > 2 && history.sumOf { it.allocationByteCount.toLong() } > budget) history.removeAt(1)
        index = history.lastIndex
        refreshPreview()
    }

    val zoom = ViewZoom()

    private suspend fun refreshPreview() {
        val bmp = current ?: return
        // A new step can change the image's shape, so start from the full view again.
        zoom.reset()
        preview = withContext(Dispatchers.Default) { ImageOps.downscale(bmp, previewSide) }
    }

    fun undo() {
        if (!canUndo || busy) return
        index--
        viewModelScope.launch { refreshPreview() }
    }

    fun redo() {
        if (!canRedo || busy) return
        index++
        viewModelScope.launch { refreshPreview() }
    }

    fun openTool(t: Tool) {
        strokes.clear()
        textLayers.clear()
        selectedText = null
        tool = t
    }

    fun closeTool() {
        strokes.clear()
        textLayers.clear()
        selectedText = null
        tool = null
    }

    fun applyOverlays() {
        val ctx = getApplication<Application>()
        val s = strokes.toList()
        val t = textLayers.toList()
        if (s.isEmpty() && t.isEmpty()) {
            closeTool()
            return
        }
        commit { base ->
            ImageOps.drawOver(base) { canvas, w, h ->
                val fx = if (s.any { it.effect != BrushEffect.NONE }) StrokeEffects.of(base) else null
                s.forEach { OverlayRenderer.drawStroke(canvas, it, 0f, 0f, w, h, fx) }
                t.forEach { OverlayRenderer.drawText(ctx, canvas, it, 0f, 0f, w, h) }
            }
        }
        // The overlays stay visible until the baked image replaces them; openTool() clears them next time.
    }

    suspend fun saveCopy(format: SaveFormat, quality: Int): Uri {
        val bmp = current ?: error(str(R.string.edit_no_image))
        return ImageIO.saveCopy(getApplication(), bmp, source!!, format, quality)
    }

    /** Uploads the edit to trunk as a new photo next to the original. */
    suspend fun uploadToTrunk(format: SaveFormat, quality: Int, originalName: String, taken: Long) = withContext(Dispatchers.IO) {
        val bmp = current ?: error(str(R.string.edit_no_image))
        val file = ImageIO.encodeWithExif(getApplication(), bmp, source!!, format, quality)
        try {
            val name = originalName.substringBeforeLast('.') + "_edit." + format.ext
            TrunkClient.upload(name, taken, sha256(file), format.mime, file.length()) { sink ->
                file.inputStream().use { it.copyTo(sink.outputStream()) }
            }
        } finally {
            file.delete()
        }
    }

    /** Saves the edit as the trunk photo's current version; trunk keeps the first upload. */
    suspend fun saveEditToTrunk(format: SaveFormat, quality: Int, id: Long) = withContext(Dispatchers.IO) {
        val bmp = current ?: error(str(R.string.edit_no_image))
        val file = ImageIO.encodeWithExif(getApplication(), bmp, source!!, format, quality)
        try {
            TrunkLibrary.put(getApplication(), TrunkClient.saveEdit(id, file, sha256(file), format.mime))
        } finally {
            file.delete()
        }
    }

    suspend fun overwrite(quality: Int) {
        val bmp = current ?: error(str(R.string.edit_no_image))
        ImageIO.overwrite(getApplication(), bmp, source!!, quality)
    }

    private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }
}
