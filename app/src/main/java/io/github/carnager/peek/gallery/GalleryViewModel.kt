package io.github.carnager.peek.gallery

import android.provider.MediaStore
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.carnager.peek.R
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
    /** trunk's favourites and trash. */
    data object TrunkFavorites : Source
    data object TrunkTrash : Source
    /** Photos from this day in earlier years; [remote] when they come from trunk. */
    data class Memories(val remote: Boolean) : Source
}

/**
 * "On this day": photos taken on today's date in earlier years, or, when there are none, in the
 * week around it. Most recent year first, each day in the order it happened.
 */
class OnThisDay(val images: List<MediaImage>, val yearsAgo: List<Int>, val wholeWeek: Boolean, val remote: Boolean) {
    /** "2 years ago today", "1, 3 and 5 years ago this week". */
    fun title(res: android.content.res.Resources): String {
        val n = yearsAgo
        return if (n.size == 1) {
            res.getQuantityString(if (wholeWeek) R.plurals.gallery_memories_week else R.plurals.gallery_memories_today, n[0], n[0])
        } else {
            res.getString(if (wholeWeek) R.string.gallery_memories_week_list else R.string.gallery_memories_today_list, n.dropLast(1).joinToString(", "), n.last())
        }
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
enum class TrunkSort(@StringRes val label: Int, val byDate: Boolean) {
    Newest(R.string.gallery_sort_newest, true),
    Oldest(R.string.gallery_sort_oldest, true),
    Added(R.string.gallery_sort_added, false),
    Name(R.string.gallery_sort_name, false),
    Size(R.string.gallery_sort_size, false),
}

/** What the trunk library can be narrowed to. */
enum class TrunkFilter(@StringRes val label: Int) { Photos(R.string.gallery_filter_photos), Videos(R.string.gallery_filter_videos), Favorites(R.string.gallery_filter_favorites) }

/** The overview's tabs: the phone's photos, the trunk library, the phone's folders. */
enum class Tab(@StringRes val label: Int) { Phone(R.string.gallery_tab_phone), Trunk(R.string.gallery_tab_trunk), Folders(R.string.gallery_tab_folders) }

/** Shows photos from the trunk server, so actions go there. */
val Source?.onTrunk: Boolean
    get() = this == Source.Trunk || this == Source.TrunkFavorites || this == Source.TrunkTrash || this is Source.TrunkAlbum ||
        (this is Source.Memories && remote)

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

    private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)
    private fun plural(@androidx.annotation.PluralsRes id: Int, n: Int, vararg args: Any): String = getApplication<Application>().resources.getQuantityString(id, n, *args)

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

