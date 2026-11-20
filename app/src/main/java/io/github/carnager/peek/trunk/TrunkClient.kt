package io.github.carnager.peek.trunk

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** The server peek is connected to, remembered across launches. */
object TrunkAccount {
    private const val PREFS = "trunk"
    @Volatile var baseUrl: String? = null; private set
    @Volatile var token: String? = null; private set
    @Volatile var user: String? = null; private set

    /** Upload limit for photos in bytes; 0 sends originals. */
    @Volatile var uploadLimit: Long = 0; private set

    val connected get() = baseUrl != null && token != null

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, 0)
        baseUrl = p.getString("baseUrl", null)
        token = p.getString("token", null)
        user = p.getString("user", null)
        uploadLimit = p.getLong("uploadLimit", 0)
    }

    fun save(ctx: Context, url: String, user: String, token: String) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString("baseUrl", url).putString("user", user).putString("token", token).apply()
        load(ctx)
    }

    fun setUploadLimit(ctx: Context, bytes: Long) {
        ctx.getSharedPreferences(PREFS, 0).edit().putLong("uploadLimit", bytes).apply()
        uploadLimit = bytes
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, 0).edit().clear().apply()
        load(ctx)
    }

    fun api(path: String) = "${baseUrl}/api/v1$path"
    // The content hash in the URL changes when a photo is edited, so Coil's caches never show the old pixels.
    fun thumbUrl(a: RemoteAsset, size: String) = api("/assets/${a.id}/thumb/$size?v=${a.version}")
    /** The current file: the edit, if there is one. */
    fun fileUrl(a: RemoteAsset) = api("/assets/${a.id}/file?v=${a.version}")

    /** Full address of a public link from its "path". */
    fun linkUrl(path: String) = "$baseUrl$path"

    /** Whether a request goes to our server, so the token may be attached. */
    fun owns(url: String): Boolean = baseUrl?.let { url.startsWith("$it/") } ?: false
}

class TrunkException(val status: Int, message: String) : IOException(message)

/** Hex SHA-256, the hash trunk identifies files by. */
fun sha256(input: java.io.InputStream): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(1 shl 16)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun sha256(file: java.io.File): String = file.inputStream().use { sha256(it) }

