package io.github.carnager.peek.trunk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The server's library as last seen, kept in a JSON file so the trunk view opens instantly and
 * works offline. Kept current with delta sync: only what changed since the stored cursor is fetched.
 */
object TrunkLibrary {
    private const val CACHE_VERSION = 2
    private val lock = Mutex()
    private val byId = LinkedHashMap<Long, RemoteAsset>()
    private val albums = LinkedHashMap<Long, RemoteAlbum>()
    /** Contents of shared albums (other people's photos are not in our feed), with the album seq they are from. */
    private val shared = HashMap<Long, Pair<Long, List<RemoteAsset>>>()
    private var cursor = 0L
    private var owner: String? = null
    private var loaded = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "trunk").apply { mkdirs() }
    private fun file(ctx: Context) = File(dir(ctx), "library.json")

    /** Who the cache belongs to; a different server or account starts from scratch. */
    private fun currentOwner() = "${TrunkAccount.baseUrl}|${TrunkAccount.user}"

    private fun loadCache(ctx: Context) {
        if (loaded && owner == currentOwner()) return
        byId.clear()
        albums.clear()
        shared.clear()
        cursor = 0
        owner = currentOwner()
        loaded = true
        runCatching {
            val o = JSONObject(file(ctx).readText())
            // Older caches ignored albums but moved their cursor past them: sync from scratch once.
            if (o.optString("owner") != owner || o.optInt("version") < CACHE_VERSION) return
            cursor = o.optLong("cursor")
            val arr = o.getJSONArray("assets")
            for (i in 0 until arr.length()) RemoteAsset.parse(arr.getJSONObject(i)).let { byId[it.id] = it }
            val al = o.optJSONArray("albums") ?: JSONArray()
            for (i in 0 until al.length()) RemoteAlbum.parse(al.getJSONObject(i)).let { albums[it.id] = it }
            val sh = o.optJSONObject("shared") ?: JSONObject()
            for (k in sh.keys()) {
                val e = sh.getJSONObject(k)
                val list = e.getJSONArray("assets")
                shared[k.toLong()] = e.getLong("seq") to List(list.length()) { RemoteAsset.parse(list.getJSONObject(it)) }
            }
        }
    }

    private fun saveCache(ctx: Context) {
        val arr = JSONArray()
        byId.values.forEach { arr.put(it.json) }
        val al = JSONArray()
        albums.values.forEach { al.put(it.json) }
        val sh = JSONObject()
        shared.forEach { (id, e) -> sh.put(id.toString(), JSONObject().put("seq", e.first).put("assets", JSONArray().apply { e.second.forEach { put(it.json) } })) }
        val o = JSONObject().put("version", CACHE_VERSION).put("owner", owner).put("cursor", cursor).put("assets", arr).put("albums", al).put("shared", sh)
        val tmp = File(dir(ctx), "library.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file(ctx))
    }

    /** Everything not in the server's trash, newest first by the exact instant it was taken. */
    suspend fun cached(ctx: Context): List<RemoteAsset> = withContext(Dispatchers.IO) {
        lock.withLock {
            loadCache(ctx)
            snapshot()
        }
    }

    /** The server's albums, newest first. */
    suspend fun cachedAlbums(ctx: Context): List<RemoteAlbum> = withContext(Dispatchers.IO) {
        lock.withLock {
            loadCache(ctx)
            albumSnapshot()
        }
    }

    private fun albumSnapshot() = albums.values.sortedWith(compareByDescending<RemoteAlbum> { it.createdAt }.thenByDescending { it.id })

    /** Applies an album from an API response, or drops it when [deletedId] is given. */
    suspend fun putAlbum(ctx: Context, o: JSONObject?, deletedId: Long? = null): List<RemoteAlbum> = withContext(Dispatchers.IO) {
        lock.withLock {
            loadCache(ctx)
            if (o != null) RemoteAlbum.parse(o).let { albums[it.id] = it }
            if (deletedId != null) albums.remove(deletedId)
            runCatching { refreshShared() }
            saveCache(ctx)
            albumSnapshot()
        }
    }

    /** Photos of shared albums, by album id. */
    suspend fun sharedAssets(ctx: Context): Map<Long, List<RemoteAsset>> = withContext(Dispatchers.IO) {
        lock.withLock {
            loadCache(ctx)
            shared.mapValues { it.value.second }
        }
    }

    /** Fetches the contents of shared albums that changed since we last did; forgets the rest. */
    private fun refreshShared(): Boolean {
        var changed = shared.keys.retainAll { id -> albums[id]?.shared == true }
        for (a in albums.values) {
            if (!a.shared || shared[a.id]?.first == a.seq) continue
            val arr = TrunkClient.albumAssets(a.id)
            shared[a.id] = a.seq to List(arr.length()) { RemoteAsset.parse(arr.getJSONObject(it)) }
            changed = true
        }
        return changed
    }

    private fun snapshot() = byId.values.filter { !it.trashed }.sortedWith(compareByDescending<RemoteAsset> { it.takenAt }.thenByDescending { it.id })

    /** Fetches what changed since last time and returns the updated library. */
    suspend fun sync(ctx: Context): List<RemoteAsset> = withContext(Dispatchers.IO) {
        lock.withLock {
            loadCache(ctx)
            var more = true
            var changed = false
            while (more) {
                val page = TrunkClient.changes(cursor)
                for (i in 0 until page.assets.length()) {
                    val o = page.assets.getJSONObject(i)
                    val id = o.getLong("id")
                    if (o.optBoolean("deleted")) byId.remove(id) else byId[id] = RemoteAsset.parse(o)
                    changed = true
                }
                for (i in 0 until page.albums.length()) {
                    val o = page.albums.getJSONObject(i)
                    val id = o.getLong("id")
                    if (o.optBoolean("deleted")) albums.remove(id) else albums[id] = RemoteAlbum.parse(o)
                    changed = true
                }
                if (page.cursor != cursor) changed = true
                cursor = page.cursor
                more = page.more
            }
            if (refreshShared()) changed = true
            if (changed) saveCache(ctx)
            snapshot()
        }
    }

    /** Applies a single updated asset from a PATCH response, without waiting for the next sync. */
    suspend fun put(ctx: Context, o: JSONObject) = withContext(Dispatchers.IO) {
        lock.withLock {
            loadCache(ctx)
            RemoteAsset.parse(o).let { byId[it.id] = it }
            saveCache(ctx)
        }
    }

    suspend fun clear(ctx: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            byId.clear()
            albums.clear()
            shared.clear()
            cursor = 0
            loaded = false
            file(ctx).delete()
        }
    }
}