    /** The whole library as cached, trashed photos included; [trunk] and [trunkTrash] split it. */
    private var trunkAll by mutableStateOf<List<MediaImage>>(emptyList())
    val trunk: List<MediaImage> by androidx.compose.runtime.derivedStateOf { trunkAll.filter { it.remote?.trashed != true } }
    val trunkFavorites: List<MediaImage> by androidx.compose.runtime.derivedStateOf { trunk.filter { it.isFavorite } }
    /** Most recently deleted first. */
    val trunkTrash: List<MediaImage> by androidx.compose.runtime.derivedStateOf {
        trunkAll.filter { it.remote?.trashed == true }.sortedByDescending { it.remote?.trashedAt ?: 0 }
    }
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
        selection = emptySet()
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
        expires = trashExpiresAt,
        remote = this,
    )

    /** Shows the cached library at once, then asks the server what changed. */
    fun openTrunk() {
        viewModelScope.launch {
            trunkAll = TrunkLibrary.cached(getApplication()).map { it.toImage() }
            trunkAlbums = TrunkLibrary.cachedAlbums(getApplication())
            loadShared()
            syncTrunk()
        }
    }

    /** Set when a sync is asked for while one runs: that one may have started before the change. */
    private var syncAgain = false

    fun syncTrunk() {
        if (!TrunkAccount.connected) return
        if (trunkSyncing) {
            syncAgain = true
            return
        }
        viewModelScope.launch {
            trunkSyncing = true
            try {
                trunkAll = TrunkLibrary.sync(getApplication()).map { it.toImage() }
                trunkAlbums = TrunkLibrary.cachedAlbums(getApplication())
                loadShared()
                if (open is Source.TrunkAlbum && trunkAlbums.none { it.id == (open as Source.TrunkAlbum).id }) {
                    open = null // deleted on another device
                    viewerIndex = null
                }
                viewerIndex?.let { i ->
                    if (shown.onTrunk) {
                        val n = openImages.size
                        viewerIndex = if (n == 0) null else i.coerceAtMost(n - 1)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("peek-trunk", "sync failed", e)
                message = Message(str(R.string.gallery_trunk_unreachable_detail, e.message ?: e.javaClass.simpleName))
            } finally {
                trunkSyncing = false
                if (syncAgain) {
                    syncAgain = false
                    syncTrunk()
                }
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
                done(if (e.status == 401) str(R.string.gallery_login_wrong) else e.message)
            } catch (e: Exception) {
                done(str(R.string.gallery_server_unreachable, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun trunkLogout() {
        viewModelScope.launch {
            TrunkClient.logout(getApplication())
            TrunkLibrary.clear(getApplication())
            io.github.carnager.peek.trunk.TrunkFiles.clear(getApplication())
            trunkConnected = false
            trunkAll = emptyList()
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
            message = Message(plural(R.plurals.gallery_loading_originals, remote.size, remote.size))
        }
        val uris = remote.map { io.github.carnager.peek.trunk.TrunkFiles.file(getApplication(), it) }
        then(uris)
    }

    fun trunkFavorite(images: List<MediaImage>, favorite: Boolean) = trunkCall {
        ownOnly(images).mapNotNull { it.remote }.forEach { TrunkLibrary.put(getApplication(), TrunkClient.setFavorite(it.id, favorite)) }
        trunkAll = TrunkLibrary.cached(getApplication()).map { it.toImage() }
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
            message = Message(if (own.isEmpty()) str(R.string.gallery_foreign_view_only, who) else str(R.string.gallery_foreign_skipped, foreign.size, who))
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
        message = Message(plural(R.plurals.gallery_sent_to, ids.size, ids.size, name))
    }

    /** Makes a public link and returns its address; for an album, or for our own photos among [images]. */
    suspend fun trunkCreateLink(albumId: Long?, images: List<MediaImage>, title: String, stripLocation: Boolean, expiresDays: Int): String {
        val ids = if (albumId == null) remoteIds(ownOnly(images).map { it.id }) else null
        if (albumId == null && ids.isNullOrEmpty()) error(str(R.string.gallery_no_own_selected))
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
        message = Message(str(R.string.gallery_left_album, name))
    }

    fun trunkCreateAlbum(name: String, imageIds: List<Long> = emptyList(), then: (Long) -> Unit = {}) = trunkCall {
        val o = TrunkClient.createAlbum(name.trim(), remoteIds(imageIds))
        applyAlbum(o)
        if (imageIds.isNotEmpty()) message = Message(str(R.string.gallery_added_to, imageIds.size, o.optString("name")))
        then(o.getLong("id"))
    }

    fun trunkAddTo(albumId: Long, imageIds: List<Long>) = trunkCall {
        val album = trunkAlbums.firstOrNull { it.id == albumId } ?: return@trunkCall
        val ids = remoteIds(imageIds)
        val fresh = ids.filter { it !in album.assetIds }
        if (fresh.isEmpty()) {
            message = Message(str(R.string.gallery_already_in, album.name))
            return@trunkCall
        }
        applyAlbum(TrunkClient.addToAlbum(albumId, fresh))
        message = Message(str(R.string.gallery_added_to, fresh.size, album.name))
    }

    fun trunkRemoveFrom(albumId: Long, imageIds: Collection<Long>) = trunkCall {
        val ids = remoteIds(imageIds)
        applyAlbum(TrunkClient.removeFromAlbum(albumId, ids))
        message = Message(str(R.string.gallery_removed_from_album, ids.size), str(R.string.gallery_undo)) {
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
        message = Message(str(R.string.gallery_album_deleted, album.name), str(R.string.gallery_undo)) {
            // The server forgets deleted albums; a new one with the same content takes its place.
            trunkCall { applyAlbum(TrunkClient.createAlbum(album.name, album.assetIds)) }
        }
    }

    fun trunkCaption(image: MediaImage, caption: String) = trunkCall {
        val id = image.remote?.id ?: return@trunkCall
        TrunkLibrary.put(getApplication(), TrunkClient.setCaption(id, caption))
        trunkAll = TrunkLibrary.cached(getApplication()).map { it.toImage() }
    }

    /** Back to the photo as first uploaded. */
    fun trunkRevertEdit(image: MediaImage) = trunkCall {
        val id = image.remote?.id ?: return@trunkCall
        TrunkLibrary.put(getApplication(), TrunkClient.revertEdit(id))
        trunkAll = TrunkLibrary.cached(getApplication()).map { it.toImage() }
        message = Message(str(R.string.gallery_edit_reverted))
    }

    fun trunkTrash(images: List<MediaImage>) = trunkCall {
        val ids = ownOnly(images).mapNotNull { it.remote?.id }
        if (ids.isEmpty()) return@trunkCall
        TrunkClient.trash(ids)
        selection = emptySet()
        syncTrunk()
        message = Message(plural(R.plurals.gallery_trunk_trashed, ids.size, ids.size), str(R.string.gallery_undo)) {
            trunkCall { TrunkClient.restore(ids); syncTrunk() }
        }
    }

    // --- clean-up: look-alikes on trunk ---

    /**
     * Photos that look alike: one picture in several files ("same"), a burst of shots ("burst"), or
     * a video that is only a copy of the video in a motion photo ("motion").
     */
    class SimilarGroup(val ids: List<Long>, val keep: Long, val kind: String) {
        val key get() = ids.joinToString(",")
    }

    var cleanupOpen by mutableStateOf(false)
    /** null while loading. */
    var similar by mutableStateOf<List<SimilarGroup>?>(null)
        private set
    /** Photos trunk has not fingerprinted yet. */
    var similarPending by mutableIntStateOf(0)
        private set
    var similarError by mutableStateOf<String?>(null)
        private set

    private val trunkById: Map<Long, MediaImage> by androidx.compose.runtime.derivedStateOf {
        trunk.associateBy { it.remote!!.id }
    }

    /** A group's photos as far as the library has them, newest first. */
    fun similarImages(g: SimilarGroup): List<MediaImage> = g.ids.mapNotNull { trunkById[it] }

    fun loadSimilar() {
        similar = null
        similarError = null
        viewModelScope.launch {
            try {
                val o = TrunkClient.similar()
                val arr = o.optJSONArray("groups") ?: org.json.JSONArray()
                similar = List(arr.length()) { i ->
                    val g = arr.getJSONObject(i)
                    val ids = g.getJSONArray("asset_ids")
                    SimilarGroup(List(ids.length()) { ids.getLong(it) }, g.optLong("keep"), g.optString("kind"))
                }
                similarPending = o.optInt("pending")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                similarError = if (e is io.github.carnager.peek.trunk.TrunkException && e.status == 404) {
                    str(R.string.gallery_cleanup_unsupported)
                } else {
                    str(R.string.gallery_trunk_unreachable_detail, e.message ?: e.javaClass.simpleName)
                }
                similar = emptyList()
            }
        }
    }

    /** Moves the photos marked in these groups to trunk's trash; the groups are done. */
    fun cleanUp(marked: Map<SimilarGroup, Set<Long>>) = trunkCall {
        val ids = marked.values.flatten()
        if (ids.isEmpty()) return@trunkCall
        TrunkClient.trash(ids)
        similar = similar?.filter { it !in marked.keys }
        syncTrunk()
        message = Message(plural(R.plurals.gallery_trunk_trashed, ids.size, ids.size), str(R.string.gallery_undo)) {
            trunkCall { TrunkClient.restore(ids); syncTrunk(); loadSimilar() }
        }
    }

    /** "Not the same": the group goes away for good. */
    fun notSimilar(g: SimilarGroup) = trunkCall {
        TrunkClient.similarIgnore(g.ids)
        similar = similar?.filter { it !== g }
    }

    /** Corrects date or place of photos on trunk; see [TrunkClient.adjust]. */
    fun trunkAdjust(images: List<MediaImage>, localTime: String?, shiftMinutes: Long?, placeId: Long?, clearPlace: Boolean) = trunkCall {
        val ids = ownOnly(images).mapNotNull { it.remote?.id }
        if (ids.isEmpty()) return@trunkCall
        val arr = TrunkClient.adjust(ids, localTime, shiftMinutes, placeId, clearPlace)
        for (i in 0 until arr.length()) TrunkLibrary.put(getApplication(), arr.getJSONObject(i))
        trunkAll = TrunkLibrary.cached(getApplication()).map { it.toImage() }
        selection = emptySet()
        message = Message(plural(R.plurals.gallery_adjusted, ids.size, ids.size))
    }

    /** Takes photos out of trunk's trash again. */
    fun trunkRestore(images: List<MediaImage>) = trunkCall {
        val ids = images.mapNotNull { it.remote?.id }
        if (ids.isEmpty()) return@trunkCall
        TrunkClient.restore(ids)
        selection = emptySet()
        syncTrunk()
        message = Message(plural(R.plurals.gallery_trunk_restored, ids.size, ids.size))
    }

    /** Deletes photos in trunk's trash for good, on the server. */
    fun trunkDeleteForever(images: List<MediaImage>) = trunkCall {
        val ids = images.mapNotNull { it.remote?.id }
        if (ids.isEmpty()) return@trunkCall
        TrunkClient.deleteForever(ids)
        selection = emptySet()
        syncTrunk()
        message = Message(plural(R.plurals.gallery_trunk_deleted, ids.size, ids.size))
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
        Source.TrunkFavorites -> trunkFavorites
        Source.TrunkTrash -> trunkTrash
        is Source.Memories -> (if (source.remote) trunkMemories else phoneMemories)?.images ?: emptyList()
        is Source.TrunkAlbum -> trunkAlbums.firstOrNull { it.id == source.id }?.let(::trunkAlbumImages) ?: emptyList()
    }

    /** What is on screen: an opened album or folder, else the photos of the tab (Handy: all, trunk: the library). */
    val shown: Source?
        get() = open ?: when (tab) {
            Tab.Phone -> Source.All
            Tab.Trunk -> if (trunkConnected) Source.Trunk else null
            Tab.Folders -> null
        }

    val openImages: List<MediaImage> get() = imagesOf(shown)

    fun titleOf(source: Source?): String = when (source) {
        null -> "peek"
        Source.All -> str(R.string.gallery_all_photos)
        Source.Favorites -> str(R.string.gallery_favorites)
        Source.Trash -> str(R.string.gallery_trash)
        is Source.Folder -> folders.firstOrNull { it.bucketId == source.bucketId }?.name ?: ""
        is Source.User -> userAlbums.firstOrNull { it.id == source.id }?.name ?: ""
        Source.Trunk -> "trunk"
        Source.TrunkFavorites -> str(R.string.gallery_favorites)
        Source.TrunkTrash -> str(R.string.gallery_trunk_trash)
        is Source.TrunkAlbum -> trunkAlbums.firstOrNull { it.id == source.id }?.title(getApplication()) ?: ""
        is Source.Memories -> (if (source.remote) trunkMemories else phoneMemories)?.title(getApplication<Application>().resources) ?: str(R.string.gallery_memories)
    }

    // Android registers a new or restored photo a moment after the camera or trash is done with
    // it, often after peek has already reloaded. So peek watches the media index and reloads
    // whenever it changes, like other galleries, a short while after the last change.
    private var changeJob: kotlinx.coroutines.Job? = null
    private val mediaObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (!loaded) return // nothing shown yet, or no access
            changeJob?.cancel()
            changeJob = viewModelScope.launch {
                kotlinx.coroutines.delay(400)
                reload()
            }
        }
    }

    init {
        app.contentResolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, mediaObserver)
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver)
        super.onCleared()
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
            message = Message(str(R.string.gallery_nothing_to_review))
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
        message = Message(if (fresh.isEmpty()) str(R.string.gallery_already_in, a.name) else str(R.string.gallery_added_to, fresh.size, a.name))
    }

    fun removeFrom(albumId: String, ids: Collection<Long>) {
        val set = ids.toHashSet()
        val before = userAlbums
        saveAlbums(userAlbums.map { if (it.id == albumId) it.copy(ids = it.ids.filterNot { id -> id in set }) else it })
        message = Message(str(R.string.gallery_removed_from_album, set.size), str(R.string.gallery_undo)) { saveAlbums(before) }
    }

    fun renameAlbum(albumId: String, name: String) {
        saveAlbums(userAlbums.map { if (it.id == albumId) it.copy(name = name.trim()) else it })
    }

    fun deleteAlbum(albumId: String) {
        val before = userAlbums
        val name = userAlbums.firstOrNull { it.id == albumId }?.name ?: ""
        saveAlbums(userAlbums.filterNot { it.id == albumId })
        if (open == Source.User(albumId)) open = null
        message = Message(str(R.string.gallery_album_deleted, name), str(R.string.gallery_undo)) { saveAlbums(before) }
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
