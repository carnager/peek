package io.github.carnager.peek.gallery

import android.net.Uri

/** Actions on media that need the system's consent; the activity runs the consent dialogs. */
interface MediaOps {
    fun trash(images: List<MediaImage>)
    fun restore(images: List<MediaImage>)
    fun deleteForever(images: List<MediaImage>)
    fun setFavorite(images: List<MediaImage>, favorite: Boolean)
    fun edit(uri: Uri)

    /** With "media management" granted, the above run without a confirmation dialog each time. */
    val canManageMedia: Boolean
    fun requestManageMedia()
}
