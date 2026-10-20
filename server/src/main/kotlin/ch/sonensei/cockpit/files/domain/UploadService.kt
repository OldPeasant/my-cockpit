package ch.sonensei.cockpit.files.domain

import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.files.infra.ChunkLengthException
import ch.sonensei.cockpit.files.infra.ChunkStore
import ch.sonensei.cockpit.files.infra.UploadSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.util.unit.DataSize
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Upload limits (SPEC.md §8.2): [maxSize] is the largest file the drive accepts (`cockpit.files.max-size`, default
 * 5 GB), [chunkSize] the size of the chunks of a resumable upload (`cockpit.files.upload-chunk-size`, default 8 MB).
 */
@Component
class UploadLimits(
	@Value("\${cockpit.files.max-size:5GB}") maxSize: DataSize,
	@Value("\${cockpit.files.upload-chunk-size:8MB}") chunkSize: DataSize,
) {
	val maxSize: Long = maxSize.toBytes()
	val chunkSize: Int = Math.toIntExact(chunkSize.toBytes())

	init {
		require(this.maxSize > 0 && this.chunkSize > 0) { "cockpit.files sizes must be positive" }
	}
}

/** An upload in progress and the numbers of the chunks received so far. */
data class UploadStatus(
	val session: UploadSession,
	val receivedChunks: List<Int>,
)

/**
 * Resumable chunked uploads (SPEC.md §8.2), for files larger than a single request should carry: [create] declares
 * the file and its size, the client sends the chunks in any order ([putChunk], again after a failure), [status]
 * tells which chunks arrived (to resume), and [complete] assembles them into a file after checking its size and
 * SHA-256. Chunks are written without a database transaction, so slow clients never hold a pooled connection.
 *
 * Uploads not completed within [EXPIRY] are deleted (with their chunks) the next time an upload is created.
 */
@Service
class UploadService(
	private val sessions: UploadSessionRepository,
	private val chunks: ChunkStore,
	private val files: FilesService,
	private val limits: UploadLimits,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * Starts an upload of a file of [size] bytes named [name] into [folderId] (`null`: the root).
	 *
	 * @throws InvalidRequestException for an invalid name or a size outside `0..maxSize`
	 * @throws NotFoundException when the folder does not exist or is in the Trash
	 */
	@Transactional
	fun create(
		name: String,
		folderId: UUID?,
		mime: String?,
		size: Long,
	): UploadSession {
		val valid = FileNames.validate(name)
		if (size !in 0..limits.maxSize) throw InvalidRequestException("size", "must be between 0 and ${limits.maxSize} bytes")
		folderId?.let(files::folder)
		deleteExpired()
		val mimeType = mime?.trim()?.ifBlank { null } ?: FilesService.DEFAULT_MIME
		return sessions.save(UploadSession(valid, folderId, mimeType, size, limits.chunkSize))
	}

	/** @throws NotFoundException when the upload does not exist (completed, aborted or expired) */
	@Transactional(readOnly = true)
	fun status(id: UUID): UploadStatus {
		val session = session(id)
		return UploadStatus(session, chunks.received(id).filter { it < session.chunkCount })
	}

	/**
	 * Stores chunk [n] of the upload [id] from [content], replacing a chunk sent before.
	 *
	 * @throws NotFoundException when the upload does not exist
	 * @throws InvalidRequestException when [n] is not a chunk of the upload or [content] has the wrong length
	 */
	fun putChunk(
		id: UUID,
		n: Int,
		content: InputStream,
	) {
		val session = session(id)
		if (n !in 0 until session.chunkCount) {
			throw InvalidRequestException("n", "must be between 0 and ${session.chunkCount - 1}")
		}
		try {
			chunks.write(id, n, content, session.chunkLength(n))
		} catch (e: ChunkLengthException) {
			throw InvalidRequestException("body", "must have exactly ${e.expected} bytes")
		} catch (e: IOException) {
			// An abort or complete that deleted the chunk directory during the write ends the upload, not the server.
			if (sessions.existsById(id)) throw e
			deleteChunks(id)
			throw notFound()
		}
		// Complete or abort may have ended the upload (and deleted its chunks) while this chunk was written: nothing
		// else would ever remove the chunk then, since cleanup follows the upload_session rows.
		if (!sessions.existsById(id)) {
			deleteChunks(id)
			throw notFound()
		}
	}

	/**
	 * Assembles the chunks of upload [id] into a new file and ends the upload. When the content does not match [size]
	 * or [sha256] nothing is stored and the upload stays open (its chunks can be sent again).
	 *
	 * @throws NotFoundException when the upload, or its folder, does not exist (anymore)
	 * @throws ConflictException when chunks are missing (listed in the message)
	 * @throws InvalidRequestException when [sha256] is not a SHA-256 hex digest or the content does not match it
	 */
	@Transactional
	fun complete(
		id: UUID,
		sha256: String,
	): FileItem {
		val expectedHash = sha256.trim().lowercase()
		if (!SHA256.matches(expectedHash)) throw InvalidRequestException("sha256", "must be a hex SHA-256 digest")
		val session = sessions.lockById(id) ?: throw notFound()
		val missing = (0 until session.chunkCount) - chunks.received(id).toSet()
		if (missing.isNotEmpty()) throw ConflictException("Chunks missing: ${missing.joinToString(", ")}")
		val file =
			chunks.open(id, session.chunkCount).use {
				files.storeFile(session.folderId, session.name, session.mime, it, limits.maxSize)
			}
		// Rolling back (by throwing) also removes the blob that storeFile wrote.
		val errors = mutableListOf<FieldError>()
		if (file.size != session.size) errors += FieldError("size", "the chunks have ${file.size} bytes, not ${session.size}")
		if (file.sha256 != expectedHash) errors += FieldError("sha256", "does not match the uploaded content")
		if (errors.isNotEmpty()) throw InvalidRequestException(errors, "The uploaded content does not match")
		sessions.delete(session)
		afterCommit { deleteChunks(id) }
		return file
	}

	/** Ends the upload [id] without storing a file. @throws NotFoundException when it does not exist */
	@Transactional
	fun abort(id: UUID) {
		val session = sessions.lockById(id) ?: throw notFound()
		sessions.delete(session)
		afterCommit { deleteChunks(id) }
	}

	private fun deleteExpired() {
		val expired = sessions.findByCreatedAtBefore(clock.instant().minus(EXPIRY))
		if (expired.isEmpty()) return
		sessions.deleteAll(expired)
		val ids = expired.map { it.id }
		afterCommit { ids.forEach(::deleteChunks) }
	}

	private fun session(id: UUID): UploadSession = sessions.findById(id).orElseThrow(::notFound)

	private fun notFound() = NotFoundException("Upload not found")

	private fun deleteChunks(id: UUID) {
		try {
			chunks.delete(id)
		} catch (e: IOException) {
			log.warn("Could not delete the chunks of upload {}", id, e)
		}
	}

	private fun afterCommit(action: () -> Unit) {
		TransactionSynchronizationManager.registerSynchronization(
			object : TransactionSynchronization {
				override fun afterCommit() = action()
			},
		)
	}

	companion object {
		val EXPIRY: Duration = Duration.ofDays(7)
		private val SHA256 = Regex("[0-9a-f]{64}")
	}
}
