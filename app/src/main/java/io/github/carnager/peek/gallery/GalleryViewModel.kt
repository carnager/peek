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
    /** Photos from this day in earlier years; [remote] when they come from trunk. */
    data class Memories(val remote: Boolean) : Source
}

/**
 * "On this day": photos taken on today's date in earlier years, or, when there are none, in the
 * week around it. Most recent year first, each day in the order it happened.
 */
class OnThisDay(val images: List<MediaImage>, val yearsAgo: List<Int>, val wholeWeek: Boolean, val remote: Boolean) {
    /** "Heute vor 2 Jahren", "Diese Woche vor 1, 3 und 5 Jahren". */
    val title: String
        get() {
            val n = yearsAgo
            val years = when {
                n.size == 1 && n[0] == 1 -> "einem Jahr"
                n.size == 1 -> "${n[0]} Jahren"
                else -> n.dropLast(1).joinToString(", ") + " und ${n.last()} Jahren"
            }
            return (if (wholeWeek) "Diese Woche vor " else "Heute vor ") + years
        }

    /** A favourite if there is one, else a photo from the middle of the most recent year. */
    val cover: MediaImage
        get() = images.firstOrNull { it.isFavorite } ?: images.filter { y -> yearOf(y) == yearOf(images.first()) }.let { it[it.size / 2] }

    /** The town most of them were taken in, if known. */
    val place: String?
        get() = images.mapNotNull { it.remote?.city }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    companion object {
        fun dateOf(img: MediaImage): java.time.LocalDate =
            java.time.Instant.ofEpochMilli(img.dateTaken).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

        private fun yearOf(img: MediaImage) = dateOf(img).year

        fun of(pool: List<MediaImage>, today: java.time.LocalDate, remote: Boolean): OnThisDay? {
            val past = pool.map { it to dateOf(it) }.filter { it.second.year < today.year }
            var hits = past.filter { (_, d) -> d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth }
            var week = false
            if (hits.isEmpty()) {
                // The same week in other years; withYear turns 29 February into the 28th.
                hits = past.filter { (_, d) ->
                    val days = kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, d.withYear(today.year)))
                    minOf(days, 365 - days) <= 3
                }
                week = true
            }
            if (hits.isEmpty()) return null
            val sorted = hits.sortedWith(compareByDescending<Pair<MediaImage, java.time.LocalDate>> { it.second.year }.thenBy { it.first.dateTaken })
            val ago = sorted.map { today.year - it.second.year }.distinct().sorted()
            return OnThisDay(sorted.map { it.first }, ago, week, remote)
        }
    }
}

/** How the trunk library is ordered. */
enum class TrunkSort(val label: String, val byDate: Boolean) {
    Newest("Neueste zuerst", true),
    Oldest("Älteste zuerst", true),
    Added("Zuletzt hinzugefügt", false),
    Name("Name", false),
    Size("Größte zuerst", false),
}

/** What the trunk library can be narrowed to. */
enum class TrunkFilter(val label: String) { Photos("Fotos"), Videos("Videos"), Favorites("Favoriten") }

/** The overview's tabs: the phone's photos, the trunk library, the phone's folders. */
enum class Tab(val label: String) { Phone("Handy"), Trunk("trunk"), Folders("Ordner") }

