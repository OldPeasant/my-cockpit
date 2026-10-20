package ch.sonensei.cockpit.capture.queue

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A capture not yet accepted by the server (SPEC.md §9.3); [id] is the client UUIDv7 sent to `POST /capture`. */
@Entity(tableName = "pending_capture")
data class PendingCaptureEntity(
    @PrimaryKey val id: String,
    val text: String,
    val notes: String?,
    val createdAtEpochMs: Long,
    /** Why the last upload attempt failed; null before the first failure. */
    val lastError: String?,
    val lastAttemptAtEpochMs: Long?,
    /** The server rejected the capture (4xx): retrying the same request cannot succeed, so the job skips it. */
    val rejected: Boolean = false,
)

/**
 * A file attached to a pending capture, copied into app storage at [localPath] so it survives the source going away.
 * [remoteFileId] is set once it is uploaded, so a retry does not upload it again.
 */
@Entity(
    tableName = "pending_capture_attachment",
    primaryKeys = ["captureId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PendingCaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["captureId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PendingAttachmentEntity(
    val captureId: String,
    val position: Int,
    val localPath: String,
    val name: String,
    val mime: String,
    val remoteFileId: String? = null,
)

/** A pending capture with its attachments in [PendingAttachmentEntity.position] order. */
data class PendingCapture(
    val capture: PendingCaptureEntity,
    val attachments: List<PendingAttachmentEntity>,
)

/** The newest error of the queue together with the number of pending captures, read as one query. */
data class QueueStatusRow(
    val pending: Int,
    val lastError: String?,
)

@Dao
abstract class CaptureQueueDao {
    @Transaction
    open suspend fun insert(
        capture: PendingCaptureEntity,
        attachments: List<PendingAttachmentEntity>,
    ) {
        insertCapture(capture)
        insertAttachments(attachments)
    }

    /**
     * Captures the job still has to send, oldest first, so the Inbox gets them in capture order. Captures of the same
     * millisecond are ordered by their ids, which [UuidV7] makes increase with every capture.
     */
    @Transaction
    open suspend fun sendable(): List<PendingCapture> = sendableCaptures().map { PendingCapture(it, attachments(it.id)) }

    @Query(
        "SELECT (SELECT COUNT(*) FROM pending_capture) AS pending, " +
            "(SELECT lastError FROM pending_capture WHERE lastError IS NOT NULL " +
            "ORDER BY lastAttemptAtEpochMs DESC LIMIT 1) AS lastError",
    )
    abstract fun observeStatus(): Flow<QueueStatusRow>

    @Query("SELECT * FROM pending_capture_attachment WHERE captureId = :captureId ORDER BY position")
    abstract suspend fun attachments(captureId: String): List<PendingAttachmentEntity>

    @Query("UPDATE pending_capture_attachment SET remoteFileId = :remoteFileId WHERE captureId = :captureId AND position = :position")
    abstract suspend fun markUploaded(
        captureId: String,
        position: Int,
        remoteFileId: String,
    )

    @Query("UPDATE pending_capture SET lastError = :error, lastAttemptAtEpochMs = :atEpochMs, rejected = :rejected WHERE id = :id")
    abstract suspend fun markFailed(
        id: String,
        error: String,
        atEpochMs: Long,
        rejected: Boolean,
    )

    /** Removes the capture; its attachment rows go with it (cascade). */
    @Query("DELETE FROM pending_capture WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Query("SELECT * FROM pending_capture WHERE rejected = 0 ORDER BY createdAtEpochMs, id")
    protected abstract suspend fun sendableCaptures(): List<PendingCaptureEntity>

    @Insert
    protected abstract suspend fun insertCapture(capture: PendingCaptureEntity)

    @Insert
    protected abstract suspend fun insertAttachments(attachments: List<PendingAttachmentEntity>)
}

/**
 * The offline capture queue. Unlike the GTD read cache it holds data that exists nowhere else yet, so it is never
 * dropped on a schema change: new versions need a migration.
 */
@Database(
    entities = [PendingCaptureEntity::class, PendingAttachmentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CaptureQueueDatabase : RoomDatabase() {
    abstract fun dao(): CaptureQueueDao

    companion object {
        const val NAME = "capture_queue.db"
    }
}
