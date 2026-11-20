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
    private val lock = Mutex()
    private val byId = LinkedHashMap<Long, RemoteAsset>()
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
        cursor = 0
        owner = currentOwner()
        loaded = true
        runCatching {
            val o = JSONObject(file(ctx).readText())
            if (o.optString("owner") != owner) return
            cursor = o.optLong("cursor")
            val arr = o.getJSONArray("assets")
            for (i in 0 until arr.length()) RemoteAsset.parse(arr.getJSONObject(i)).let { byId[it.id] = it }
        }
    }

    private fun saveCache(ctx: Context) {
        val arr = JSONArray()
        byId.values.forEach { arr.put(it.json) }
        val o = JSONObject().put("owner", owner).put("cursor", cursor).put("assets", arr)
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

    private fun snapshot() = byId.values.filter { !it.trashed }.sortedWith(compareByDescending<RemoteAsset> { it.takenAt }.thenByDescending { it.id })

    /** Fetches what changed since last time and returns the updated library. */
    suspend fun sync(ctx: Context): List<RemoteAsset> = withContext(Dispatchers.IO) {
        lock.withLock {
            loadCache(ctx)
            var more = true
            var changed = false
            while (more) {
                val (assets, next, hasMore) = TrunkClient.changes(cursor)
                for (i in 0 until assets.length()) {
                    val o = assets.getJSONObject(i)
                    val id = o.getLong("id")
                    if (o.optBoolean("deleted")) byId.remove(id) else byId[id] = RemoteAsset.parse(o)
                    changed = true
                }
                cursor = next
                more = hasMore
            }
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
            cursor = 0
            loaded = false
            file(ctx).delete()
        }
    }
}
