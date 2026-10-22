package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.network.generated.apis.FilesApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.source
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Streaming
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.DateTimeException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The contents of a file, streamed: the generated `FilesApi.downloadFile` lacks `@Streaming`, so Retrofit would read
 * the whole file into memory first.
 */
interface FileContentApi {
    @Streaming
    @GET("api/v1/files/{id}/content")
    suspend fun content(
        @Path("id") id: UUID,
    ): Response<ResponseBody>
}

@Module
@InstallIn(SingletonComponent::class)
object FilesModule {
    @Provides
    fun fileContentApi(retrofit: Retrofit): FileContentApi = retrofit.create(FileContentApi::class.java)
}

/** The outcome of a call. */
sealed interface FilesResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : FilesResult<T>

    /** No connection (or the server is down). */
    data object Offline : FilesResult<Nothing>

    /** The server refused it; [status] is `null` for an unreadable answer or a device file that cannot be read or written. */
    data class Failed(
        val status: Int?,
    ) : FilesResult<Nothing>
}

/** A file picked on the device to upload; read only while it is sent. */
interface UploadSource {
    val name: String
    val mime: String

    /** Bytes when the provider tells. */
    val sizeBytes: Long?

    /** `null` when it can no longer be read. */
    fun open(): InputStream?
}

/** The drive on the server (SPEC.md §8.2): browse, download and upload. No local cache: every screen loads what it shows. */
@Singleton
class FilesRepository
    @Inject
    constructor(
        private val api: FilesApi,
        private val content: FileContentApi,
    ) {
        /** Everything in folder [folderId] (`null`: the drive root), folders first, in the server's order. */
        suspend fun list(folderId: UUID?): FilesResult<List<DriveEntry>> =
            call {
                val all = mutableListOf<DriveEntry>()
                var cursor: String? = null
                for (page in 1..MAX_PAGES) {
                    val response = body(api.listFolder(folderId = folderId, cursor = cursor, limit = PAGE_SIZE))
                    response.items.mapNotNullTo(all) { it.toEntry() }
                    cursor = response.nextCursor ?: break
                }
                all.distinctBy { it.id }
            }

        /**
         * Streams the contents of file [id] into what [target] opens; `Failed(null)` when it opens nothing or writing
         * fails. Returns the bytes written.
         */
        suspend fun download(
            id: UUID,
            target: () -> OutputStream?,
        ): FilesResult<Long> =
            withContext(Dispatchers.IO) {
                var unwritable = false
                val result =
                    call {
                        body(content.content(id)).use { body ->
                            val out =
                                target() ?: run {
                                    unwritable = true
                                    throw IOException("The target cannot be written")
                                }
                            out.use { copy(body.byteStream(), it) { unwritable = true } }
                        }
                    }
                if (unwritable) FilesResult.Failed(null) else result
            }

        /** Uploads [source] into folder [folderId] (`null`: the drive root); `Failed(413)` when it is too large. */
        suspend fun upload(
            folderId: UUID?,
            source: UploadSource,
        ): FilesResult<DriveEntry.File> {
            val body = UploadBody(source)
            val result =
                call {
                    val part = MultipartBody.Part.createFormData("file", source.name, body)
                    body(api.uploadFile(part, folderId = folderId, name = source.name)).toFile()
                }
            return if (body.unreadable) FilesResult.Failed(null) else result
        }

        private suspend fun <T> call(block: suspend () -> T): FilesResult<T> =
            try {
                FilesResult.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                FilesResult.Offline
            } catch (e: FilesHttpFailure) {
                if (e.status >= SERVER_ERROR) FilesResult.Offline else FilesResult.Failed(e.status)
            } catch (_: SerializationException) {
                FilesResult.Failed(null)
            } catch (_: IllegalArgumentException) {
                FilesResult.Failed(null)
            } catch (_: DateTimeException) {
                FilesResult.Failed(null)
            }

        private companion object {
            const val PAGE_SIZE = 200
            const val MAX_PAGES = 50
            const val SERVER_ERROR = 500
        }
    }

/** A non-2xx answer; its message carries only the status, never the body. */
private class FilesHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw FilesHttpFailure(response.code())
    return body
}

/** Copies [input] to [out]; a failed write (a full disk, say) calls [writeFailed] first, so it is not taken for a lost connection. */
private fun copy(
    input: InputStream,
    out: OutputStream,
    writeFailed: () -> Unit,
): Long {
    val buffer = ByteArray(BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return total
        try {
            out.write(buffer, 0, read)
        } catch (e: IOException) {
            writeFailed()
            throw e
        }
        total += read
    }
}

private const val BUFFER_SIZE = 64 * 1024

/** Streams the picked file when OkHttp writes the request, so a large file is never held in memory. */
private class UploadBody(
    private val source: UploadSource,
) : RequestBody() {
    override fun contentType(): MediaType? = source.mime.toMediaTypeOrNull()

    /** The file could not be opened: the failed request is not a connection problem. */
    @Volatile
    var unreadable = false
        private set

    // Unknown: a provider's size may be wrong, and a wrong length breaks the request.
    override fun contentLength(): Long = -1

    override fun writeTo(sink: BufferedSink) {
        val input =
            source.open() ?: run {
                unreadable = true
                throw IOException("The file can no longer be read")
            }
        input.source().use { sink.writeAll(it) }
    }
}
