package ch.sonensei.cockpit.journal.entries.domain

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.CursorPage
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ch.sonensei.cockpit.files.domain.UploadLimits
import ch.sonensei.cockpit.journal.entries.infra.JournalEntryRepository
import ch.sonensei.cockpit.notes.domain.NoteMarkdown
import jakarta.persistence.criteria.JoinType
import jakarta.persistence.criteria.Predicate
import org.hibernate.Hibernate
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.temporal.ChronoUnit

/**
 * Writes the search document of [entry]: its display title and its body as plain text (Markdown stripped, like notes).
 * The one place that decides what an entry's search document holds, used by [JournalEntryService] and the Trash
 * restore.
 */
fun SearchIndexer.indexJournalEntry(entry: JournalEntry) {
	upsert(ItemType.JOURNAL_ENTRY, entry.id, entry.displayTitle, NoteMarkdown.plainText(entry.bodyMd))
}

/**
 * The journal (SPEC.md §8.5): one entry per local date, written with an upsert by date, the dates with entries (calendar
 * heatmap) and "on this day" in earlier years. Every change re-indexes the entry for search, syncs its inline links
 * `[[type:id]]` (the same syntax as notes) and publishes a [ch.sonensei.cockpit.core.events.domain.ChangeEvent]; a
 * deleted entry goes to the Trash and leaves the search index. "Today" is the date in the user's time zone setting.
 *
 * Returned entries have their tags loaded.
 */
