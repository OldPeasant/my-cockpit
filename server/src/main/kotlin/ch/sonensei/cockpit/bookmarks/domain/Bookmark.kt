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
import java.net.IDN
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
		protected set

	/**
	 * Counts the metadata fetches requested for this bookmark; a fetch result is stored only while it belongs to the
	 * latest request ([requestMetadata]), so an older fetch that finishes later cannot overwrite a newer one.
	 */
	@Column(name = "metadata_fetch", nullable = false)
	var metadataFetch: Long = 0
		protected set

	/** Starts a new metadata fetch: the status becomes `PENDING`; returns the number of the fetch ([metadataFetch]). */
	fun requestMetadata(): Long {
		metadataStatus = MetadataStatus.PENDING
		return ++metadataFetch
	}

	/** Stores the outcome of fetch number [fetch], the pending one: `FETCHED` or `FAILED`. */
	fun metadataFetched(
		fetch: Long,
		fetched: Boolean,
	) {
		check(fetch == metadataFetch && metadataStatus == MetadataStatus.PENDING) { "Not the pending metadata fetch" }
		metadataStatus = if (fetched) MetadataStatus.FETCHED else MetadataStatus.FAILED
	}

	// Batched so a page of bookmarks loads its tags in one query, not one per bookmark.
	@ElementCollection
	@BatchSize(size = 100)
	@CollectionTable(name = "bookmark_tag", joinColumns = [JoinColumn(name = "bookmark_id")])
	@Column(name = "tag", nullable = false)
	var tags: MutableSet<String> = mutableSetOf()

	/** The title, or else the host of the URL (Unicode form of an IDN host; the URL itself when it has none). */
	val displayTitle: String
		get() =
			title?.takeIf { it.isNotBlank() }
				?: runCatching { URI(url).host?.let(IDN::toUnicode)?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotEmpty() }
				?: url
}
