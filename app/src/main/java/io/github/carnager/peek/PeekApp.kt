package io.github.carnager.peek

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.video.VideoFrameDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.carnager.peek.trunk.TrunkAccount
import io.github.carnager.peek.trunk.TrunkClient
import io.github.carnager.peek.trunk.UploadWorker

class PeekApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        TrunkAccount.load(this)
        UploadWorker.createChannel(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
                // OpenStreetMap's tile policy asks for a User-Agent that names the app.
                // One client for map tiles and trunk: it names the app (OpenStreetMap's tile policy
                // asks for that) and adds the trunk token, but only for requests to that server.
                add(OkHttpNetworkFetcherFactory(callFactory = { TrunkClient.http }))
            }
            .build()
}
