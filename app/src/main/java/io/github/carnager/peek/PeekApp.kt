package io.github.carnager.peek

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.video.VideoFrameDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient

class PeekApp : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
                // OpenStreetMap's tile policy asks for a User-Agent that names the app.
                add(OkHttpNetworkFetcherFactory(callFactory = {
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            chain.proceed(chain.request().newBuilder().header("User-Agent", "peek/${BuildConfig.VERSION_NAME} (Android photo gallery)").build())
                        }
                        .build()
                }))
            }
            .build()
}
