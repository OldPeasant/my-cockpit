package ch.sonensei.cockpit.bookmarks.domain

import ch.sonensei.cockpit.bookmarks.infra.BookmarkRepository
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.storableInstant
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.web.CursorPage
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.Predicate
import org.hibernate.Hibernate
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.URISyntaxException
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Writes the search document of [bookmark]: its display title, and its URL, description and tags as the body. The one
 * place that decides what a bookmark's search document holds, used by [BookmarkService] and the Trash restore.
 */
fun SearchIndexer.indexBookmark(bookmark: Bookmark) {
	val body =
		listOfNotNull(
			bookmark.url,
			bookmark.description,
			bookmark.tags
				.sorted()
				.joinToString(" ")
				.ifEmpty { null },
		)
	upsert(ItemType.BOOKMARK, bookmark.id, bookmark.displayTitle, body.joinToString("\n"))
}

/**
 * Bookmarks and read-later (SPEC.md §8.4): CRUD, tags, the read-later queue and read state. Every change re-indexes
 * the bookmark for search and publishes a [ch.sonensei.cockpit.core.events.domain.ChangeEvent]; a deleted bookmark goes
 * to the Trash ([BookmarkTrashContributor][ch.sonensei.cockpit.bookmarks.infra.BookmarkTrashContributor]) and leaves
 * the search index.
 *
 * Saving a new URL (create, or a change of the URL) sets the [Bookmark.metadataStatus] to `PENDING` and publishes a
 * [BookmarkMetadataRequested]; after the commit the page is fetched in the background
 * ([BookmarkMetadataWorker][ch.sonensei.cockpit.bookmarks.infra.BookmarkMetadataWorker]) and [applyMetadata] fills the
 * title, description and favicon the user left empty. [refreshMetadata] fetches again and replaces them.
 *
 * Returned bookmarks have their tags loaded. Methods that change a bookmark take a `checkVersion` callback that sees the
 * current version before anything changes (If-Match).
 */