@Service
@Transactional
class JournalEntryService(
	private val entries: JournalEntryRepository,
	private val links: LinkService,
	private val indexer: SearchIndexer,
	private val changes: ChangeEventPublisher,
	private val files: FilesService,
	private val uploadLimits: UploadLimits,
	private val settings: SettingsService,
	private val clock: Clock,
) {
	/** Today in the user's time zone. */
	@Transactional(readOnly = true)
	fun today(): LocalDate = LocalDate.now(clock.withZone(settings.timeZone()))

	/**
	 * One page of entries from [from] to [to] (both optional, included) with [tag] (regardless of case), newest date
	 * first.
	 *
	 * @throws InvalidRequestException (400) when [to] is before [from] or the tag has NUL characters
	 */
	@Transactional(readOnly = true)
	fun list(
		from: LocalDate?,
		to: LocalDate?,
		tag: String?,
		request: CursorRequest,
	): CursorPage<JournalEntry> {
		if (from != null && to != null && to < from) throw InvalidRequestException("to", "must not be before from")
		// PostgreSQL rejects NUL in text values, so the filter would otherwise fail with a 500.
		if (tag?.contains('\u0000') == true) throw InvalidRequestException("tag", "must not contain NUL characters")
		val after = request.position?.let(::parsePosition)
		val fetched =
			entries.findBy<JournalEntry, List<JournalEntry>>(specification(from, to, tag, after)) { query ->
				query.sortBy(Sort.by(Sort.Order.desc("date"))).limit(request.limit + 1).all()
			}
		fetched.forEach { Hibernate.initialize(it.tags) }
		return request.page(fetched) { it.date.toString() }
	}

	/**
	 * The entry of [date].
	 *
	 * @throws NotFoundException (404) when the date has no entry (or only one in the Trash)
	 */
	@Transactional(readOnly = true)
	fun get(date: LocalDate): JournalEntry = initialized(requireActive(date))

	/**
	 * Creates the entry of [date] or replaces its content (upsert). [checkVersion] sees the version of the existing entry,
	 * or `null` when the date has none yet, before anything changes (If-Match).
	 *
	 * @return the entry and whether it was created
	 * @throws InvalidRequestException (400) for a date after today or invalid content
	 * @throws ConflictException (409) when another request created the entry of the same date at the same time
	 */
	fun put(
		date: LocalDate,
		content: JournalEntryContent,
		checkVersion: (Long?) -> Unit,
	): Pair<JournalEntry, Boolean> {
		val existing = entries.findActiveByDate(date)
		checkVersion(existing?.version)
		if (date > today()) throw InvalidRequestException("date", "must not be after today")
		val tags = normalizeTags(content.tags)
		validate(content)
		val entry = existing ?: JournalEntry(date)
		val bodyChanged = existing == null || entry.bodyMd != content.bodyMd
		entry.bodyMd = content.bodyMd
		entry.mood = content.mood
		// Unchanged tags keep their spelling.
		val current = entry.tags.associateBy { it.lowercase() }
		val wanted = tags.map { current[it.lowercase()] ?: it }.toSet()
		entry.tags.retainAll(wanted)
		entry.tags.addAll(wanted)
		val saved =
			try {
				entries.saveAndFlush(entry)
			} catch (_: DataIntegrityViolationException) {
				// The partial unique index on the date: a concurrent PUT created the entry first.
				throw ConflictException("The journal entry of $date was created concurrently")
			}
		if (bodyChanged) links.sync(ref(saved), NoteMarkdown.inlineLinks(saved.bodyMd))
		indexer.indexJournalEntry(saved)
		changes.publish(ItemType.JOURNAL_ENTRY, saved.id, if (existing == null) ChangeOp.CREATED else ChangeOp.UPDATED)
		return initialized(saved) to (existing == null)
	}

	/** Moves the entry of [date] to the Trash; its links and images stay until it is purged. */
	fun delete(
		date: LocalDate,
		checkVersion: (Long) -> Unit,
	) {
		val entry = requireActive(date)
		checkVersion(entry.version)
		entry.softDelete(clock)
		entries.saveAndFlush(entry)
		indexer.delete(ItemType.JOURNAL_ENTRY, entry.id)
		changes.publish(ItemType.JOURNAL_ENTRY, entry.id, ChangeOp.DELETED)
	}

	/**
	 * The dates from [from] to [to] (both included) that have an entry, with its mood, by date.
	 *
	 * @throws InvalidRequestException (400) when [to] is before [from] or the range is longer than [MAX_RANGE_DAYS]
	 */
	@Transactional(readOnly = true)
	fun days(
		from: LocalDate,
		to: LocalDate,
	): List<JournalDay> {
		if (to < from) throw InvalidRequestException("to", "must not be before from")
		if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
			throw InvalidRequestException("to", "must be at most $MAX_RANGE_DAYS days after from")
		}
		return entries.findDays(from, to)
	}

	/**
	 * The entries of the same day in earlier years than [date], newest first. On 28 February of a year that is not a
	 * leap year, entries of 29 February are included (that day has no other occurrence).
	 */
	@Transactional(readOnly = true)
	fun onThisDay(date: LocalDate): List<JournalEntry> {
		val days =
			if (date.month == Month.FEBRUARY && date.dayOfMonth == 28 && !date.isLeapYear) listOf(28, 29) else listOf(date.dayOfMonth)
		return entries.findOnDayOfYear(date.withDayOfYear(1), date.monthValue, days).onEach { Hibernate.initialize(it.tags) }
	}

	/**
	 * Stores an image for the journal in `/Attachments/Journal` (created when missing). No entry changes: the client
	 * inserts the reference `![](cockpit-file:{id})` into a body, so an image can be added before the entry is first saved.
	 *
	 * [content] should already be buffered locally (see [FilesService.storeFile]).
	 *
	 * @throws InvalidRequestException (400) when [mime] is not an image type or the name is invalid
	 * @throws ch.sonensei.cockpit.files.infra.BlobTooLargeException when the image exceeds the maximum file size
	 */
	fun addImage(
		name: String,
		mime: String,
		content: InputStream,
	): FileItem {
		if (!mime.lowercase().startsWith("image/")) throw InvalidRequestException("file", "must be an image")
		val folder = files.specialFolder(SpecialFolder.JOURNAL)
		return files.storeFile(folder.id, name, mime, content, uploadLimits.maxSize)
	}

	private fun requireActive(date: LocalDate): JournalEntry =
		entries.findActiveByDate(date) ?: throw NotFoundException("Journal entry not found")

	private fun initialized(entry: JournalEntry): JournalEntry = entry.also { Hibernate.initialize(it.tags) }

	private fun ref(entry: JournalEntry) = ItemRef(ItemType.JOURNAL_ENTRY, entry.id)

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

	private fun validate(content: JournalEntryContent) {
		val errors = mutableListOf<FieldError>()
		if (content.bodyMd.length > MAX_BODY_LENGTH) errors += FieldError("bodyMd", "must be at most $MAX_BODY_LENGTH characters")
		if (content.bodyMd.contains('\u0000')) errors += FieldError("bodyMd", "must not contain NUL characters")
		if (content.mood != null && content.mood !in MIN_MOOD..MAX_MOOD) errors += FieldError("mood", "must be between $MIN_MOOD and $MAX_MOOD")
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
	}

	private fun specification(
		from: LocalDate?,
		to: LocalDate?,
		tag: String?,
		after: LocalDate?,
	): Specification<JournalEntry> =
		Specification { root, _, cb ->
			val date = root.get<LocalDate>("date")
			val predicates = mutableListOf<Predicate>(cb.isNull(root.get<Instant>("deletedAt")))
			from?.let { predicates += cb.greaterThanOrEqualTo(date, it) }
			to?.let { predicates += cb.lessThanOrEqualTo(date, it) }
			after?.let { predicates += cb.lessThan(date, it) }
			tag?.let {
				// An entry has each tag once regardless of case, so the join adds no duplicate rows.
				val tags = root.join<JournalEntry, String>("tags", JoinType.INNER)
				predicates +=
					cb.equal(
						cb.lower(tags),
						it
							.trim()
							.removePrefix("#")
							.trim()
							.lowercase(),
					)
			}
			cb.and(*predicates.toTypedArray())
		}

	private fun parsePosition(position: String): LocalDate =
		runCatching { LocalDate.parse(position) }.getOrNull() ?: throw InvalidRequestException("cursor", "is not a valid cursor")

	companion object {
		const val MAX_BODY_LENGTH = 100_000
		const val MAX_TAG_LENGTH = 50
		const val MAX_TAGS = 50
		const val MIN_MOOD = 1
		const val MAX_MOOD = 5
		const val MAX_RANGE_DAYS = 3660L
	}
}
