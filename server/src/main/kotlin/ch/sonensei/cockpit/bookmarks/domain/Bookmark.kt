package ch.sonensei.cockpit.bookmarks.domain

import ch.sonensei.cockpit.core.persistence.SoftDeletableEntity
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.BatchSize
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Whether the page's title, description and favicon were fetched after the last save of the URL. */
enum class MetadataStatus { PENDING, FETCHED, FAILED }

/**
 * A bookmark (SPEC.md §8.4): a [url] with an optional [title], [description] and [favicon] (an absolute http(s) URL),
 * which the server fills from the page after a save ([metadataStatus]). [readLater] puts it into the read-later queue
 * until it is read ([readAt]). [snapshotFileId] is a readable copy of the page in Files.
 */
@Entity
@Table(name = "bookmark")
class Bookmark(
	@Column(name = "url", nullable = false)
	var url: String,
	id: UUID? = null,
) : SoftDeletableEntity(id) {
	@Column(name = "title")
	var title: String? = null

	@Column(name = "description")
	var description: String? = null

	@Column(name = "favicon")
	var favicon: String? = null

	@Column(name = "read_later", nullable = false)
	var readLater: Boolean = false

	@Column(name = "read_at")
	var readAt: Instant? = null

	@Column(name = "snapshot_file_id")
	var snapshotFileId: UUID? = null

	@Enumerated(EnumType.STRING)
	@Column(name = "metadata_status", nullable = false)
	var metadataStatus: MetadataStatus = MetadataStatus.PENDING

	// Batched so a page of bookmarks loads its tags in one query, not one per bookmark.
	@ElementCollection
	@BatchSize(size = 100)
	@CollectionTable(name = "bookmark_tag", joinColumns = [JoinColumn(name = "bookmark_id")])
	@Column(name = "tag", nullable = false)
	var tags: MutableSet<String> = mutableSetOf()

	/** The title, or else the host of the URL (the URL itself when it has none). */
	val displayTitle: String
		get() =
			title?.takeIf { it.isNotBlank() }
				?: runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotEmpty() }
				?: url
}
