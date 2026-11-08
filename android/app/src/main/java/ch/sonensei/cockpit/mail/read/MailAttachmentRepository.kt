package ch.sonensei.cockpit.mail.read

import ch.sonensei.cockpit.core.network.generated.apis.FilesApi
import ch.sonensei.cockpit.core.network.generated.models.FilePatchRequest
import ch.sonensei.cockpit.core.network.generated.models.FileResponse
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.mail.list.mailBody
import ch.sonensei.cockpit.mail.list.mailCall
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The attachment endpoints of a mail: streamed download and "Save to Files" (`/Attachments/Mail`). */
interface MailAttachmentApi {
    @Streaming
    @GET("api/v1/mail/messages/{id}/attachments/{partId}")
    suspend fun download(
        @Path("id") id: String,
        @Path("partId") partId: String,
    ): Response<ResponseBody>

    @POST("api/v1/mail/messages/{id}/attachments/{partId}/save-to-files")
    suspend fun saveToFiles(
        @Path("id") id: String,
        @Path("partId") partId: String,
    ): Response<FileResponse>
}

@Module
@InstallIn(SingletonComponent::class)
object MailAttachmentModule {
    @Provides
    fun mailAttachmentApi(retrofit: Retrofit): MailAttachmentApi = retrofit.create(MailAttachmentApi::class.java)
}

/** Where "Save to Files" stored an attachment: the drive file's [name] and its folder ([folderId], `null`: the root). */
data class SavedAttachment(
    val fileId: UUID,
    val name: String,
    val folderId: UUID?,
)

/** The attachments of a mail on the server: download them to the device, or store them in the drive. */
@Singleton
class MailAttachmentRepository
    @Inject
    constructor(
        private val api: MailAttachmentApi,
        private val files: FilesApi,
    ) {
        /**
         * Streams attachment [partId] of message [id] into what [target] opens; `Failed(null)` when it opens nothing or
         * writing fails (a full disk is not a lost connection). Returns the bytes written.
         */
        suspend fun download(
            id: UUID,
            partId: String,
            target: () -> OutputStream?,
        ): MailResult<Long> =
            withContext(Dispatchers.IO) {
                var unwritable = false
                val result =
                    mailCall {
                        mailBody(api.download(id.toString(), partId)).use { body ->
                            val out =
                                target() ?: run {
                                    unwritable = true
                                    throw IOException("The target cannot be written")
                                }
                            out.use { copy(body.byteStream(), it) { unwritable = true } }
                        }
                    }
                if (unwritable) MailResult.Failed(null) else result
            }

        /**
         * Stores attachment [partId] of message [id] in the drive, linked to the mail. The server puts it into
         * `/Attachments/Mail`; with a [folderId] it is then moved there. A failed move keeps the stored file where it
         * is: the result then names that place, so the user is told where it actually is.
         */
        suspend fun saveToFiles(
            id: UUID,
            partId: String,
            folderId: UUID? = null,
        ): MailResult<SavedAttachment> =
            mailCall {
                val stored = mailBody(api.saveToFiles(id.toString(), partId))
                if (folderId == null || folderId == stored.folderId) return@mailCall stored.toSaved()
                val moved =
                    (
                        mailCall {
                            mailBody(files.updateFile(stored.id, FilePatchRequest(folderId = folderId), "\"${stored.version}\""))
                        } as? MailResult.Ok
                    )?.value
                (moved ?: stored).toSaved()
            }
    }

private fun FileResponse.toSaved() = SavedAttachment(id, name, folderId)

/** Copies [input] to [out]; a failed write calls [writeFailed] first. */
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
