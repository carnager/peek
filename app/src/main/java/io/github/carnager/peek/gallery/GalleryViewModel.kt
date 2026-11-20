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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** What an image grid shows. */
sealed interface Source {
    data object All : Source
    data object Favorites : Source
    data object Trash : Source
    data class Folder(val bucketId: Long) : Source
    data class User(val id: String) : Source
}

/** A short message with an optional action, shown as a snackbar. */
data class Message(val text: String, val action: String? = null, val token: Long = System.nanoTime(), val onAction: (() -> Unit)? = null)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    var images by mutableStateOf<List<MediaImage>>(emptyList())
        private set
    var folders by mutableStateOf<List<Album>>(emptyList())
        private set
    var trash by mutableStateOf<List<MediaImage>>(emptyList())
        private set
    var userAlbums by mutableStateOf(UserAlbums.load(app))
        private set
    var loaded by mutableStateOf(false)
        private set

    var open by mutableStateOf<Source?>(null)
    var viewerIndex by mutableStateOf<Int?>(null)
    /** Bumped to rebuild the viewer at [viewerIndex], e.g. to jump to a freshly saved copy. */
    var viewerKey by mutableIntStateOf(0)
        private set
    var selection by mutableStateOf<Set<Long>>(emptySet())

    /** Images waiting for the "add to album" sheet, or null when it is closed. */
    var addToAlbum by mutableStateOf<List<Long>?>(null)
    var message by mutableStateOf<Message?>(null)

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

    var manageMediaHintDismissed by mutableStateOf(prefs.getBoolean("manageMediaHintDismissed", false))
        private set

    fun dismissManageMediaHint() {
        manageMediaHintDismissed = true
        prefs.edit().putBoolean("manageMediaHintDismissed", true).apply()
    }

    fun changeColumns(delta: Int) {
        val n = (columns + delta).coerceIn(2, 7)
        if (n != columns) {
            columns = n
            prefs.edit().putInt("columns", n).apply()
        }
    }

    val favorites: List<MediaImage> get() = images.filter { it.isFavorite }

    fun imagesOf(source: Source?): List<MediaImage> = when (source) {
        null -> emptyList()
        Source.All -> images
        Source.Favorites -> favorites
        Source.Trash -> trash
        is Source.Folder -> images.filter { it.bucketId == source.bucketId }
        is Source.User -> {
            val ids = userAlbums.firstOrNull { it.id == source.id }?.ids?.toHashSet() ?: emptySet()
            images.filter { it.id in ids }
        }
    }

    val openImages: List<MediaImage> get() = imagesOf(open)

    fun titleOf(source: Source?): String = when (source) {
        null -> "peek"
        Source.All -> "Alle Bilder"
        Source.Favorites -> "Favoriten"
        Source.Trash -> "Papierkorb"
        is Source.Folder -> folders.firstOrNull { it.bucketId == source.bucketId }?.name ?: ""
        is Source.User -> userAlbums.firstOrNull { it.id == source.id }?.name ?: ""
    }

    fun reload(then: (() -> Unit)? = null) {
        job?.cancel()
        job = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val trashed = async { runCatching { MediaRepository.loadImages(ctx, trashed = true) }.getOrDefault(emptyList()) }
            val list = runCatching { MediaRepository.loadImages(ctx) }.getOrDefault(emptyList())
            images = list
            folders = MediaRepository.albumsOf(list)
            trash = trashed.await()
            loaded = true
            val ids = (list + trash).mapTo(HashSet()) { it.id }
            selection = selection.filterTo(HashSet()) { it in ids }
            viewerIndex?.let { i ->
                val n = openImages.size
                viewerIndex = if (n == 0) null else i.coerceAtMost(n - 1)
            }
            then?.invoke()
        }
    }

    fun showInViewer(uri: Uri) {
        val i = openImages.indexOfFirst { it.uri == uri }
        if (i >= 0) {
            viewerIndex = i
            viewerKey++
        }
    }

    fun toggle(id: Long) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun byIds(ids: Collection<Long>): List<MediaImage> {
        val set = ids.toHashSet()
        return (images + trash).filter { it.id in set }
    }

    // --- user albums ---

    private fun saveAlbums(list: List<UserAlbum>) {
        userAlbums = list
        UserAlbums.save(getApplication(), list)
    }

    fun createAlbum(name: String, ids: List<Long> = emptyList()): UserAlbum {
        val a = UserAlbums.new(name).copy(ids = ids)
        saveAlbums(listOf(a) + userAlbums)
        return a
    }

    fun addTo(albumId: String, ids: List<Long>) {
        val a = userAlbums.firstOrNull { it.id == albumId } ?: return
        val fresh = ids.filter { it !in a.ids }
        saveAlbums(userAlbums.map { if (it.id == albumId) it.copy(ids = fresh + it.ids) else it })
        message = Message(if (fresh.isEmpty()) "Schon in „${a.name}“" else "${fresh.size} zu „${a.name}“ hinzugefügt")
    }

    fun removeFrom(albumId: String, ids: Collection<Long>) {
        val set = ids.toHashSet()
        val before = userAlbums
        saveAlbums(userAlbums.map { if (it.id == albumId) it.copy(ids = it.ids.filterNot { id -> id in set }) else it })
        message = Message("${set.size} aus dem Album entfernt", "Rückgängig") { saveAlbums(before) }
    }

    fun renameAlbum(albumId: String, name: String) {
        saveAlbums(userAlbums.map { if (it.id == albumId) it.copy(name = name.trim()) else it })
    }

    fun deleteAlbum(albumId: String) {
        val before = userAlbums
        val name = userAlbums.firstOrNull { it.id == albumId }?.name ?: ""
        saveAlbums(userAlbums.filterNot { it.id == albumId })
        if (open == Source.User(albumId)) open = null
        message = Message("Album „$name“ gelöscht. Die Bilder bleiben erhalten.", "Rückgängig") { saveAlbums(before) }
    }

    /** Cover for a user album: its most recently added photo that still exists. */
    fun coverOf(album: UserAlbum): MediaImage? {
        if (album.ids.isEmpty()) return null
        val byId = images.associateBy { it.id }
        return album.ids.firstNotNullOfOrNull { byId[it] }
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
