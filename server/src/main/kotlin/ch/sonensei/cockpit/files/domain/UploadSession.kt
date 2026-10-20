package ch.sonensei.cockpit.files.domain

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * A resumable chunked upload in progress (SPEC.md §8.2): a file of [size] bytes named [name], to be stored in
 * [folderId] (`null`: the root), sent as [chunkCount] chunks of [chunkSize] bytes (the last one may be shorter).
 * The chunks themselves are on disk ([ch.sonensei.cockpit.files.infra.ChunkStore]).
 */
@Entity
@Table(name = "upload_session")
class UploadSession(
	@Column(name = "name", nullable = false, updatable = false)
	val name: String,
	@Column(name = "folder_id", updatable = false)
	val folderId: UUID?,
	@Column(name = "mime", nullable = false, updatable = false)
	val mime: String,
	@Column(name = "size", nullable = false, updatable = false)
	val size: Long,
	@Column(name = "chunk_size", nullable = false, updatable = false)
	val chunkSize: Int,
	id: UUID? = null,
) : BaseEntity(id) {
	val chunkCount: Int get() = ((size + chunkSize - 1) / chunkSize).toInt()

	/** The exact length chunk [n] must have: [chunkSize], except for the last chunk, which holds the rest. */
	fun chunkLength(n: Int): Long {
		require(n in 0 until chunkCount) { "No such chunk" }
		return minOf(chunkSize.toLong(), size - n.toLong() * chunkSize)
	}
}
