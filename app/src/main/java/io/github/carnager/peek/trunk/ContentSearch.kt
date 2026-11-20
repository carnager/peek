package io.github.carnager.peek.trunk

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.security.MessageDigest

/**
 * Search by what is in the photos ("Hund am Strand"). trunk keeps a vector per photo, made by
 * trunk-ai on a desktop; peek turns the search text into a vector with the matching text model,
 * downloaded once from trunk (about 135 MB), and asks trunk for the photos closest to it. The text
 * never leaves the phone and trunk; the model runs here, so search works whenever trunk is up.
 *
 * Mirrors trunk-ai's text side (ai/clip.go): BERT word pieces (cased), mean pooling, a 768→512
 * linear layer, unit length.
 */
object ContentSearch {
    /** What trunk offers: a model name and its files. */
    data class Offer(val model: String, val files: List<Triple<String, Long, String>>, val indexed: Int) {
        val size get() = files.sumOf { it.second }
    }

    private fun dir(ctx: Context, model: String) = File(ctx.filesDir, "ai/$model")

    /** trunk's model, or null when it has none (no trunk-ai run yet, or an older trunk). */
    suspend fun offer(): Offer? = withContext(Dispatchers.IO) {
        val res = runCatching {
            TrunkClient.http.newCall(Request.Builder().url(TrunkAccount.api("/ai/model")).build()).execute()
        }.getOrNull() ?: return@withContext null
        res.use { r ->
            if (!r.isSuccessful) return@withContext null
            val o = JSONObject(r.body?.string() ?: return@withContext null)
            val arr = o.optJSONArray("files") ?: JSONArray()
            Offer(
                o.getString("model"),
                List(arr.length()) { i -> arr.getJSONObject(i).let { Triple(it.getString("name"), it.getLong("size"), it.getString("sha256")) } },
                o.optInt("indexed"),
            )
        }
    }

    /** The model's files are all here, as trunk has them. */
    fun ready(ctx: Context, offer: Offer): Boolean {
        val d = dir(ctx, offer.model)
        val stamp = File(d, ".complete")
        return stamp.exists() && stamp.readText() == offer.files.joinToString(",") { it.third }
    }

