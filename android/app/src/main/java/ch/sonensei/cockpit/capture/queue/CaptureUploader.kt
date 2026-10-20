package ch.sonensei.cockpit.capture.queue

import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.generated.apis.CaptureApi
import ch.sonensei.cockpit.core.network.generated.apis.FilesApi
import ch.sonensei.cockpit.core.network.generated.models.CaptureRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

/** Outcome of one pass over the queue. */
enum class SendResult {
    /** Nothing left that a retry could send (the queue is empty or only holds rejected captures). */
    DONE,

    /** The server could not be reached, failed or the device is logged out: try again later. */
    RETRY,
}

/**
 * Sends the queued captures: for each one (oldest first) uploads its attachments in order, remembering every file id
 * so a retry does not upload it twice, then calls `POST /capture` with the client id. The capture is idempotent, so a
 * repeated call after a lost answer returns the existing task (200) and counts as sent, as does 409 (the task was sent
 * before and is in the Trash by now). A sent capture is removed together with its attachment copies.
 */
class CaptureUploader internal constructor(
    private val session: AuthSession,
    private val dao: CaptureQueueDao,
    private val files: AttachmentStore,
    private val captureApi: CaptureApi,
    private val filesApi: FilesApi,
    private val clock: Clock,
) {
    @Inject
    constructor(
        session: AuthSession,
        dao: CaptureQueueDao,
        files: AttachmentStore,
        captureApi: CaptureApi,
        filesApi: FilesApi,
    ) : this(session, dao, files, captureApi, filesApi, Clock.systemUTC())

    suspend fun sendAll(): SendResult {
        for (pending in dao.sendable()) {
            val id = pending.capture.id
            val outcome = if (session.isLoggedIn) send(pending) else Outcome.Retry(NOT_LOGGED_IN)
            when (outcome) {
                Outcome.Sent -> {
                    dao.delete(id)
                    files.delete(id)
                }
                is Outcome.Rejected -> dao.markFailed(id, outcome.error, clock.millis(), rejected = true)
                is Outcome.Retry -> {
                    // The server is down, unreachable or the device logged out: the later captures would fail the same way.
                    dao.markFailed(id, outcome.error, clock.millis(), rejected = false)
                    return SendResult.RETRY
                }
            }
        }
        return SendResult.DONE
    }

    private suspend fun send(pending: PendingCapture): Outcome {
        val fileIds = mutableListOf<UUID>()
        for (attachment in pending.attachments) {
            if (attachment.remoteFileId != null) {
                fileIds += UUID.fromString(attachment.remoteFileId)
                continue
            }
            val file = File(attachment.localPath)
            if (!file.isFile) return Outcome.Rejected(ATTACHMENT_MISSING)
            val part = MultipartBody.Part.createFormData("file", attachment.name, file.asRequestBody(attachment.mime.toMediaTypeOrNull()))
            val response =
                try {
                    filesApi.uploadFile(part, name = attachment.name)
                } catch (_: IOException) {
                    return Outcome.Retry(OFFLINE)
                }
            if (!response.isSuccessful) return failure(response.code())
            val fileId = response.body()?.id ?: return Outcome.Retry(serverError(response.code()))
            dao.markUploaded(attachment.captureId, attachment.position, fileId.toString())
            fileIds += fileId
        }
        val request =
            CaptureRequest(
                text = pending.capture.text,
                id = UUID.fromString(pending.capture.id),
                notes = pending.capture.notes,
                fileIds = fileIds.takeIf { it.isNotEmpty() },
            )
        val code =
            try {
                captureApi.capture(request).code()
            } catch (_: IOException) {
                return Outcome.Retry(OFFLINE)
            }
        return if (code in 200..299 || code == HTTP_CONFLICT) Outcome.Sent else failure(code)
    }

    private fun failure(code: Int): Outcome =
        when {
            code == HTTP_UNAUTHORIZED -> Outcome.Retry(NOT_LOGGED_IN)
            code == HTTP_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS || code >= HTTP_SERVER_ERROR -> Outcome.Retry(serverError(code))
            else -> Outcome.Rejected("Rejected by the server (HTTP $code)")
        }

    private fun serverError(code: Int) = "Server error (HTTP $code)"

    private sealed interface Outcome {
        data object Sent : Outcome

        /** Retrying the same request cannot succeed. */
        data class Rejected(
            val error: String,
        ) : Outcome

        data class Retry(
            val error: String,
        ) : Outcome
    }

    companion object {
        const val OFFLINE = "Server unreachable"
        const val NOT_LOGGED_IN = "Not logged in"
        const val ATTACHMENT_MISSING = "Attachment missing"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TIMEOUT = 408
        private const val HTTP_CONFLICT = 409
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
    }
}
