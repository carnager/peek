package io.github.carnager.peek.gallery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** An album the user put together in peek. The photos stay where they are; it only lists their ids. */
data class UserAlbum(
    val id: String,
    val name: String,
    /** MediaStore ids, most recently added first. */
    val ids: List<Long>,
    val created: Long,
)

/** Stores the user's albums as a small JSON file in app storage. */
object UserAlbums {
    private fun file(ctx: Context) = File(ctx.filesDir, "albums.json")

    fun load(ctx: Context): List<UserAlbum> = runCatching {
        val arr = JSONArray(file(ctx).readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val ids = o.getJSONArray("ids")
            UserAlbum(o.getString("id"), o.getString("name"), List(ids.length()) { ids.getLong(it) }, o.optLong("created"))
        }
    }.getOrDefault(emptyList())

    fun save(ctx: Context, albums: List<UserAlbum>) {
        val arr = JSONArray()
        albums.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("name", a.name)
                    .put("created", a.created)
                    .put("ids", JSONArray(a.ids))
            )
        }
        // Write then rename, so a crash mid-write cannot lose the albums.
        val tmp = File(ctx.filesDir, "albums.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }

    fun new(name: String) = UserAlbum(UUID.randomUUID().toString(), name.trim(), emptyList(), System.currentTimeMillis())
}