    /** Downloads the model from trunk; [progress] gets bytes done and total. */
    suspend fun download(ctx: Context, offer: Offer, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val d = dir(ctx, offer.model).apply { mkdirs() }
        File(d, ".complete").delete()
        var done = 0L
        for ((name, size, sha) in offer.files) {
            val target = File(d, name)
            if (target.length() == size && hash(target) == sha) {
                done += size
                progress(done, offer.size)
                continue
            }
            val tmp = File(d, "$name.part")
            val req = Request.Builder().url(TrunkAccount.api("/ai/model/${offer.model}/$name")).build()
            TrunkClient.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("$name: HTTP ${r.code}")
                val md = MessageDigest.getInstance("SHA-256")
                r.body!!.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            if (done - last > (1 shl 20)) {
                                progress(done, offer.size)
                                last = done
                            }
                        }
                    }
                }
                if (md.digest().joinToString("") { "%02x".format(it) } != sha) {
                    tmp.delete()
                    throw java.io.IOException("$name: checksum does not match")
                }
            }
            tmp.renameTo(target)
        }
        File(d, ".complete").writeText(offer.files.joinToString(",") { it.third })
        progress(offer.size, offer.size)
    }

    /** Removes downloaded models, to free the space. */
    fun remove(ctx: Context) {
        close()
        File(ctx.filesDir, "ai").deleteRecursively()
    }

    fun downloadedBytes(ctx: Context): Long = File(ctx.filesDir, "ai").walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun hash(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // --- the encoder ---

    private class Encoder(val model: String, val session: OrtSession, val vocab: Map<String, Long>, val dense: FloatArray)

    private val lock = Mutex()
    private var encoder: Encoder? = null

    private fun load(ctx: Context, model: String): Encoder {
        encoder?.takeIf { it.model == model }?.let { return it }
        close()
        val d = dir(ctx, model)
        val vocab = HashMap<String, Long>(130_000)
        File(d, "vocab.txt").useLines { lines -> lines.forEachIndexed { i, w -> vocab[w] = i.toLong() } }
        val session = OrtEnvironment.getEnvironment().createSession(File(d, "text.onnx").absolutePath, OrtSession.SessionOptions())
        return Encoder(model, session, vocab, loadDense(File(d, "dense.safetensors"))).also { encoder = it }
    }

    /** Frees the model's memory; it is loaded again for the next search. */
    fun close() {
        encoder?.session?.close()
        encoder = null
    }

    private fun loadDense(f: File): FloatArray {
        val b = f.readBytes()
        val n = ByteBuffer.wrap(b, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long.toInt()
        val hdr = JSONObject(String(b, 8, n, Charsets.UTF_8)).getJSONObject("linear.weight")
        val off = hdr.getJSONArray("data_offsets")
        val buf = ByteBuffer.wrap(b, 8 + n + off.getInt(0), off.getInt(1) - off.getInt(0)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buf.remaining()).also { buf.get(it) }
    }

    private fun isPunct(c: Int): Boolean =
        (c in 33..47) || (c in 58..64) || (c in 91..96) || (c in 123..126) ||
            Character.getType(c).let {
                it == Character.CONNECTOR_PUNCTUATION.toInt() || it == Character.DASH_PUNCTUATION.toInt() ||
                    it == Character.START_PUNCTUATION.toInt() || it == Character.END_PUNCTUATION.toInt() ||
                    it == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || it == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                    it == Character.OTHER_PUNCTUATION.toInt()
            }

    private fun isCJK(c: Int): Boolean =
        (c in 0x4E00..0x9FFF) || (c in 0x3400..0x4DBF) || (c in 0x20000..0x2A6DF) || (c in 0x2A700..0x2CEAF) ||
            (c in 0xF900..0xFAFF) || (c in 0x2F800..0x2FA1F)

    /** BERT's tokenizer, cased: word pieces between [CLS] and [SEP], at most 128. */
    private fun ids(vocab: Map<String, Long>, text: String): LongArray {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            i += Character.charCount(c)
            when {
                c == 0 || c == 0xFFFD || (Character.isISOControl(c) && c != '\t'.code && c != '\n'.code && c != '\r'.code) -> {}
                Character.isWhitespace(c) || Character.isSpaceChar(c) -> sb.append(' ')
                isCJK(c) || isPunct(c) -> sb.append(' ').appendCodePoint(c).append(' ')
                else -> sb.appendCodePoint(c)
            }
        }
        val unk = vocab["[UNK]"]!!
        val out = ArrayList<Long>()
        out += vocab["[CLS]"]!!
        for (word in sb.split(' ').filter { it.isNotEmpty() }) {
            val cps = word.codePoints().toArray()
            if (cps.size > 100) {
                out += unk
                continue
            }
            val pieces = ArrayList<Long>()
            var start = 0
            var bad = false
            while (start < cps.size) {
                var end = cps.size
                var found = -1L
                while (end > start) {
                    val s = (if (start > 0) "##" else "") + String(cps, start, end - start)
                    val id = vocab[s]
                    if (id != null) {
                        found = id
                        break
                    }
                    end--
                }
                if (found < 0) {
                    bad = true
                    break
                }
                pieces += found
                start = end
            }
            if (bad) out += unk else out.addAll(pieces)
        }
        while (out.size > 127) out.removeAt(out.size - 1)
        out += vocab["[SEP]"]!!
        return out.toLongArray()
    }

    /** The search vector for [text]: 512 floats, unit length. */
    suspend fun vector(ctx: Context, model: String, text: String): FloatArray = withContext(Dispatchers.Default) {
        lock.withLock {
            val e = load(ctx, model)
            val ids = ids(e.vocab, text)
            val env = OrtEnvironment.getEnvironment()
            val shape = longArrayOf(1, ids.size.toLong())
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { t1 ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape).use { t2 ->
                    e.session.run(mapOf("input_ids" to t1, "attention_mask" to t2)).use { res ->
                        @Suppress("UNCHECKED_CAST")
                        val h = (res[0].value as Array<Array<FloatArray>>)[0]
                        val pooled = FloatArray(768)
                        for (tok in h) for (d in 0 until 768) pooled[d] += tok[d]
                        for (d in 0 until 768) pooled[d] /= h.size
                        val v = FloatArray(512) { o ->
                            var acc = 0f
                            val row = o * 768
                            for (j in 0 until 768) acc += e.dense[row + j] * pooled[j]
                            acc
                        }
                        val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
                        for (k in v.indices) v[k] /= norm
                        v
                    }
                }
            }
        }
    }

    /** Photos trunk finds closest to [text], best first: trunk id → score. */
    suspend fun search(ctx: Context, model: String, text: String): Map<Long, Float> {
        val v = vector(ctx, model, text)
        val bytes = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()
        val body = JSONObject().put("model", model).put("vec", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("limit", 300)
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url(TrunkAccount.api("/ai/search"))
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            TrunkClient.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext emptyMap()
                val arr = JSONObject(r.body!!.string()).optJSONArray("results") ?: JSONArray()
                LinkedHashMap<Long, Float>().apply {
                    for (i in 0 until arr.length()) arr.getJSONObject(i).let { put(it.getLong("id"), it.getDouble("score").toFloat()) }
                }
            }
        }
    }
}
