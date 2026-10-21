package ch.sonensei.cockpit.notes.domain

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.persistence.storableInstant
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.web.CursorPage
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ch.sonensei.cockpit.files.domain.UploadLimits
import ch.sonensei.cockpit.notes.infra.NoteRepository
import ch.sonensei.cockpit.notes.infra.NoteRevisionRepository
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.Predicate
import org.hibernate.Hibernate
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Writes the search document of [note]: its display title and its body as plain text (Markdown stripped). The one place
 * that decides what a note's search document holds, used by [NoteService] and the Trash restore.
 */
fun SearchIndexer.indexNote(note: Note) {
	upsert(ItemType.NOTE, note.id, note.displayTitle, NoteMarkdown.plainText(note.bodyMd))
}

/**
 * Notes (SPEC.md §8.1): CRUD, tags, the history of their content and their images. Every change re-indexes the note
 * for search (Markdown stripped), syncs its inline links `[[type:id]]` to the link table and publishes a
 * [ch.sonensei.cockpit.core.events.domain.ChangeEvent]; a deleted note goes to the Trash
 * ([NoteTrashContributor][ch.sonensei.cockpit.notes.infra.NoteTrashContributor]) and leaves the search index.
 *
 * History: before a change of the title or body, the content as it was is kept as a [NoteRevision], unless the newest
 * revision is younger than [REVISION_INTERVAL] (so an editing session with autosave keeps the state from before it, not
 * every keystroke). A restore always keeps the content it replaces. The newest [MAX_REVISIONS] are kept.
 *
 * Returned notes have their tags loaded. Methods that change a note take a `checkVersion` callback that sees the
 * current version before anything changes (If-Match).
 */
