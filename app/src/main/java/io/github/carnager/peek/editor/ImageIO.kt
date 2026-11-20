package io.github.carnager.peek.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class SourceInfo(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val relativePath: String?,
    val isMediaStore: Boolean,
    val originalWidth: Int,
    val originalHeight: Int,
)

enum class SaveFormat(val mime: String, val ext: String, val compress: Bitmap.CompressFormat, val lossy: Boolean) {
    JPEG("image/jpeg", "jpg", Bitmap.CompressFormat.JPEG, true),
    PNG("image/png", "png", Bitmap.CompressFormat.PNG, false),
    WEBP("image/webp", "webp", Bitmap.CompressFormat.WEBP_LOSSY, true);

    companion object {
        fun forMime(mime: String): SaveFormat? = entries.firstOrNull { it.mime == mime }
    }
}

object ImageIO {

    /** The largest bitmap we edit, sized so a handful of undo steps fit in the heap. */
    fun maxPixels(): Long = (Runtime.getRuntime().maxMemory() / 4 / 8).coerceIn(4_000_000L, 40_000_000L)

    suspend fun load(context: Context, uri: Uri): Pair<Bitmap, SourceInfo> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name: String? = null
        var relPath: String? = null
        val isMediaStore = uri.authority == MediaStore.AUTHORITY
        runCatching {
            val cols = if (isMediaStore) arrayOf(OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH) else arrayOf(OpenableColumns.DISPLAY_NAME)
            resolver.query(uri, cols, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (isMediaStore) relPath = c.getString(1)
                }
            }
        }
        val mime = resolver.getType(uri) ?: "image/jpeg"
        var ow = 0
        var oh = 0
        val limit = maxPixels()
        // ImageDecoder applies the EXIF orientation itself, so the bitmap is always upright.
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            ow = info.size.width
            oh = info.size.height
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val pixels = info.size.width.toLong() * info.size.height
            if (pixels > limit) {
                val f = sqrt(limit.toDouble() / pixels)
                decoder.setTargetSize(
                    (info.size.width * f).roundToInt().coerceAtLeast(1),
                    (info.size.height * f).roundToInt().coerceAtLeast(1),
                )
            }
        }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        bitmap to SourceInfo(uri, name ?: "bild.jpg", mime, relPath, isMediaStore, ow, oh)
    }

    private val exifTags = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    private fun readExif(context: Context, uri: Uri): Map<String, String> = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            exifTags.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
        }
    }.getOrNull().orEmpty()

    /** Encodes into a cache file and carries over the source's EXIF data (for JPEG). */
    private fun encode(context: Context, bitmap: Bitmap, format: SaveFormat, quality: Int, exif: Map<String, String>): File {
        val tmp = File.createTempFile("peek_", ".${format.ext}", context.cacheDir)
        tmp.outputStream().buffered().use { bitmap.compress(format.compress, if (format.lossy) quality else 100, it) }
        if (format == SaveFormat.JPEG) {
            runCatching {
                val out = ExifInterface(tmp)
                exif.forEach { (k, v) -> out.setAttribute(k, v) }
                out.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                out.setAttribute(ExifInterface.TAG_SOFTWARE, "peek")
                out.saveAttributes()
            }
        }
        return tmp
    }

    /** Writes a new image next to the original (or into Pictures/peek if that folder is not writable). */
    suspend fun saveCopy(context: Context, bitmap: Bitmap, source: SourceInfo, format: SaveFormat, quality: Int): Uri = withContext(Dispatchers.IO) {
        val exif = readExif(context, source.uri)
        val tmp = encode(context, bitmap, format, quality, exif)
        try {
            val base = source.displayName.substringBeforeLast('.').ifBlank { "bild" }
            val name = "${base}_edit.${format.ext}"
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val candidates = listOfNotNull(
                source.relativePath,
                "${Environment.DIRECTORY_PICTURES}/peek/",
            ).distinct()
            var lastError: Exception? = null
            for (path in candidates) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.Images.Media.WIDTH, bitmap.width)
                    put(MediaStore.Images.Media.HEIGHT, bitmap.height)
                }
                val uri = try {
                    resolver.insert(collection, values)
                } catch (e: Exception) {
                    // Scoped storage only lets us create images under DCIM/ and Pictures/.
                    lastError = e
                    null
                } ?: continue
                try {
                    resolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    return@withContext uri
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    lastError = e
                }
            }
            throw lastError ?: IllegalStateException("Speichern fehlgeschlagen")
        } finally {
            tmp.delete()
        }
    }

    /** Replaces the original's bytes. The caller must already hold write access to [source]. */
    suspend fun overwrite(context: Context, bitmap: Bitmap, source: SourceInfo, quality: Int) = withContext(Dispatchers.IO) {
        val format = SaveFormat.forMime(source.mimeType) ?: error("Format ${source.mimeType} kann nicht geschrieben werden")
        val exif = readExif(context, source.uri)
        val tmp = encode(context, bitmap, format, quality, exif)
        try {
            context.contentResolver.openOutputStream(source.uri, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            if (source.isMediaStore) {
                runCatching {
                    context.contentResolver.update(source.uri, ContentValues().apply {
                        put(MediaStore.Images.Media.WIDTH, bitmap.width)
                        put(MediaStore.Images.Media.HEIGHT, bitmap.height)
                    }, null, null)
                }
            }
        } finally {
            tmp.delete()
        }
    }

    fun originalSize(source: SourceInfo): Size = Size(max(source.originalWidth, 1), max(source.originalHeight, 1))
}
