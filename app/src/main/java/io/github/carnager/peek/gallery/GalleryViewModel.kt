package io.github.carnager.peek.gallery

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

const val ALL_IMAGES = Long.MIN_VALUE

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    var images by mutableStateOf<List<MediaImage>>(emptyList())
        private set
    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    var openAlbum by mutableStateOf<Long?>(null)
    var viewerIndex by mutableStateOf<Int?>(null)
    /** Bumped to rebuild the viewer at [viewerIndex], e.g. to jump to a freshly saved copy. */
    var viewerKey by mutableIntStateOf(0)
        private set
    var selection by mutableStateOf<Set<Long>>(emptySet())

    private var job: Job? = null

    /** Where each visible thumbnail sits on screen, for the open/close animation. Not observed. */
    val thumbBounds = HashMap<Long, androidx.compose.ui.geometry.Rect>()

    /** The photo currently flying between grid and viewer, if any. */
    var hero by mutableStateOf<Hero?>(null)

    fun openViewer(index: Int, image: MediaImage) {
        thumbBounds[image.id]?.let { hero = Hero(image, it, opening = true) }
        viewerIndex = index
    }

    private val prefs = app.getSharedPreferences("gallery", 0)

    /** Thumbnail columns in an album, changed by pinching the grid. */
    var columns by mutableIntStateOf(prefs.getInt("columns", 4))
        private set

    fun changeColumns(delta: Int) {
        val n = (columns + delta).coerceIn(2, 7)
        if (n != columns) {
            columns = n
            prefs.edit().putInt("columns", n).apply()
        }
    }

    fun imagesOf(album: Long?): List<MediaImage> = when (album) {
        null -> emptyList()
        ALL_IMAGES -> images
        else -> images.filter { it.bucketId == album }
    }

    val albumImages: List<MediaImage> get() = imagesOf(openAlbum)

    fun titleOf(album: Long?): String = when (album) {
        ALL_IMAGES -> "Alle Bilder"
        null -> "peek"
        else -> albums.firstOrNull { it.bucketId == album }?.name ?: ""
    }

    fun reload(then: (() -> Unit)? = null) {
        job?.cancel()
        job = viewModelScope.launch {
            val list = runCatching { MediaRepository.loadImages(getApplication()) }.getOrDefault(emptyList())
            images = list
            albums = MediaRepository.albumsOf(list)
            loaded = true
            val ids = list.mapTo(HashSet()) { it.id }
            selection = selection.filterTo(HashSet()) { it in ids }
            viewerIndex?.let { i ->
                val n = albumImages.size
                viewerIndex = if (n == 0) null else i.coerceAtMost(n - 1)
            }
            then?.invoke()
        }
    }

    fun showInViewer(uri: Uri) {
        val i = albumImages.indexOfFirst { it.uri == uri }
        if (i >= 0) {
            viewerIndex = i
            viewerKey++
        }
    }

    fun toggle(id: Long) {
        selection = if (id in selection) selection - id else selection + id
    }
}

data class Hero(
    val image: MediaImage,
    /** Opening: the thumbnail's bounds. Closing: where the photo was in the viewer. */
    val from: androidx.compose.ui.geometry.Rect,
    val opening: Boolean,
    /** Viewer background opacity at the start of a close (lower after a swipe). */
    val backdrop: Float = 1f,
    val token: Long = System.nanoTime(),
)