/** Shows photos from the trunk server, so actions go there. */
val Source?.onTrunk: Boolean get() = this == Source.Trunk || this is Source.TrunkAlbum || (this is Source.Memories && remote)

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
    /** Contents of shared albums, by album id: other people's photos are not in [trunk]. */
    var trunkShared by mutableStateOf<Map<Long, List<MediaImage>>>(emptyMap())
        private set

    private suspend fun loadShared() {
        trunkShared = TrunkLibrary.sharedAssets(getApplication()).mapValues { e -> e.value.map { it.toImage() } }
    }

    /** An album's photos in its order: ours from the library, others' from the shared contents. Trashed ones left out. */
    fun trunkAlbumImages(album: io.github.carnager.peek.trunk.RemoteAlbum): List<MediaImage> {
        val own = trunkByRemote
        val shared = trunkShared[album.id]?.associateBy { it.remote!!.id } ?: emptyMap()
        return album.assetIds.mapNotNull { own[it] ?: shared[it]?.takeIf { s -> !s.remote!!.trashed } }
    }

    private val trunkByRemote by androidx.compose.runtime.derivedStateOf { trunk.associateBy { it.remote!!.id } }

    /** Our account's id on trunk, to leave shared albums. */
    private var myTrunkId: Long? = null

    /** On this day in earlier years, from the phone's photos (Handy tab) and from trunk's (trunk tab). */
    val phoneMemories: OnThisDay? by androidx.compose.runtime.derivedStateOf { OnThisDay.of(images, java.time.LocalDate.now(), false) }
    val trunkMemories: OnThisDay? by androidx.compose.runtime.derivedStateOf {
        if (trunkConnected) OnThisDay.of(trunk, java.time.LocalDate.now(), true) else null
    }

    /** The overview's tab, remembered across launches. */
    var tab by mutableStateOf(runCatching { Tab.valueOf(prefs.getString("tab", Tab.Phone.name)!!) }.getOrDefault(Tab.Phone))
        private set

    fun selectTab(t: Tab) {
        tab = t
        prefs.edit().putString("tab", t.name).apply()
    }

    // Search, filters and order of the trunk library. All offline, on the cached library.
    var trunkQuery by mutableStateOf("")
    var trunkSort by mutableStateOf(TrunkSort.Newest)
    var trunkFilters by mutableStateOf(emptySet<TrunkFilter>())
    val trunkSearching get() = trunkQuery.isNotBlank() || trunkFilters.isNotEmpty()

    /**
     * The trunk library as shown: every word of the query must turn up somewhere (name, caption,
     * place, camera, year, month, or an album the photo is in), then the filters, then the order.
     */
    private val trunkView by androidx.compose.runtime.derivedStateOf { trunkShown() } // recomputed only when an input changes

    private fun trunkShown(): List<MediaImage> {
        val words = trunkQuery.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val albumsOf = if (words.isEmpty()) emptyMap() else HashMap<Long, StringBuilder>().also { m ->
            trunkAlbums.forEach { a -> a.assetIds.forEach { id -> m.getOrPut(id) { StringBuilder() }.append(' ').append(a.name.lowercase()) } }
        }
        val f = trunkFilters
        val list = trunk.filter { img ->
            val r = img.remote!!
            (TrunkFilter.Photos !in f || !r.isVideo) && (TrunkFilter.Videos !in f || r.isVideo) &&
                (TrunkFilter.Favorites !in f || r.favorite) &&
                words.all { w -> w in r.searchText || albumsOf[r.id]?.contains(w) == true }
        }
        return when (trunkSort) {
            TrunkSort.Newest -> list // the library comes newest first
            TrunkSort.Oldest -> list.reversed()
            TrunkSort.Added -> list.sortedByDescending { it.remote!!.createdAt }
            TrunkSort.Name -> list.sortedBy { it.name.lowercase() }
            TrunkSort.Size -> list.sortedByDescending { it.size }
        }
    }

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
            loadShared()
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
                loadShared()
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
        ownOnly(images).mapNotNull { it.remote }.forEach { TrunkLibrary.put(getApplication(), TrunkClient.setFavorite(it.id, favorite)) }
        trunk = TrunkLibrary.cached(getApplication()).map { it.toImage() }
    }

    // Albums on trunk. Images carry negative ids (see toImage); the server wants its own.

    private fun remoteIds(imageIds: Collection<Long>): List<Long> {
        val byId = (trunk + trunkShared.values.flatten()).associateBy { it.id }
        return imageIds.mapNotNull { byId[it]?.remote?.id }.distinct()
    }

    private suspend fun applyAlbum(o: org.json.JSONObject?, deletedId: Long? = null) {
        trunkAlbums = TrunkLibrary.putAlbum(getApplication(), o, deletedId)
        loadShared()
    }

    /** First photo of a trunk album, for its card. */
    fun trunkCoverOf(album: io.github.carnager.peek.trunk.RemoteAlbum): MediaImage? = trunkAlbumImages(album).firstOrNull()

    /** Only our own photos can be changed; others' in a shared album are just to look at. */
    private fun ownOnly(images: List<MediaImage>): List<MediaImage> {
        val (own, foreign) = images.partition { it.remote?.owner == null }
        if (foreign.isNotEmpty()) {
            val who = foreign.mapNotNull { it.remote?.owner }.distinct().joinToString(", ")
            message = Message(if (own.isEmpty()) "Gehört $who – nur ansehen" else "${foreign.size} von $who übersprungen")
        }
        return own
    }

    // Sharing within the household.

    suspend fun household(): List<Pair<Long, String>> = TrunkClient.users()

    fun trunkShare(albumId: Long, userId: Long, canAdd: Boolean) = trunkCall {
        applyAlbum(TrunkClient.setMember(albumId, userId, canAdd))
    }

    fun trunkUnshare(albumId: Long, userId: Long) = trunkCall {
        TrunkClient.removeMember(albumId, userId)
        syncTrunk()
    }

    /** Sends our own photos to someone in the household. */
    fun trunkSendTo(userId: Long, name: String, images: List<MediaImage>) = trunkCall {
        val ids = remoteIds(ownOnly(images).map { it.id })
        if (ids.isEmpty()) return@trunkCall
        applyAlbum(TrunkClient.sendTo(userId, ids))
        selection = emptySet()
        message = Message(if (ids.size == 1) "1 Bild an $name gesendet" else "${ids.size} Bilder an $name gesendet")
    }

    /** Makes a public link and returns its address; for an album, or for our own photos among [images]. */
    suspend fun trunkCreateLink(albumId: Long?, images: List<MediaImage>, title: String, stripLocation: Boolean, expiresDays: Int): String {
        val ids = if (albumId == null) remoteIds(ownOnly(images).map { it.id }) else null
        if (albumId == null && ids.isNullOrEmpty()) error("Keine eigenen Bilder ausgewählt")
        val o = TrunkClient.createLink(albumId, ids, title, stripLocation, expiresDays)
        return TrunkAccount.linkUrl(o.getString("path"))
    }

    /** Stops sending photos to someone: their album of what we sent goes away. */
    fun trunkEndDirect(albumId: Long) = trunkCall {
        TrunkClient.deleteAlbum(albumId)
        applyAlbum(null, deletedId = albumId)
        if (open == Source.TrunkAlbum(albumId)) open = null
    }

    /** Leaves an album someone else shares with us. */
    fun trunkLeave(albumId: Long) = trunkCall {
        val me = myTrunkId ?: TrunkClient.me().getLong("id").also { myTrunkId = it }
        val name = trunkAlbums.firstOrNull { it.id == albumId }?.name ?: ""
        TrunkClient.removeMember(albumId, me)
        applyAlbum(null, deletedId = albumId)
        if (open == Source.TrunkAlbum(albumId)) open = null
        message = Message("„$name“ verlassen")
    }

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

    fun trunkCaption(image: MediaImage, caption: String) = trunkCall {
        val id = image.remote?.id ?: return@trunkCall
        TrunkLibrary.put(getApplication(), TrunkClient.setCaption(id, caption))
        trunk = TrunkLibrary.cached(getApplication()).map { it.toImage() }
    }

    /** Back to the photo as first uploaded. */
    fun trunkRevertEdit(image: MediaImage) = trunkCall {
        val id = image.remote?.id ?: return@trunkCall
        TrunkLibrary.put(getApplication(), TrunkClient.revertEdit(id))
        trunk = TrunkLibrary.cached(getApplication()).map { it.toImage() }
        message = Message("Bearbeitung verworfen, Original wiederhergestellt")
    }

    fun trunkTrash(images: List<MediaImage>) = trunkCall {
        val ids = ownOnly(images).mapNotNull { it.remote?.id }
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
        Source.Trunk -> trunkView
        is Source.Memories -> (if (source.remote) trunkMemories else phoneMemories)?.images ?: emptyList()
        is Source.TrunkAlbum -> trunkAlbums.firstOrNull { it.id == source.id }?.let(::trunkAlbumImages) ?: emptyList()
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
        is Source.TrunkAlbum -> trunkAlbums.firstOrNull { it.id == source.id }?.title ?: ""
        is Source.Memories -> (if (source.remote) trunkMemories else phoneMemories)?.title ?: "Rückblick"
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
    var settingsOpen by mutableStateOf(false)
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
