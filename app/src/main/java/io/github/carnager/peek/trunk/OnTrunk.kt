package io.github.carnager.peek.trunk

import android.content.Context
import io.github.carnager.peek.gallery.MediaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Which photos on the phone trunk does not have yet: they join the trunk timeline marked "only on
 * phone" until uploaded.
 *
 * First by name and time, which is free and covers peek's uploads and Google Takeout imports alike
 * (both keep the file name; time zones may shift the time, so within half a day counts). What is
 * left is recognised by its SHA-256, as trunk deduplicates uploads: trunk knows every hash a photo
 * had, also of the original when peek uploaded a smaller copy. Hashes are kept, so a file is read
 * once.
 */
object OnTrunk {
    private const val CACHE = "trunk/hashes.json"
    private const val SAME_TIME_MS = 12 * 3600_000L

    private var hashes: MutableMap<String, String>? = null

    private fun key(img: MediaImage) = "${img.id}:${img.modified}:${img.size}"

    private fun load(ctx: Context): MutableMap<String, String> {
        hashes?.let { return it }
        val m = HashMap<String, String>()
        runCatching {
            val o = JSONObject(File(ctx.filesDir, CACHE).readText())
            o.keys().forEach { k -> m[k] = o.getString(k) }
        }
        return m.also { hashes = it }
    }

    private fun save(ctx: Context, m: Map<String, String>) {
        runCatching {
            val f = File(ctx.filesDir, CACHE).apply { parentFile?.mkdirs() }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject(m).toString())
            tmp.renameTo(f)
        }
    }

    /**
     * The ids of [local] photos trunk does not have, given its library [remote]. [early] gets a
     * first answer before any file is read: [surelyNew] among those the name did not match.
     */
    suspend fun missing(
        ctx: Context,
        local: List<MediaImage>,
        remote: List<RemoteAsset>,
        surelyNew: Set<Long> = emptySet(),
        early: (Set<Long>) -> Unit = {},
    ): Set<Long> = withContext(Dispatchers.IO) {
        val byName = HashMap<String, MutableList<Long>>()
        for (r in remote) byName.getOrPut(r.name.lowercase()) { ArrayList() } += r.takenAt
        val unsure = local.filter { img ->
            val times = byName[img.name.lowercase()]
            times == null || times.none { abs(it - img.dateTaken) < SAME_TIME_MS }
        }
        if (unsure.isEmpty()) return@withContext emptySet()
        early(unsure.mapNotNullTo(HashSet()) { it.id.takeIf { id -> id in surelyNew } })

        val cache = load(ctx)
        val unreadable = HashSet<Long>()
        var hashed = 0
        val shaOf = HashMap<Long, String>()
        for (img in unsure) {
            coroutineContext.ensureActive()
            val k = key(img)
            val sha = cache[k] ?: runCatching {
                Uploader.hash(ctx, Uploader.Item(img.uri, img.name, img.mimeType, img.size, img.dateTaken, img.isVideo))
            }.getOrNull()?.also {
                cache[k] = it
                // Saved along the way: an interrupted first run (a big camera folder) loses nothing.
                if (++hashed % 50 == 0) save(ctx, cache)
            }
            if (sha == null) {
                unreadable += img.id // shown as only on the phone rather than hidden
                continue
            }
            shaOf[img.id] = sha
        }
        // Keep the hashes of files still there; forget the rest.
        val ids = local.mapTo(HashSet()) { it.id }
        val before = cache.size
        cache.keys.retainAll { it.substringBefore(':').toLongOrNull() in ids }
        if (hashed > 0 || cache.size != before) save(ctx, cache)
        val known = HashSet<String>()
        for (chunk in shaOf.values.distinct().chunked(2000)) {
            known += TrunkClient.check(chunk).keys
        }
        shaOf.filterValues { it !in known }.keys + unreadable
    }
}
