package io.github.carnager.peek.gallery

import android.net.Uri

/** Actions on media that need the system's consent; the activity runs the consent dialogs. */
interface MediaOps {
    fun trash(images: List<MediaImage>)
    fun restore(images: List<MediaImage>)
    fun deleteForever(images: List<MediaImage>)
    fun setFavorite(images: List<MediaImage>, favorite: Boolean)
    fun edit(uri: Uri)

    /** Opens a downloaded trunk original in the editor; the result goes back to trunk as a copy. */
    fun editTrunk(uri: Uri, asset: io.github.carnager.peek.trunk.RemoteAsset)

    /** Queues photos and videos for upload to trunk, asking for what the upload needs first. */
    fun uploadToTrunk(images: List<MediaImage>)

    /** Asks to show notifications (for the evening reminder); [then] learns whether it may. */
    fun requestNotifications(then: (Boolean) -> Unit)

    /** With "media management" granted, the above run without a confirmation dialog each time. */
    val canManageMedia: Boolean
    fun requestManageMedia()
}