@Service
@Transactional
class BookmarkService(
	private val bookmarks: BookmarkRepository,
	private val indexer: SearchIndexer,
	private val changes: ChangeEventPublisher,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
) {
	/**
	 * One page of bookmarks matching [filter], newest first (then by id).
	 *
	 * @throws InvalidRequestException (400) for a tag filter with NUL characters
	 */
	@Transactional(readOnly = true)
	fun list(
		filter: BookmarkFilter,
		request: CursorRequest,
	): CursorPage<Bookmark> {
		// PostgreSQL rejects NUL in text values, so the filter would otherwise fail with a 500.
		if (filter.tag?.contains('\u0000') == true) throw InvalidRequestException("tag", "must not contain NUL characters")
		val after = request.position?.let(::parsePosition)
		val sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
		val fetched =
			bookmarks.findBy<Bookmark, List<Bookmark>>(specification(filter, after)) { query ->
				query.sortBy(sort).limit(request.limit + 1).all()
			}
		fetched.forEach { Hibernate.initialize(it.tags) }
		return request.page(fetched, ::positionOf)
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): Bookmark = load(id)

	/** Every tag in use (on bookmarks not in the Trash) with its number of bookmarks, ordered regardless of case. */
	@Transactional(readOnly = true)
	fun tags(): List<BookmarkTag> = bookmarks.countTags().map { BookmarkTag(it.tag, it.count) }

	/**
	 * Creates a bookmark of [BookmarkChanges.url] (required) and requests its page metadata. Other fields not given get
	 * their defaults (no tags, not in the read-later list, unread).
	 *
	 * @throws InvalidRequestException (400) for a missing or invalid URL or other invalid fields
	 */
	fun create(fields: BookmarkChanges): Bookmark {
		val url = fields.url ?: throw InvalidRequestException("url", "is required")
		val bookmark = Bookmark(normalizeUrl(url.value))
		apply(bookmark, fields)
		validate(bookmark)
		val saved = bookmarks.saveAndFlush(bookmark)
		requestMetadata(saved, overwrite = false)
		changed(saved, ChangeOp.CREATED)
		return initialized(saved)
	}

	/**
	 * Changes the given fields. A new URL clears the favicon (it belongs to the old site) and requests the page metadata
	 * again; it fills the fields that are empty.
	 *
	 * @throws InvalidRequestException (400) for invalid fields
	 */
	fun update(
		id: UUID,
		fields: BookmarkChanges,
		checkVersion: (Long) -> Unit,
	): Bookmark {
		val bookmark = load(id)
		checkVersion(bookmark.version)
		val newUrl = fields.url?.let { normalizeUrl(it.value) }?.takeIf { it != bookmark.url }
		apply(bookmark, fields)
		if (newUrl != null) {
			bookmark.url = newUrl
			bookmark.favicon = null
			bookmark.metadataStatus = MetadataStatus.PENDING
		}
		validate(bookmark)
		val saved = bookmarks.saveAndFlush(bookmark)
		if (newUrl != null) requestMetadata(saved, overwrite = false)
		changed(saved)
		return initialized(saved)
	}

	/** Fetches the page metadata again; the fetched title, description and favicon replace the current ones. */
	fun refreshMetadata(
		id: UUID,
		checkVersion: (Long) -> Unit,
	): Bookmark {
		val bookmark = load(id)
		checkVersion(bookmark.version)
		bookmark.metadataStatus = MetadataStatus.PENDING
		val saved = bookmarks.saveAndFlush(bookmark)
		requestMetadata(saved, overwrite = true)
		changed(saved)
		return initialized(saved)
	}

	/**
	 * Stores the result of a metadata fetch of [url] for bookmark [id]: [metadata] fills the empty title, description
	 * and favicon (with [overwrite]: replaces them where something was fetched), `null` (the fetch failed) only sets the
	 * status. Ignored (`false`) when the bookmark is gone, in the Trash, or its URL changed since (a newer fetch is
	 * pending).
	 */
	fun applyMetadata(
		id: UUID,
		url: String,
		overwrite: Boolean,
		metadata: PageMetadata?,
	): Boolean {
		val bookmark = bookmarks.findActiveById(id)?.takeIf { it.url == url } ?: return false
		Hibernate.initialize(bookmark.tags)
		if (metadata == null) {
			bookmark.metadataStatus = MetadataStatus.FAILED
		} else {
			bookmark.metadataStatus = MetadataStatus.FETCHED

			fun pick(
				current: String?,
				fetched: String?,
			) = if (fetched != null && (overwrite || current.isNullOrBlank())) fetched else current
			bookmark.title = pick(bookmark.title, metadata.title?.clean(MAX_TITLE_LENGTH))
			bookmark.description = pick(bookmark.description, metadata.description?.clean(MAX_DESCRIPTION_LENGTH))
			bookmark.favicon = pick(bookmark.favicon, metadata.favicon?.takeIf { it.length <= MAX_URL_LENGTH && '\u0000' !in it })
		}
		changed(bookmarks.saveAndFlush(bookmark))
		return true
	}

	/** Moves the bookmark to the Trash. */
	fun delete(
		id: UUID,
		checkVersion: (Long) -> Unit,
	) {
		val bookmark = load(id)
		checkVersion(bookmark.version)
		bookmark.softDelete(clock)
		bookmarks.saveAndFlush(bookmark)
		indexer.delete(ItemType.BOOKMARK, bookmark.id)
		changes.publish(ItemType.BOOKMARK, bookmark.id, ChangeOp.DELETED)
	}

	/**
	 * An active bookmark, for other parts of the application.
	 *
	 * @throws NotFoundException (404) when there is no such bookmark or it is in the Trash
	 */
	@Transactional(readOnly = true)
	fun requireActive(id: UUID): Bookmark = bookmarks.findActiveById(id) ?: throw NotFoundException("Bookmark not found")

	/** Has the page metadata of the saved (`PENDING`) [bookmark] fetched after the commit. */
	private fun requestMetadata(
		bookmark: Bookmark,
		overwrite: Boolean,
	) {
		events.publishEvent(BookmarkMetadataRequested(bookmark.id, bookmark.url, overwrite))
	}

	/** Re-indexes a bookmark ([indexBookmark]) and announces it as changed. */
	private fun changed(
		bookmark: Bookmark,
		op: ChangeOp = ChangeOp.UPDATED,
	) {
		indexer.indexBookmark(bookmark)
		changes.publish(ItemType.BOOKMARK, bookmark.id, op)
	}

	private fun load(id: UUID): Bookmark = initialized(requireActive(id))

	private fun initialized(bookmark: Bookmark): Bookmark = bookmark.also { Hibernate.initialize(it.tags) }

	private fun apply(
		bookmark: Bookmark,
		fields: BookmarkChanges,
	) {
		// Tags first: the spelling query auto-flushes, which would write the other changes before they are validated.
		fields.tags?.let { tags ->
			val normalized = normalizeTags(tags.value)
			// Unchanged tags keep their spelling; new ones take the spelling other bookmarks already use.
			val current = bookmark.tags.associateBy { it.lowercase() }
			val wanted = normalized.map { current[it.lowercase()] ?: bookmarks.findTagSpelling(it) ?: it }
			bookmark.tags.retainAll(wanted.toSet())
			bookmark.tags.addAll(wanted)
		}
		fields.title?.let { bookmark.title = it.value?.trim()?.ifEmpty { null } }
		fields.description?.let { bookmark.description = it.value?.trim()?.ifEmpty { null } }
		fields.readLater?.let { bookmark.readLater = it.value }
		fields.read?.let { read ->
			bookmark.readAt = if (read.value) bookmark.readAt ?: clock.storableInstant() else null
		}
	}

	/**
	 * The URL trimmed.
	 *
	 * @throws InvalidRequestException (400) unless it is an absolute http(s) URL with a host, of at most [MAX_URL_LENGTH]
	 * characters
	 */
	private fun normalizeUrl(url: String): String {
		val trimmed = url.trim()
		if (trimmed.length > MAX_URL_LENGTH) throw InvalidRequestException("url", "must be at most $MAX_URL_LENGTH characters")
		val uri =
			try {
				URI(trimmed)
			} catch (_: URISyntaxException) {
				throw InvalidRequestException("url", "must be a valid http or https URL")
			}
		val scheme = uri.scheme?.lowercase()
		if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty()) {
			throw InvalidRequestException("url", "must be a valid http or https URL")
		}
		return trimmed
	}

	/**
	 * The tags trimmed and without a leading `#`, each once regardless of case (the first spelling wins).
	 *
	 * @throws InvalidRequestException (400) for an empty or too long tag, a tag with NUL characters, or too many tags
	 */
	private fun normalizeTags(tags: List<String>): List<String> {
		val errors = mutableListOf<FieldError>()
		val normalized = tags.map { it.trim().removePrefix("#").trim() }
		if (normalized.any { it.isEmpty() }) errors += FieldError("tags", "must not contain empty tags")
		if (normalized.any { it.length > MAX_TAG_LENGTH }) errors += FieldError("tags", "must be at most $MAX_TAG_LENGTH characters each")
		if (normalized.any { it.contains('\u0000') }) errors += FieldError("tags", "must not contain NUL characters")
		val distinct = normalized.distinctBy { it.lowercase() }
		if (distinct.size > MAX_TAGS) errors += FieldError("tags", "must be at most $MAX_TAGS tags")
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
		return distinct
	}

	private fun validate(bookmark: Bookmark) {
		val errors = mutableListOf<FieldError>()
		if ((bookmark.title?.length ?: 0) > MAX_TITLE_LENGTH) errors += FieldError("title", "must be at most $MAX_TITLE_LENGTH characters")
		if ((bookmark.description?.length ?: 0) > MAX_DESCRIPTION_LENGTH) {
			errors += FieldError("description", "must be at most $MAX_DESCRIPTION_LENGTH characters")
		}
		if ('\u0000' in bookmark.url) errors += FieldError("url", "must not contain NUL characters")
		if (bookmark.title?.contains('\u0000') == true) errors += FieldError("title", "must not contain NUL characters")
		if (bookmark.description?.contains('\u0000') == true) errors += FieldError("description", "must not contain NUL characters")
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
	}

	private fun specification(
		filter: BookmarkFilter,
		after: Position?,
	): Specification<Bookmark> =
		Specification { root, _, cb ->
			val predicates = mutableListOf<Predicate>(cb.isNull(root.get<Instant>("deletedAt")))
			filter.readLater?.let { predicates += cb.equal(root.get<Boolean>("readLater"), it) }
			filter.read?.let { read ->
				val readAt = root.get<Instant>("readAt")
				predicates += if (read) cb.isNotNull(readAt) else cb.isNull(readAt)
			}
			filter.tag?.let { tag ->
				// A bookmark has each tag once regardless of case, so the join adds no duplicate rows.
				val tags = root.join<Bookmark, String>("tags", JoinType.INNER)
				predicates +=
					cb.equal(
						cb.lower(tags),
						tag
							.trim()
							.removePrefix("#")
							.trim()
							.lowercase(),
					)
			}
			if (after != null) {
				val createdAt = root.get<Instant>("createdAt")
				val id = root.get<UUID>("id")
				predicates +=
					cb.or(
						cb.lessThan(createdAt, after.createdAt),
						cb.and(cb.equal(createdAt, after.createdAt), cb.lessThan(id, after.id)),
					)
			}
			cb.and(*predicates.toTypedArray())
		}

	private fun positionOf(bookmark: Bookmark): String = "${bookmark.createdAt}|${bookmark.id}"

	private fun parsePosition(position: String): Position {
		val parsed =
			runCatching {
				val (createdAt, id) = position.split('|').also { require(it.size == 2) }
				Position(Instant.parse(createdAt), UUID.fromString(id))
			}.getOrNull()
		return parsed ?: throw InvalidRequestException("cursor", "is not a valid cursor")
	}

	/** The keyset of the last bookmark of a page. */
	private data class Position(
		val createdAt: Instant,
		val id: UUID,
	)

	companion object {
		const val MAX_URL_LENGTH = 2_048
		const val MAX_TITLE_LENGTH = 500
		const val MAX_DESCRIPTION_LENGTH = 2_000
		const val MAX_TAG_LENGTH = 50
		const val MAX_TAGS = 50
	}
}

/** Fetched text: whitespace collapsed, without NUL characters, at most [maxLength] characters; `null` when empty. */
private fun String.clean(maxLength: Int): String? =
	replace("\u0000", "")
		.replace(Regex("\\s+"), " ")
		.trim()
		.take(maxLength)
		.trim()
		.ifEmpty { null }
