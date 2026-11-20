package io.github.carnager.peek.gallery

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.carnager.peek.trunk.RemoteAsset
import io.github.carnager.peek.trunk.TrunkAccount
import io.github.carnager.peek.trunk.TrunkClient
import io.github.carnager.peek.trunk.TrunkException
import io.github.carnager.peek.trunk.TrunkLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** What an image grid shows. */
sealed interface Source {
    data object All : Source
    data object Favorites : Source
    data object Trash : Source
    data class Folder(val bucketId: Long) : Source
    data class User(val id: String) : Source
    /** The library on the trunk server. */
    data object Trunk : Source
    /** An album on the trunk server. */
    data class TrunkAlbum(val id: Long) : Source
}

/** Shows photos from the trunk server, so actions go there. */
val Source?.onTrunk: Boolean get() = this == Source.Trunk || this is Source.TrunkAlbum

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

    // --- trunk ---

    var trunk by mutableStateOf<List<MediaImage>>(emptyList())
        private set
    var trunkSyncing by mutableStateOf(false)
        private set
    var trunkConnected by mutableStateOf(TrunkAccount.connected)
        private set
    var trunkAlbums by mutableStateOf<List<io.github.carnager.peek.trunk.RemoteAlbum>>(emptyList())
        private set

    private fun RemoteAsset.toImage() = MediaImage(
        // Negative ids keep server photos apart from MediaStore ones in selections and animations.
        id = -id,
        uri = Uri.parse(TrunkAccount.fileUrl(this)),
        name = name,
        bucketId = Long.MIN_VALUE,
        bucketName = "trunk",
        relativePath = null,
        mimeType = mime,
        width = width,
        height = height,
        size = size,
        dateTaken = displayMillis,
        modified = (updatedAt ?: "").hashCode().toLong(),
        isFavorite = favorite,
        isVideo = isVideo,
        duration = durationMs,
        remote = this,
    )

    /** Shows the cached library at once, then asks the server what changed. */
    fun openTrunk() {
        viewModelScope.launch {
            trunk = TrunkLibrary.cached(getApplication()).map { it.toImage() }
            trunkAlbums = TrunkLibrary.cachedAlbums(getApplication())
            syncTrunk()
        }
    }

    fun syncTrunk() {
        if (!TrunkAccount.connected || trunkSyncing) return
        viewModelScope.launch {
            trunkSyncing = true
            try {
                trunk = TrunkLibrary.sync(getApplication()).map { it.toImage() }
                trunkAlbums = TrunkLibrary.cachedAlbums(getApplication())
                if (open is Source.TrunkAlbum && trunkAlbums.none { it.id == (open as Source.TrunkAlbum).id }) {
                    open = null // deleted on another device
                    viewerIndex = null
                }
                viewerIndex?.let { i ->
                    if (open.onTrunk) {
                        val n = openImages.size
                        viewerIndex = if (n == 0) null else i.coerceAtMost(n - 1)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("peek-trunk", "sync failed", e)
                message = Message("trunk nicht erreichbar: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                trunkSyncing = false
            }
        }
    }

    /** Logs in; [done] gets null on success or the error to show. */
    fun trunkConnect(address: String, user: String, password: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                TrunkLibrary.clear(getApplication())
                TrunkClient.login(getApplication(), address, user, password)
                trunkConnected = true
                done(null)
                openTrunk()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: TrunkException) {
                done(if (e.status == 401) "Name oder Passwort stimmt nicht" else e.message)
            } catch (e: Exception) {
                done("Server nicht erreichbar: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun trunkLogout() {
        viewModelScope.launch {
            TrunkClient.logout(getApplication())
            TrunkLibrary.clear(getApplication())
            io.github.carnager.peek.trunk.TrunkFiles.clear(getApplication())
            trunkConnected = false
            trunk = emptyList()
            trunkAlbums = emptyList()
            if (open.onTrunk) open = null
        }
    }

    private fun trunkCall(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                message = Message("trunk: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Downloads trunk photos (cached), then hands their local URIs to [then]. */
    fun withTrunkOriginals(images: List<MediaImage>, then: (List<Uri>) -> Unit) = trunkCall {
        val remote = images.mapNotNull { it.remote }
        if (remote.isEmpty()) return@trunkCall
        if (remote.sumOf { it.size } > 2_000_000) {
            message = Message(if (remote.size == 1) "Lade Original …" else "Lade ${remote.size} Originale …")
        }
        val uris = remote.map { io.github.carnager.peek.trunk.TrunkFiles.file(getApplication(), it) }
        then(uris)
    }

    fun trunkFavorite(images: List<MediaImage>, favorite: Boolean) = trunkCall {
        images.mapNotNull { it.remote }.forEach { TrunkLibrary.put(getApplication(), TrunkClient.setFavorite(it.id, favorite)) }
        trunk = TrunkLibrary.cached(getApplication()).map { it.toImage() }
    }

    // Albums on trunk. Images carry negative ids (see toImage); the server wants its own.

    private fun remoteIds(imageIds: Collection<Long>): List<Long> {
        val byId = trunk.associateBy { it.id }
        return imageIds.mapNotNull { byId[it]?.remote?.id }
    }

    private suspend fun applyAlbum(o: org.json.JSONObject?, deletedId: Long? = null) {
        trunkAlbums = TrunkLibrary.putAlbum(getApplication(), o, deletedId)
    }

    /** First photo of a trunk album that is still in the library, for its card. */
    fun trunkCoverOf(album: io.github.carnager.peek.trunk.RemoteAlbum, byRemote: Map<Long, MediaImage>): MediaImage? =
        album.assetIds.firstNotNullOfOrNull { byRemote[it] }

    fun trunkCreateAlbum(name: String, imageIds: List<Long> = emptyList(), then: (Long) -> Unit = {}) = trunkCall {
        val o = TrunkClient.createAlbum(name.trim(), remoteIds(imageIds))
        applyAlbum(o)
        if (imageIds.isNotEmpty()) message = Message("${imageIds.size} zu „${o.optString("name")}“ hinzugefügt")
        then(o.getLong("id"))
    }

    fun trunkAddTo(albumId: Long, imageIds: List<Long>) = trunkCall {
        val album = trunkAlbums.firstOrNull { it.id == albumId } ?: return@trunkCall
        val ids = remoteIds(imageIds)
        val fresh = ids.filter { it !in album.assetIds }
        if (fresh.isEmpty()) {
            message = Message("Schon in „${album.name}“")
            return@trunkCall
        }
        applyAlbum(TrunkClient.addToAlbum(albumId, fresh))
        message = Message("${fresh.size} zu „${album.name}“ hinzugefügt")
    }

    fun trunkRemoveFrom(albumId: Long, imageIds: Collection<Long>) = trunkCall {
        val ids = remoteIds(imageIds)
        applyAlbum(TrunkClient.removeFromAlbum(albumId, ids))
        message = Message("${ids.size} aus dem Album entfernt", "Rückgängig") {
            trunkCall { applyAlbum(TrunkClient.addToAlbum(albumId, ids)) }
        }
    }

    fun trunkRenameAlbum(albumId: Long, name: String) = trunkCall {
        applyAlbum(TrunkClient.renameAlbum(albumId, name.trim()))
    }

    fun trunkDeleteAlbum(albumId: Long) = trunkCall {
        val album = trunkAlbums.firstOrNull { it.id == albumId } ?: return@trunkCall
        TrunkClient.deleteAlbum(albumId)
        applyAlbum(null, deletedId = albumId)
        if (open == Source.TrunkAlbum(albumId)) open = null
        message = Message("Album „${album.name}“ gelöscht. Die Bilder bleiben erhalten.", "Rückgängig") {
            // The server forgets deleted albums; a new one with the same content takes its place.
            trunkCall { applyAlbum(TrunkClient.createAlbum(album.name, album.assetIds)) }
        }
    }

    /** Back to the photo as first uploaded. */
    fun trunkRevertEdit(image: MediaImage) = trunkCall {
        val id = image.remote?.id ?: return@trunkCall
        TrunkLibrary.put(getApplication(), TrunkClient.revertEdit(id))
        trunk = TrunkLibrary.cached(getApplication()).map { it.toImage() }
        message = Message("Bearbeitung verworfen, Original wiederhergestellt")
    }

    fun trunkTrash(images: List<MediaImage>) = trunkCall {
        val ids = images.mapNotNull { it.remote?.id }
        if (ids.isEmpty()) return@trunkCall
        TrunkClient.trash(ids)
        selection = emptySet()
        syncTrunk()
        message = Message(if (ids.size == 1) "1 Bild in trunks Papierkorb" else "${ids.size} Bilder in trunks Papierkorb", "Rückgängig") {
            trunkCall { TrunkClient.restore(ids); syncTrunk() }
        }
    }

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
        Source.Trunk -> trunk
        is Source.TrunkAlbum -> {
            val album = trunkAlbums.firstOrNull { it.id == source.id }
            if (album == null) emptyList() else {
                val byRemote = trunk.associateBy { it.remote!!.id }
                album.assetIds.mapNotNull { byRemote[it] }
            }
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
        Source.Trunk -> "trunk"
        is Source.TrunkAlbum -> trunkAlbums.firstOrNull { it.id == source.id }?.name ?: ""
    }

    fun reload(then: (() -> Unit)? = null) {
        job?.cancel()
        job = viewModelScope.launch {
            val ctx = getApplication<Application>()
            // A newer reload cancels this one; that must not be mistaken for "no images", or a
            // cancelled load would overwrite the real list with an empty one.
            suspend fun load(trashed: Boolean) = try {
                MediaRepository.loadImages(ctx, trashed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val trashed = async { load(trashed = true) }
            val list = load(trashed = false)
            val trashList = trashed.await()
            ensureActive()
            images = list
            folders = MediaRepository.albumsOf(list)
            trash = trashList
            loaded = true
            if (reviewWhenLoaded) {
                reviewWhenLoaded = false
                startReview()
            }
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

    // --- nightly review ---

    /** A review in progress: the photos it shows and the DATE_ADDED mark it moves to when finished. */
    class ReviewSession(val items: List<MediaImage>, val addedUpTo: Long)

    var review by mutableStateOf<ReviewSession?>(null)
    var reviewSettings by mutableStateOf(io.github.carnager.peek.review.Review.settings(app))
        private set
    private var reviewMark by mutableLongStateOf(io.github.carnager.peek.review.Review.mark(app))
    private var reviewWhenLoaded = false

    /** Photos and videos waiting for review, oldest first, like the day went. */
    val reviewPending: List<MediaImage>
        get() {
            val s = reviewSettings
            if (!s.configured || s.buckets.isEmpty()) return emptyList()
            return images.filter { it.bucketId in s.buckets && it.added > reviewMark }.sortedBy { it.dateTaken }
        }

    fun saveReviewSettings(s: io.github.carnager.peek.review.Review.Settings) {
        io.github.carnager.peek.review.Review.save(getApplication(), s)
        reviewSettings = io.github.carnager.peek.review.Review.settings(getApplication())
        reviewMark = io.github.carnager.peek.review.Review.mark(getApplication())
    }

    /** Opens the review; from the notification the photos may still be loading. */
    fun startReview() {
        if (!loaded) {
            reviewWhenLoaded = true
            return
        }
        val items = reviewPending
        if (items.isEmpty()) {
            message = Message("Nichts Neues zum Durchsehen")
            return
        }
        viewerIndex = null
        review = ReviewSession(items, items.maxOf { it.added })
    }

    fun finishReview(session: ReviewSession) {
        io.github.carnager.peek.review.Review.finished(getApplication(), session.addedUpTo)
        reviewMark = io.github.carnager.peek.review.Review.mark(getApplication())
        review = null
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
