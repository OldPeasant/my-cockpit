package ch.sonensei.cockpit.capture.queue

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the capture UI shows: captures not yet on the server and why the last attempt failed (null: no failure). */
data class CaptureQueueStatus(
    val pendingCount: Int,
    val lastError: String?,
)

/** A file to attach, as the user picked or shared it. */
data class CaptureAttachment(
    val uri: Uri,
    /** Overrides the name the content provider reports. */
    val name: String? = null,
)

/**
 * Offline-first capture (SPEC.md §9.3): [capture] always stores the capture locally first (attachments copied into app
 * storage) and then asks the [CaptureSyncScheduler] to send it once the network is up.
 */
@Singleton
class CaptureQueue internal constructor(
    private val dao: CaptureQueueDao,
    private val files: AttachmentStore,
    private val scheduler: CaptureSyncScheduler,
    private val clock: Clock,
) {
    @Inject
    constructor(
        dao: CaptureQueueDao,
        files: AttachmentStore,
        scheduler: CaptureSyncScheduler,
    ) : this(dao, files, scheduler, Clock.systemUTC())

    val status: Flow<CaptureQueueStatus> =
        dao
            .observeStatus()
            .map { CaptureQueueStatus(it.pending, it.lastError) }
            .distinctUntilChanged()

    /**
     * Queues a capture and returns its id. Throws [IOException] if an attachment cannot be read or copied; nothing is
     * queued then.
     */
    suspend fun capture(
        text: String,
        notes: String? = null,
        attachments: List<CaptureAttachment> = emptyList(),
    ): UUID {
        val now = clock.millis()
        val id = UuidV7.generate(now)
        val copies =
            try {
                attachments.mapIndexed { position, attachment ->
                    val copy = files.copy(id.toString(), position, attachment.uri)
                    PendingAttachmentEntity(
                        captureId = id.toString(),
                        position = position,
                        localPath = copy.file.absolutePath,
                        name = attachment.name ?: copy.name,
                        mime = copy.mime,
                    )
                }
            } catch (e: IOException) {
                files.delete(id.toString())
                throw e
            }
        try {
            dao.insert(
                PendingCaptureEntity(
                    id = id.toString(),
                    text = text,
                    notes = notes?.takeIf { it.isNotBlank() },
                    createdAtEpochMs = now,
                    lastError = null,
                    lastAttemptAtEpochMs = null,
                ),
                copies,
            )
        } catch (e: Exception) {
            files.delete(id.toString())
            throw e
        }
        scheduler.schedule()
        return id
    }
}

/** A local copy of an attachment. */
data class AttachmentCopy(
    val file: File,
    val name: String,
    val mime: String,
)

/** Copies of attachments under `files/capture_queue/<capture id>/`, owned by the queue until the capture is sent. */
@Singleton
class AttachmentStore internal constructor(
    private val root: File,
    private val resolver: ContentResolver,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(File(context.filesDir, DIRECTORY), context.contentResolver)

    suspend fun copy(
        captureId: String,
        position: Int,
        uri: Uri,
    ): AttachmentCopy =
        withContext(Dispatchers.IO) {
            val dir = File(root, captureId)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create attachment folder")
            val target = File(dir, position.toString())
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open attachment")
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            AttachmentCopy(target, displayName(uri) ?: "attachment-${position + 1}", resolver.getType(uri) ?: DEFAULT_MIME)
        }

    /** Deletes the copies of one capture; missing copies are fine. */
    suspend fun delete(captureId: String) {
        withContext(Dispatchers.IO) { File(root, captureId).deleteRecursively() }
    }

    private fun displayName(uri: Uri): String? =
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.lastPathSegment
        } else {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
        }?.takeIf { it.isNotBlank() }

    companion object {
        const val DIRECTORY = "capture_queue"
        const val DEFAULT_MIME = "application/octet-stream"
    }
}
