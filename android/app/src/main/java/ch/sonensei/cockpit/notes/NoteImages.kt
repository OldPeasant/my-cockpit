package ch.sonensei.cockpit.notes

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import ch.sonensei.cockpit.core.network.PLACEHOLDER_BASE_URL
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the images of notes with the app's authenticated HTTP client (server address and access token). Images are
 * kept in memory only: a note's photos never land in a disk cache that outlives the session.
 */
@Singleton
class NoteImages
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

/** The address an image is loaded from: drive files through the authenticated content endpoint. */
fun ImageSource.url(): String =
    when (this) {
        is ImageSource.CockpitFile -> "${PLACEHOLDER_BASE_URL}api/v1/files/$fileId/content"
        is ImageSource.Web -> url
    }

/** Photos taken with the camera for a note: a file in the cache that only this app's FileProvider shares. */
object NotePhotoFiles {
    /** Matches the provider declared in the manifest. */
    fun authority(context: Context) = "${context.packageName}.notes.photos"

    /** A new, empty file for the camera to write to, and its content URI. */
    fun create(context: Context): Pair<File, Uri> {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File.createTempFile("photo-", ".jpg", dir)
        return file to FileProvider.getUriForFile(context, authority(context), file)
    }

    /**
     * Reads the picked or taken image at [uri]; `null` when it cannot be read or is larger than [MAX_BYTES] (the
     * server would refuse it anyway, and reading it would risk running out of memory).
     */
    suspend fun read(
        context: Context,
        uri: Uri,
    ): NotePhoto? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val name =
                runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull() ?: "photo.${mime.substringAfter('/').substringBefore('+')}"
            try {
                resolver.openInputStream(uri)?.use { input ->
                    val bytes = readAtMost(input, MAX_BYTES + 1)
                    if (bytes.isEmpty() || bytes.size > MAX_BYTES) null else NotePhoto(bytes, mime, name)
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

    /** Deletes the camera files of earlier photos. */
    fun clear(context: Context) {
        File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
    }

    /** Up to [limit] bytes of [input] (`InputStream.readNBytes` needs API 33). */
    internal fun readAtMost(
        input: InputStream,
        limit: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private const val DIR = "notes-photos"
    private const val BUFFER_SIZE = 64 * 1024
    const val MAX_BYTES = 25 * 1024 * 1024
}
