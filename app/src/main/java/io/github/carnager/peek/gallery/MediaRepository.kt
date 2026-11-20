package io.github.carnager.peek.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaImage(
    val id: Long,
    val uri: Uri,
    val name: String,
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String?,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val dateTaken: Long,
    /** Seconds; changes when the file is rewritten, so caches can tell versions apart. */
    val modified: Long,
    val isFavorite: Boolean = false,
    /** For trashed items: when the system deletes it for good (ms), else 0. */
    val expires: Long = 0,
    val isVideo: Boolean = false,
    /** Video length in ms. */
    val duration: Long = 0,
    /** Set for photos that live on the trunk server rather than on the phone. */
    val remote: io.github.carnager.peek.trunk.RemoteAsset? = null,
) {
    /** Taken by the screenshot function rather than the camera. */
    val isScreenshot: Boolean
        get() = relativePath?.contains("Screenshots", ignoreCase = true) == true || name.startsWith("Screenshot", ignoreCase = true)
}

data class Album(
    val bucketId: Long,
    val name: String,
    val cover: MediaImage,
    val count: Int,
    val latest: Long,
)

object MediaRepository {
    private val imageCollection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val videoCollection: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** All photos and videos, or with [trashed] only those in the system trash. */
    suspend fun loadImages(context: Context, trashed: Boolean = false): List<MediaImage> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
        // Sorted below: SQL puts rows without DATE_TAKEN (PNG screenshots, edited copies) last.
        val queryArgs = android.os.Bundle().apply {
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val result = ArrayList<MediaImage>()
        // Photos and videos live in separate collections; their ids share one table, so they stay unique.
        for ((collection, video) in listOf(imageCollection to false, videoCollection to true))
        context.contentResolver.query(collection, if (video) projection + MediaStore.Video.Media.DURATION else projection, queryArgs, null)?.use { c ->
            val durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val orientCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            val favCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
            val expCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                // DATE_ADDED survives an overwrite, so a replaced original keeps its place in the list.
                val taken = c.getLong(takenCol).takeIf { it > 0 }
                    ?: c.getLong(addedCol).takeIf { it > 0 }?.times(1000)
                    ?: (c.getLong(modCol) * 1000)
                result += MediaImage(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol) ?: "",
                    bucketId = c.getLong(bucketCol),
                    bucketName = c.getString(bucketNameCol) ?: "",
                    relativePath = c.getString(pathCol),
                    mimeType = c.getString(mimeCol) ?: "image/*",
                    // WIDTH/HEIGHT are the stored pixels; a 90° EXIF rotation swaps what is shown.
                    width = if (c.getInt(orientCol) % 180 == 90) c.getInt(hCol) else c.getInt(wCol),
                    height = if (c.getInt(orientCol) % 180 == 90) c.getInt(wCol) else c.getInt(hCol),
                    size = c.getLong(sizeCol),
                    dateTaken = taken,
                    modified = c.getLong(modCol),
                    isFavorite = c.getInt(favCol) == 1,
                    expires = c.getLong(expCol) * 1000,
                    isVideo = video,
                    duration = if (durCol >= 0) c.getLong(durCol) else 0,
                )
            }
        }
        result.sortWith(compareByDescending<MediaImage> { it.dateTaken }.thenByDescending { it.id })
        result
    }

    fun albumsOf(images: List<MediaImage>): List<Album> =
        images.groupBy { it.bucketId }.map { (bucket, list) ->
            // Images arrive newest first, so the first one is the cover.
            Album(bucket, list.first().bucketName.ifEmpty { "/" }, list.first(), list.size, list.first().dateTaken)
        }.sortedByDescending { it.latest }
}