@Service
@Transactional
class NoteService(
	private val notes: NoteRepository,
	private val revisions: NoteRevisionRepository,
	private val links: LinkService,
	private val indexer: SearchIndexer,
	private val changes: ChangeEventPublisher,
	private val files: FilesService,
	private val uploadLimits: UploadLimits,
	private val clock: Clock,
) {
	/** One page of notes matching [filter]: pinned first, then the most recently changed, then by id. */
	@Transactional(readOnly = true)
	fun list(
		filter: NoteFilter,
		request: CursorRequest,
	): CursorPage<Note> {
		val after = request.position?.let(::parsePosition)
		val sort = Sort.by(Sort.Order.desc("pinned"), Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))
		val fetched =
			notes.findBy<Note, List<Note>>(specification(filter, after)) { query ->
				query.sortBy(sort).limit(request.limit + 1).all()
			}
		fetched.forEach { Hibernate.initialize(it.tags) }
		return request.page(fetched, ::positionOf)
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): Note = load(id)

	/** Every tag in use (on notes not in the Trash) with its number of notes, ordered regardless of case. */
	@Transactional(readOnly = true)
	fun tags(): List<NoteTag> = notes.countTags().map { NoteTag(it.tag, it.count) }

	/**
	 * Creates a note; fields not given get their defaults (empty body, no tags, not pinned or archived).
	 *
	 * @throws InvalidRequestException (400) for invalid fields
	 */
	fun create(fields: NoteChanges): Note {
		val note = Note()
		apply(note, fields)
		validate(note)
		val saved = notes.saveAndFlush(note)
		links.sync(ref(saved), NoteMarkdown.inlineLinks(saved.bodyMd))
		changed(saved, ChangeOp.CREATED)
		return initialized(saved)
	}

	/**
	 * Changes the given fields. A change of the title or body keeps the previous content as a revision (see the class
	 * comment) and, for the body, syncs the inline links.
	 *
	 * @throws InvalidRequestException (400) for invalid fields
	 */
	fun update(
		id: UUID,
		fields: NoteChanges,
		checkVersion: (Long) -> Unit,
	): Note {
		val note = load(id)
		checkVersion(note.version)
		val before = Content.of(note)
		val savedAt = checkNotNull(note.updatedAt)
		apply(note, fields)
		validate(note)
		val after = Content.of(note)
		if (after != before) keepRevision(note, before, savedAt, force = false)
		return saved(note, linksChanged = after.bodyMd != before.bodyMd)
	}

	/** Moves the note to the Trash; its revisions, links and images stay until it is purged. */
	fun delete(
		id: UUID,
		checkVersion: (Long) -> Unit,
	) {
		val note = load(id)
		checkVersion(note.version)
		note.softDelete(clock)
		notes.saveAndFlush(note)
		indexer.delete(ItemType.NOTE, note.id)
		changes.publish(ItemType.NOTE, note.id, ChangeOp.DELETED)
	}

	/**
	 * The kept revisions of the note, newest first.
	 *
	 * @throws NotFoundException (404) when there is no such note or it is in the Trash
	 */
	@Transactional(readOnly = true)
	fun revisions(id: UUID): List<NoteRevision> {
		requireActive(id)
		return revisions.findByNoteIdOrderByNumberDesc(id)
	}

	/**
	 * Puts the title and body of revision [number] back. The replaced content is kept as a new revision (unless it is
	 * the same as the newest one), so the restore can be undone. Tags, pin and archive state stay.
	 *
	 * @throws NotFoundException (404) when there is no such note (or it is in the Trash) or no such revision
	 */
	fun restoreRevision(
		id: UUID,
		number: Int,
		checkVersion: (Long) -> Unit,
	): Note {
		val note = load(id)
		val revision = revisions.findByNoteIdAndNumber(id, number) ?: throw NotFoundException("Revision not found")
		checkVersion(note.version)
		val before = Content.of(note)
		val restored = Content(revision.title, revision.bodyMd)
		if (restored == before) return note
		val savedAt = checkNotNull(note.updatedAt)
		note.title = restored.title
		note.bodyMd = restored.bodyMd
		keepRevision(note, before, savedAt, force = true)
		return saved(note, linksChanged = restored.bodyMd != before.bodyMd)
	}

	/**
	 * Stores an image for the note in `/Attachments/Notes` (created when missing). The note itself does not change: the
	 * client inserts the reference `![](cockpit-file:{id})` into the body.
	 *
	 * [content] should already be buffered locally (see [FilesService.storeFile]).
	 *
	 * @throws NotFoundException (404) when there is no such note or it is in the Trash
	 * @throws InvalidRequestException (400) when [mime] is not an image type or the name is invalid
	 * @throws ch.sonensei.cockpit.files.infra.BlobTooLargeException when the image exceeds the maximum file size
	 */
	fun addImage(
		id: UUID,
		name: String,
		mime: String,
		content: InputStream,
	): FileItem {
		requireActive(id)
		if (!mime.lowercase().startsWith("image/")) throw InvalidRequestException("file", "must be an image")
		val folder = files.specialFolder(SpecialFolder.NOTES)
		return files.storeFile(folder.id, name, mime, content, uploadLimits.maxSize)
	}

	/**
	 * An active note, for other parts of the application.
	 *
	 * @throws NotFoundException (404) when there is no such note or it is in the Trash
	 */
	@Transactional(readOnly = true)
	fun requireActive(id: UUID): Note = notes.findActiveById(id) ?: throw NotFoundException("Note not found")

	/** Re-indexes a note ([indexNote]) and announces it as changed. */
	private fun changed(
		note: Note,
		op: ChangeOp = ChangeOp.UPDATED,
	) {
		indexer.indexNote(note)
		changes.publish(ItemType.NOTE, note.id, op)
	}

	private fun saved(
		note: Note,
		linksChanged: Boolean,
	): Note {
		val saved = notes.saveAndFlush(note)
		if (linksChanged) links.sync(ref(saved), NoteMarkdown.inlineLinks(saved.bodyMd))
		changed(saved)
		return initialized(saved)
	}

	/**
	 * Keeps [content] (the note's content before a change, last saved at [savedAt]) as the next revision, unless the
	 * newest revision is younger than [REVISION_INTERVAL] and not [force]d, then deletes all but the newest
	 * [MAX_REVISIONS]. Callers change [note] first and read [savedAt] before that.
	 *
	 * The changed note is flushed before the revision is inserted: its versioned update waits for a concurrent change
	 * of the same version and then fails with an optimistic locking error (409), instead of the revision insert failing
	 * on the duplicate revision number (Hibernate would otherwise run the insert before the update).
	 */
	private fun keepRevision(
		note: Note,
		content: Content,
		savedAt: Instant,
		force: Boolean,
	) {
		val latest = revisions.findFirstByNoteIdOrderByNumberDesc(note.id)
		val now = clock.storableInstant()
		if (latest != null) {
			if (force && Content(latest.title, latest.bodyMd) == content) return
			val recent = Duration.between(checkNotNull(latest.createdAt), now) < REVISION_INTERVAL
			if (!force && recent) return
		}
		val number = (latest?.number ?: 0) + 1
		notes.saveAndFlush(note)
		revisions.save(NoteRevision(note.id, number, content.title, content.bodyMd, savedAt))
		revisions.deleteUpTo(note.id, number - MAX_REVISIONS)
	}

	private fun load(id: UUID): Note = initialized(requireActive(id))

	private fun initialized(note: Note): Note = note.also { Hibernate.initialize(it.tags) }

	private fun ref(note: Note) = ItemRef(ItemType.NOTE, note.id)

	private fun apply(
		note: Note,
		fields: NoteChanges,
	) {
		// Tags first: the spelling query auto-flushes, which would write the other changes before they are validated.
		fields.tags?.let { tags ->
			val normalized = normalizeTags(tags.value)
			// Unchanged tags keep their spelling; new ones take the spelling other notes already use.
			val current = note.tags.associateBy { it.lowercase() }
			val wanted = normalized.map { current[it.lowercase()] ?: notes.findTagSpelling(it) ?: it }
			note.tags.retainAll(wanted.toSet())
			note.tags.addAll(wanted)
		}
		fields.title?.let { note.title = it.value?.trim()?.ifEmpty { null } }
		fields.bodyMd?.let { note.bodyMd = it.value }
		fields.pinned?.let { note.pinned = it.value }
		fields.archived?.let { note.archived = it.value }
	}

	/**
	 * The tags trimmed and without a leading `#`, each once regardless of case (the first spelling wins).
	 *
	 * @throws InvalidRequestException (400) for an empty or too long tag, or too many tags
	 */
	private fun normalizeTags(tags: List<String>): List<String> {
		val errors = mutableListOf<FieldError>()
		val normalized = tags.map { it.trim().removePrefix("#").trim() }
		if (normalized.any { it.isEmpty() }) errors += FieldError("tags", "must not contain empty tags")
		if (normalized.any { it.length > MAX_TAG_LENGTH }) errors += FieldError("tags", "must be at most $MAX_TAG_LENGTH characters each")
		val distinct = normalized.distinctBy { it.lowercase() }
		if (distinct.size > MAX_TAGS) errors += FieldError("tags", "must be at most $MAX_TAGS tags")
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
		return distinct
	}

	private fun validate(note: Note) {
		val errors = mutableListOf<FieldError>()
		if ((note.title?.length ?: 0) > MAX_TITLE_LENGTH) errors += FieldError("title", "must be at most $MAX_TITLE_LENGTH characters")
		if (note.bodyMd.length > MAX_BODY_LENGTH) errors += FieldError("bodyMd", "must be at most $MAX_BODY_LENGTH characters")
		if (note.title?.contains('\u0000') == true) errors += FieldError("title", "must not contain NUL characters")
		if (note.bodyMd.contains('\u0000')) errors += FieldError("bodyMd", "must not contain NUL characters")
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
	}

	private fun specification(
		filter: NoteFilter,
		after: Position?,
	): Specification<Note> =
		Specification { root, query, cb ->
			val predicates = mutableListOf<Predicate>(cb.isNull(root.get<Instant>("deletedAt")))
			filter.pinned?.let { predicates += cb.equal(root.get<Boolean>("pinned"), it) }
			filter.archived?.let { predicates += cb.equal(root.get<Boolean>("archived"), it) }
			filter.tag?.let { tag ->
				// A note has each tag once regardless of case, so the join adds no duplicate rows.
				val tags = root.join<Note, String>("tags", JoinType.INNER)
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
				val pinned = root.get<Boolean>("pinned")
				val updatedAt = root.get<Instant>("updatedAt")
				val id = root.get<UUID>("id")
				predicates +=
					cb.or(
						cb.lessThan(pinned, after.pinned),
						cb.and(
							cb.equal(pinned, after.pinned),
							cb.or(
								cb.lessThan(updatedAt, after.updatedAt),
								cb.and(cb.equal(updatedAt, after.updatedAt), cb.lessThan(id, after.id)),
							),
						),
					)
			}
			cb.and(*predicates.toTypedArray())
		}

	private fun positionOf(note: Note): String = "${note.pinned}|${note.updatedAt}|${note.id}"

	private fun parsePosition(position: String): Position {
		val parsed =
			runCatching {
				val (pinned, updatedAt, id) = position.split('|').also { require(it.size == 3) }
				Position(pinned.toBooleanStrict(), Instant.parse(updatedAt), UUID.fromString(id))
			}.getOrNull()
		return parsed ?: throw InvalidRequestException("cursor", "is not a valid cursor")
	}

	/** The keyset of the last note of a page. */
	private data class Position(
		val pinned: Boolean,
		val updatedAt: Instant,
		val id: UUID,
	)

	/** The part of a note that revisions keep. */
	private data class Content(
		val title: String?,
		val bodyMd: String,
	) {
		companion object {
			fun of(note: Note) = Content(note.title, note.bodyMd)
		}
	}

	companion object {
		const val MAX_TITLE_LENGTH = 500
		const val MAX_BODY_LENGTH = 100_000
		const val MAX_TAG_LENGTH = 50
		const val MAX_TAGS = 50
		const val MAX_REVISIONS = 20

		/** A change keeps the previous content only when the newest revision is at least this old. */
		val REVISION_INTERVAL: Duration = Duration.ofMinutes(5)
	}
}