/** One asset as the server describes it. See trunk's docs/api.md for the conventions. */
data class RemoteAsset(
    val id: Long,
    val name: String,
    val kind: String,
    val mime: String,
    val size: Long,
    /** The exact instant, for sorting. */
    val takenAt: Long,
    /** Wall clock where it was taken, for showing to people. */
    val localTime: String?,
    /** Display dimensions: orientation is already applied. */
    val width: Int,
    val height: Int,
    val lat: Double?,
    val lon: Double?,
    val alt: Double?,
    val make: String?,
    val model: String?,
    val lens: String?,
    val fNumber: Double?,
    val exposure: Double?,
    val iso: Int?,
    val focalLength: Double?,
    val durationMs: Long,
    val favorite: Boolean,
    val trashed: Boolean,
    /** Hash of the current file. */
    val sha256: String,
    /** Edited on trunk; the first upload is kept and can be restored. */
    val edited: Boolean,
    val updatedAt: String?,
    /** When it arrived on trunk (ms), for "recently added". */
    val createdAt: Long,
    /** Nearest town, in the phone's language; see trunk's docs/api.md. */
    val city: String?,
    val region: String?,
    val country: String?,
    val caption: String?,
    /** Set for someone else's photo, seen in a shared album: look, don't touch. */
    val owner: String?,
    val json: JSONObject,
) {
    /** "Wien, Österreich", or null without a known place. */
    val placeLine: String?
        get() = listOfNotNull(city, region, country).takeIf { it.isNotEmpty() }?.joinToString(", ")

    /** Everything a search can match, in lower case: name, caption, place, camera, year and month. */
    val searchText: String by lazy {
        val month = localTime?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }?.let { t ->
            "${t.year} ${t.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())} " +
                t.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        }
        listOfNotNull(name, caption, city, region, country, make, model, lens, month, if (isVideo) "video" else "foto")
            .joinToString(" ").lowercase()
    }

    val isVideo get() = kind == "video"

    /** Short tag for cache-busting URLs. */
    val version get() = sha256.take(12)

    /** [localTime] read as a time in the phone's zone, so date formatting shows the wall clock it was taken at. */
    val displayMillis: Long
        get() = localTime?.let { runCatching { LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() } ?: takenAt

    companion object {
        fun parse(o: JSONObject): RemoteAsset {
            fun s(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
            fun d(k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
            return RemoteAsset(
                id = o.getLong("id"),
                name = s("name") ?: "",
                kind = s("kind") ?: "image",
                mime = s("mime") ?: "image/jpeg",
                size = o.optLong("size"),
                takenAt = s("taken_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0,
                localTime = s("local_time"),
                width = o.optInt("width"),
                height = o.optInt("height"),
                lat = d("lat"), lon = d("lon"), alt = d("alt"),
                make = s("make"), model = s("model"), lens = s("lens"),
                fNumber = d("f_number"), exposure = d("exposure"),
                iso = if (o.has("iso")) o.optInt("iso") else null,
                focalLength = d("focal_length"),
                durationMs = o.optLong("duration_ms"),
                favorite = o.optBoolean("favorite"),
                trashed = s("trashed_at") != null,
                sha256 = s("sha256") ?: "",
                edited = s("edited_at") != null,
                updatedAt = s("updated_at"),
                createdAt = s("created_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0,
                city = o.optJSONObject("place")?.optString("city")?.ifEmpty { null },
                region = o.optJSONObject("place")?.optString("region")?.ifEmpty { null },
                country = o.optJSONObject("place")?.optString("country")?.ifEmpty { null },
                caption = s("caption"),
                owner = s("owner"),
                json = o,
            )
        }
    }
}

/** Someone an album is shared with. */
data class AlbumMember(val id: Long, val name: String, val canAdd: Boolean)

/** An album on the server: an ordered list of asset ids, perhaps shared within the household. */
data class RemoteAlbum(
    val id: Long,
    val seq: Long,
    val name: String,
    val assetIds: List<Long>,
    val createdAt: String,
    /** Set when someone else owns it and shares it with us. */
    val owner: String?,
    val members: List<AlbumMember>,
    val canAdd: Boolean,
    /** Photos one person sent another, rather than an album. */
    val direct: Boolean,
    val json: JSONObject,
) {
    val isOwner get() = owner == null

    /** How to call it: its name, or for sent photos "Von alice" / "An bob". */
    val title: String
        get() = when {
            !direct -> name
            owner != null -> "Von $owner"
            else -> "An $name"
        }
    /** Shared in either direction: its photos are not all ours, so they are fetched with the album. */
    val shared get() = owner != null || members.isNotEmpty()

    companion object {
        fun parse(o: JSONObject): RemoteAlbum {
            val ids = o.optJSONArray("asset_ids") ?: JSONArray()
            val ms = o.optJSONArray("members") ?: JSONArray()
            return RemoteAlbum(
                id = o.getLong("id"),
                seq = o.optLong("seq"),
                name = o.optString("name"),
                assetIds = List(ids.length()) { ids.getLong(it) },
                createdAt = o.optString("created_at"),
                owner = o.optJSONObject("owner")?.optString("name"),
                members = List(ms.length()) { i -> ms.getJSONObject(i).let { AlbumMember(it.getLong("id"), it.optString("name"), it.optBoolean("can_add")) } },
                canAdd = o.optString("role") != "member" || o.optBoolean("can_add"),
                direct = o.optString("kind") == "direct",
                json = o,
            )
        }
    }
}

/** What an upload did: created a new asset, or found the content already there. */
data class Sent(val created: Boolean, val assetId: Long?)

/** One page of the server's changes feed. */
class ChangesPage(val assets: JSONArray, val albums: JSONArray, val cursor: Long, val more: Boolean)

object TrunkClient {
    private val json = "application/json".toMediaType()

    /** Shared with Coil, so thumbnails and API calls reuse connections. Adds the token for our server only. */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder().header("User-Agent", "peek/${io.github.carnager.peek.BuildConfig.VERSION_NAME} (Android photo gallery)")
                    // Place names come back in the phone's language.
                    .header("Accept-Language", java.util.Locale.getDefault().toLanguageTag())
                val t = TrunkAccount.token
                if (t != null && TrunkAccount.owns(req.url.toString())) b.header("Authorization", "Bearer $t")
                chain.proceed(b.build())
            }
            .build()
    }

    private fun Response.bodyJson(): JSONObject {
        val text = body?.string().orEmpty()
        if (!isSuccessful) {
            val msg = runCatching { JSONObject(text).getString("error") }.getOrNull() ?: "HTTP $code"
            throw TrunkException(code, msg)
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    /** API calls give up after 30 s, so a dead connection ends in an error instead of a spinner forever. */
    private fun call(req: Request): JSONObject = http.newCall(req).apply { timeout().timeout(30, TimeUnit.SECONDS) }.execute().use { it.bodyJson() }

    /** Turns what people type ("192.168.0.13:8420", "photos.example.org/") into a base URL. */
    fun normalize(input: String): String {
        var u = input.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u.removeSuffix("/api/v1")
    }

    suspend fun serverInfo(base: String): JSONObject = withContext(Dispatchers.IO) {
        call(Request.Builder().url("$base/api/v1/server").build())
    }

    /** Logs in and stores the account. Tries https first; falls back to http only if the user typed no scheme. */
    suspend fun login(ctx: Context, address: String, user: String, password: String): String = withContext(Dispatchers.IO) {
        val typedScheme = address.trim().startsWith("http")
        var base = normalize(address)
        try {
            serverInfo(base)
        } catch (e: IOException) {
            if (typedScheme || e is TrunkException) throw e
            base = base.replaceFirst("https://", "http://")
            serverInfo(base)
        }
        val body = JSONObject().put("user", user).put("password", password).put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        val res = call(Request.Builder().url("$base/api/v1/auth/login").post(body.toString().toRequestBody(json)).build())
        TrunkAccount.save(ctx, base, user, res.getString("token"))
        base
    }

    suspend fun logout(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching { call(Request.Builder().url(TrunkAccount.api("/auth/logout")).post(ByteArray(0).toRequestBody()).build()) }
        TrunkAccount.clear(ctx)
    }

    /** One page of changes after [since]. */
    suspend fun changes(since: Long, limit: Int = 1000): ChangesPage = withContext(Dispatchers.IO) {
        val o = call(Request.Builder().url(TrunkAccount.api("/changes?since=$since&limit=$limit")).build())
        ChangesPage(o.optJSONArray("assets") ?: JSONArray(), o.optJSONArray("albums") ?: JSONArray(), o.optLong("cursor", since), o.optBoolean("more"))
    }

    // Albums. Each call returns the album as it is now (null after deleting).

    suspend fun createAlbum(name: String, assetIds: List<Long> = emptyList()): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", name).put("asset_ids", JSONArray(assetIds))
        call(Request.Builder().url(TrunkAccount.api("/albums")).post(body.toString().toRequestBody(json)).build())
    }

    suspend fun renameAlbum(id: Long, name: String): JSONObject = withContext(Dispatchers.IO) {
        call(Request.Builder().url(TrunkAccount.api("/albums/$id")).patch(JSONObject().put("name", name).toString().toRequestBody(json)).build())
    }

    /** Adds to the front, in the order given, like peek's own albums. */
    suspend fun addToAlbum(id: Long, assetIds: List<Long>): JSONObject = withContext(Dispatchers.IO) {
        call(Request.Builder().url(TrunkAccount.api("/albums/$id/add")).post(JSONObject().put("ids", JSONArray(assetIds)).toString().toRequestBody(json)).build())
    }

    suspend fun removeFromAlbum(id: Long, assetIds: Collection<Long>): JSONObject = withContext(Dispatchers.IO) {
        call(Request.Builder().url(TrunkAccount.api("/albums/$id/remove")).post(JSONObject().put("ids", JSONArray(assetIds)).toString().toRequestBody(json)).build())
    }

    suspend fun deleteAlbum(id: Long) = withContext(Dispatchers.IO) {
        call(Request.Builder().url(TrunkAccount.api("/albums/$id")).delete().build())
    }

    /** All assets of an album, also other people's in a shared one. */
    fun albumAssets(id: Long): JSONArray =
        call(Request.Builder().url(TrunkAccount.api("/albums/$id/assets")).build()).optJSONArray("assets") ?: JSONArray()

    /** The other accounts on the server, to share with: id to name. */
    suspend fun users(): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        val arr = call(Request.Builder().url(TrunkAccount.api("/users")).build()).optJSONArray("users") ?: JSONArray()
        List(arr.length()) { i -> arr.getJSONObject(i).let { it.getLong("id") to it.optString("name") } }
    }

    suspend fun setMember(albumId: Long, userId: Long, canAdd: Boolean): JSONObject = withContext(Dispatchers.IO) {
        call(Request.Builder().url(TrunkAccount.api("/albums/$albumId/members/$userId")).put(JSONObject().put("can_add", canAdd).toString().toRequestBody(json)).build())
    }

    /** Stops sharing with [userId]; with our own id, leaves the album. */
    suspend fun removeMember(albumId: Long, userId: Long) = withContext(Dispatchers.IO) {
        call(Request.Builder().url(TrunkAccount.api("/albums/$albumId/members/$userId")).delete().build())
    }

    suspend fun me(): JSONObject = withContext(Dispatchers.IO) { call(Request.Builder().url(TrunkAccount.api("/me")).build()) }

    /** Sends photos to someone in the household; returns the album of what we sent them. */
    suspend fun sendTo(userId: Long, assetIds: List<Long>): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("user_id", userId).put("asset_ids", JSONArray(assetIds))
        call(Request.Builder().url(TrunkAccount.api("/shares")).post(body.toString().toRequestBody(json)).build())
    }

    /** A public link to an album or to some photos. Its address is the server's plus "path". */
    suspend fun createLink(albumId: Long?, assetIds: List<Long>?, title: String, stripLocation: Boolean, expiresDays: Int): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("title", title).put("strip_location", stripLocation).put("expires_days", expiresDays)
        if (albumId != null) body.put("album_id", albumId) else body.put("asset_ids", JSONArray(assetIds))
        call(Request.Builder().url(TrunkAccount.api("/links")).post(body.toString().toRequestBody(json)).build())
    }

    suspend fun links(): JSONArray = withContext(Dispatchers.IO) {
        call(Request.Builder().url(TrunkAccount.api("/links")).build()).optJSONArray("links") ?: JSONArray()
    }

    suspend fun revokeLink(token: String) = withContext(Dispatchers.IO) {
        call(Request.Builder().url(TrunkAccount.api("/links/$token")).delete().build())
    }

    suspend fun setCaption(id: Long, caption: String): JSONObject = withContext(Dispatchers.IO) {
        call(Request.Builder().url(TrunkAccount.api("/assets/$id")).patch(JSONObject().put("caption", caption).toString().toRequestBody(json)).build())
    }

    suspend fun setFavorite(id: Long, favorite: Boolean): JSONObject = withContext(Dispatchers.IO) {
        call(Request.Builder().url(TrunkAccount.api("/assets/$id")).patch(JSONObject().put("favorite", favorite).toString().toRequestBody(json)).build())
    }

    private suspend fun idsCall(path: String, ids: Collection<Long>) = withContext(Dispatchers.IO) {
        call(Request.Builder().url(TrunkAccount.api(path)).post(JSONObject().put("ids", JSONArray(ids)).toString().toRequestBody(json)).build())
    }

    suspend fun trash(ids: Collection<Long>) = idsCall("/assets/trash", ids)
    suspend fun restore(ids: Collection<Long>) = idsCall("/assets/restore", ids)
    suspend fun deleteForever(ids: Collection<Long>) = idsCall("/assets/delete", ids)

    /** Which of these hashes the server already has. */
    fun check(hashes: Collection<String>): Map<String, Long> {
        val o = call(Request.Builder().url(TrunkAccount.api("/assets/check")).post(JSONObject().put("sha256", JSONArray(hashes)).toString().toRequestBody(json)).build())
        val ex = o.optJSONObject("existing") ?: return emptyMap()
        return ex.keys().asSequence().associateWith { ex.getLong(it) }
    }

    /**
     * Simple upload of a whole file. Returns whether the server created a new asset. [sourceSha256] is the
     * hash of the phone's file when [sha256] belongs to a shrunk copy of it.
     */
    fun upload(name: String, mtime: Long, sha256: String, mime: String, length: Long, sourceSha256: String? = null, write: (BufferedSink) -> Unit): Sent {
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) = write(sink)
        }
        val full = TrunkAccount.api("/assets").toHttpUrl().newBuilder()
            .addQueryParameter("filename", name)
            .addQueryParameter("mtime", mtime.toString())
            .apply { if (sourceSha256 != null) addQueryParameter("source_sha256", sourceSha256) }
            .build()
        val res = call(Request.Builder().url(full).header("X-Sha256", sha256).post(body).build())
        return Sent(res.optBoolean("created"), res.optJSONObject("asset")?.optLong("id")?.takeIf { it > 0 })
    }

    /** Stores [file] as the edited version of asset [id]; trunk keeps the first upload. Returns the updated asset. */
    suspend fun saveEdit(id: Long, file: java.io.File, sha256: String, mime: String): JSONObject = withContext(Dispatchers.IO) {
        saveEdit(id, sha256, mime, file.length()) { sink -> file.inputStream().use { it.copyTo(sink.outputStream()) } }
    }

    /** [saveEdit] with the content written by [write]. */
    fun saveEdit(id: Long, sha256: String, mime: String, length: Long, write: (BufferedSink) -> Unit): JSONObject {
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) = write(sink)
        }
        // Edits are uploaded whole; give them the time a big photo needs on a slow link.
        val req = Request.Builder().url(TrunkAccount.api("/assets/$id/edit")).header("X-Sha256", sha256).put(body).build()
        return http.newCall(req).apply { timeout().timeout(5, TimeUnit.MINUTES) }.execute().use { it.bodyJson() }
    }

    /** Drops the edit; the photo shows its first upload again. Returns the updated asset. */
    suspend fun revertEdit(id: Long): JSONObject = withContext(Dispatchers.IO) {
        call(Request.Builder().url(TrunkAccount.api("/assets/$id/edit")).delete().build())
    }

    /**
     * Resumable upload (tus 1.0) for videos and anything big: created once, then sent in chunks
     * so a dropped connection only costs the current chunk. [read] fills the buffer from [offset].
     */
    fun uploadResumable(
        name: String, mtime: Long, sha256: String, length: Long,
        chunk: Int = 8 shl 20,
        read: (offset: Long, buf: ByteArray) -> Int,
        onProgress: (Long) -> Unit,
    ): Sent {
        fun b64(s: String) = android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)
        val create = Request.Builder().url(TrunkAccount.api("/uploads"))
            .header("Tus-Resumable", "1.0.0")
            .header("Upload-Length", length.toString())
            .header("Upload-Metadata", "filename ${b64(name)},mtime ${b64(mtime.toString())},sha256 ${b64(sha256)}")
            .post(ByteArray(0).toRequestBody())
            .build()
        val location = http.newCall(create).execute().use { r ->
            if (!r.isSuccessful) r.bodyJson()
            r.header("Location") ?: throw TrunkException(r.code, "Server gab keine Upload-Adresse zurück")
        }
        val uploadUrl = if (location.startsWith("http")) location else TrunkAccount.baseUrl + location
        var offset = 0L
        val buf = ByteArray(chunk)
        var created = false
        var assetId: Long? = null
        var attempts = 0
        while (offset < length) {
            val n = read(offset, buf)
            val req = Request.Builder().url(uploadUrl)
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", offset.toString())
                .patch(buf.toRequestBody("application/offset+octet-stream".toMediaType(), 0, n))
                .build()
            try {
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) r.bodyJson()
                    offset = r.header("Upload-Offset")?.toLongOrNull() ?: (offset + n)
                    r.header("Trunk-Asset-Created")?.let { created = it == "true" }
                    r.header("Trunk-Asset-Id")?.toLongOrNull()?.let { assetId = it }
                }
                attempts = 0
                onProgress(offset)
            } catch (e: IOException) {
                if (e is TrunkException || ++attempts > 5) throw e
                Thread.sleep(2000L * attempts)
                // Ask where the server stands and continue from there.
                offset = http.newCall(Request.Builder().url(uploadUrl).header("Tus-Resumable", "1.0.0").head().build()).execute()
                    .use { it.header("Upload-Offset")?.toLongOrNull() ?: offset }
            }
        }
        return Sent(created, assetId)
    }
}
