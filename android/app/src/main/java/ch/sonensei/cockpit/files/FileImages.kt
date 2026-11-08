package ch.sonensei.cockpit.files

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads drive images (list thumbnails, image previews) with the app's authenticated HTTP client (server address and
 * access token). Kept in memory only: the drive's images never land in a disk cache that outlives the session.
 */
@Singleton
class FileImages
    @Inject
    constructor(
        @ApplicationContext context: Context,
        client: OkHttpClient,
    ) {
        val loader: ImageLoader =
            ImageLoader
                .Builder(context)
                .diskCache(null)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
                .build()
    }
