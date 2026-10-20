package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.api.CalendarService
import ch.sonensei.cockpit.calendar.model.Calendar
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventIndexer
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.EventSyncState
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.storableInstant
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** An external source with its adapter's poll interval and its sync status (null: never synced). */
data class SourceSyncView(
	val source: CalendarSource,
	val adapter: CalendarProviderAdapter?,
	val status: SourceSyncStatus?,
)

/**
 * Pulls external calendars into the cockpit (SPEC.md §6.2): the calendars of a source, then per calendar the changes
 * since its stored cursor, merged into the local copies (remote is the source of truth). Every change is re-indexed for
 * search and announced with a change event; events and calendars the provider no longer has are deleted for good (with
 * their links). A run records its attempt, success and error in [SourceSyncStatus]; one failing calendar does not stop
 * the others.
 *
 * Network calls run outside transactions; each calendar's merge and cursor commit together in their own transaction, so
 * a failed run fetches the same changes again. Runs of one source never overlap.
 */
@Service
class CalendarSyncService(
	private val sources: CalendarSourceRepository,
	private val calendars: CalendarRepository,
	private val events: CalendarEventRepository,
	private val statuses: SourceSyncStatusRepository,
	private val cursors: CalendarSyncCursorRepository,
	private val registry: CalendarProviderRegistry,
	private val indexer: CalendarEventIndexer,
	private val changes: ChangeEventPublisher,
	private val appEvents: ApplicationEventPublisher,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val locks = ConcurrentHashMap<UUID, ReentrantLock>()

	// Always a transaction of its own: the write-through refetch runs after its (rolled back) transaction completed.
	private val tx =
		TransactionTemplate(transactionManager).apply {
			propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
		}
	private val readTx =
		TransactionTemplate(transactionManager).apply {
			propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
			isReadOnly = true
		}

	/** Every source in id order, with its sync status (LOCAL sources and types without adapter have no adapter). */
	fun list(): List<SourceSyncView> =
		readTx.execute {
			val status = statuses.findAll().associateBy { it.sourceId }
			sources.findAllByOrderByIdAsc().map { SourceSyncView(it, registry.find(it.type), status[it.id]) }
		}!!

	fun get(id: UUID): SourceSyncView =
		readTx.execute {
			sources.findById(id).orElse(null)?.let { SourceSyncView(it, registry.find(it.type), statuses.findById(id).orElse(null)) }
		} ?: throw NotFoundException("Calendar source not found")

	/**
	 * Syncs source [id] now (waits for a run already in progress) and returns it with its new status; a failed sync is
	 * recorded in the status, not thrown.
	 *
	 * @throws NotFoundException (404) when there is no such source
	 * @throws ConflictException (409) when the source is LOCAL or its type has no adapter
	 */
	fun sync(id: UUID): SourceSyncView {
		val view = get(id)
		val adapter =
			view.adapter
				?: throw ConflictException(
					if (view.source.type == CalendarSourceType.LOCAL) "Local calendars are not synced" else "Sources of this type cannot be synced yet",
				)
		lockOf(id).withLock { run(view.source, adapter) }
		return get(id)
	}

	/** Syncs every external source whose poll interval has passed since its last attempt; returns how many. */
	fun syncDue(): Int {
		val now = clock.instant()
		val due =
			list().filter { view ->
				val adapter = view.adapter ?: return@filter false
				val last = view.status?.lastAttemptAt
				last == null || !last.plus(adapter.pollInterval).isAfter(now)
			}
		due.forEach { view ->
			val lock = lockOf(view.source.id)
			// A run in progress (manual sync) is as good as this one.
			if (lock.tryLock()) {
				try {
					run(view.source, view.adapter!!)
				} finally {
					lock.unlock()
				}
			}
		}
		return due.size
	}

	/**
	 * Fetches calendar [calendarId] again, e.g. after a write was refused because the event changed remotely. Failures
	 * are logged only (the caller already reports the conflict).
	 */
	fun refetch(calendarId: UUID) {
		val found =
			readTx.execute {
				calendars.findById(calendarId).orElse(null)?.let { calendar ->
					sources.findById(calendar.sourceId).orElse(null)?.let { Triple(it, calendar.externalId, registry.find(it.type)) }
				}
			} ?: return
		val (source, externalId, adapter) = found
		if (externalId == null || adapter == null) return
		val lock = lockOf(source.id)
		// A run in progress fetches the new version as well; the caller (a user's request) should not wait for it.
		if (!lock.tryLock()) return
		try {
			syncCalendar(adapter, source, calendarId, externalId)
		} catch (e: RuntimeException) {
			log.warn("Refetching calendar {} failed: {}", calendarId, describe(e))
		} finally {
			lock.unlock()
		}
	}

	private fun lockOf(sourceId: UUID) = locks.computeIfAbsent(sourceId) { ReentrantLock() }

	private fun run(
		source: CalendarSource,
		adapter: CalendarProviderAdapter,
	) {
		val startedAt = clock.storableInstant()
		val errors = mutableListOf<String>()
		val targets =
			try {
				val remote = adapter.listCalendars(source)
				tx.execute { mergeCalendars(source, remote) }!!
			} catch (e: RuntimeException) {
				log.warn("Listing the calendars of source {} failed: {}", source.id, describe(e))
				errors += "Calendars: ${message(e)}"
				readTx.execute {
					calendars.findBySourceIdOrderByIdAsc(source.id).filter { it.externalId != null }.map { Target(it.id, it.externalId, it.name) }
				}!!
			}
		for (target in targets) {
			try {
				val skipped = syncCalendar(adapter, source, target.id, target.externalId!!)
				if (skipped > 0) errors += "${target.name}: $skipped events could not be stored"
			} catch (e: RuntimeException) {
				log.warn("Syncing calendar {} of source {} failed: {}", target.id, source.id, describe(e))
				errors += "${target.name}: ${message(e)}"
			}
		}
		tx.execute {
			val status = statuses.findById(source.id).orElse(null) ?: SourceSyncStatus(source.id, startedAt)
			status.lastAttemptAt = startedAt
			if (errors.isEmpty()) {
				status.lastSuccessAt = startedAt
				status.lastError = null
			} else {
				status.lastError = errors.joinToString("; ").take(MAX_ERROR_LENGTH)
			}
			// The source may have been removed while it synced.
			if (sources.existsById(source.id)) {
				statuses.save(status)
				changes.publish(SOURCE_CHANGE_TYPE, source.id, ChangeOp.UPDATED)
			}
		}
	}

	private data class Target(
		val id: UUID,
		val externalId: String?,
		val name: String,
	)

	// --- calendars

	/** Creates, renames or removes the source's calendars like the provider has them; returns those to sync. */
	private fun mergeCalendars(
		source: CalendarSource,
		remote: List<RemoteCalendar>,
	): List<Target> {
		val local = calendars.findBySourceIdOrderByIdAsc(source.id)
		val byExternalId = local.filter { it.externalId != null }.associateBy { it.externalId!! }
		val wanted = remote.distinctBy { it.externalId }
		val result =
			wanted.map { r ->
				val name = r.name.ifBlank { UNTITLED }.take(CalendarService.MAX_NAME_LENGTH)
				val existing = byExternalId[r.externalId]
				val calendar =
					if (existing == null) {
						val created =
							Calendar(source.id, name).apply {
								// The color is the provider's at first; the user may change it locally afterwards.
								color = r.color?.takeIf { COLOR.matches(it) }
								readOnly = r.readOnly
								externalId = r.externalId
							}
						calendars.saveAndFlush(created).also { changes.publish(CalendarService.CALENDAR_CHANGE_TYPE, it.id, ChangeOp.CREATED) }
					} else if (existing.name != name || existing.readOnly != r.readOnly) {
						existing.name = name
						existing.readOnly = r.readOnly
						calendars.saveAndFlush(existing).also { changes.publish(CalendarService.CALENDAR_CHANGE_TYPE, it.id, ChangeOp.UPDATED) }
					} else {
						existing
					}
				Target(calendar.id, r.externalId, calendar.name)
			}
		val remoteIds = wanted.map { it.externalId }.toSet()
		local.filter { it.externalId !in remoteIds }.forEach(::removeCalendar)
		return result
	}

	private fun removeCalendar(calendar: Calendar) {
		events.findIdsByCalendarId(calendar.id).forEach { appEvents.publishEvent(ItemDeletedEvent(ItemRef(ItemType.EVENT, it))) }
		calendars.delete(calendar)
		calendars.flush()
		changes.publish(CalendarService.CALENDAR_CHANGE_TYPE, calendar.id, ChangeOp.DELETED)
	}

	// --- events

	/** Fetches and merges the changes of one calendar; returns how many remote events could not be stored. */
	private fun syncCalendar(
		adapter: CalendarProviderAdapter,
		source: CalendarSource,
		calendarId: UUID,
		externalId: String,
	): Int {
		val startCursor = readTx.execute { cursors.findById(calendarId).orElse(null)?.cursor }
		var cursor = startCursor
		val pages = mutableListOf<RemoteEventChanges>()
		do {
			if (pages.size == MAX_PAGES) throw CalendarProviderException("The provider sent more than $MAX_PAGES pages of changes")
			val page = adapter.fetchEvents(source, externalId, cursor)
			pages += page
			cursor = page.cursor
		} while (page.hasMore)
		return tx.execute {
			if (!calendars.existsById(calendarId)) return@execute 0
			val merge = mergeEvents(calendarId, pages)
			// An override whose series is missing is not sent again after this cursor: fetch everything next time.
			val next = if (merge.orphanedOverride && startCursor != null) null else cursor
			if (next == null) {
				cursors.deleteById(calendarId)
			} else {
				val row = cursors.findById(calendarId).orElse(null) ?: CalendarSyncCursor(calendarId, next)
				row.cursor = next
				cursors.save(row)
			}
			merge.skipped
		}!!
	}

	/** How many remote events could not be stored, and whether one of them was an override without its series. */
	private data class MergeResult(
		val skipped: Int,
		val orphanedOverride: Boolean,
	)

	/**
	 * Merges the changes of all [pages] (pages and the events in them come in the provider's order): the series and
	 * single events of every page first, then the overrides (which need their series), then the deletions. A key that
	 * occurs more than once counts as it occurs last.
	 */
	private fun mergeEvents(
		calendarId: UUID,
		pages: List<RemoteEventChanges>,
	): MergeResult {
		// Per key its last change: the event, or null when it was deleted.
		val last = LinkedHashMap<RemoteEventKey, RemoteEvent?>()
		for (page in pages) {
			page.events.forEach {
				last.remove(it.key)
				last[it.key] = it
			}
			page.deleted.forEach {
				last.remove(it)
				last[it] = null
			}
		}
		val (masters, overrides) = last.values.filterNotNull().partition { it.key.recurrenceId == null }
		var skipped = 0
		val invalidSeries = mutableSetOf<String>()
		for (remote in masters) {
			if (!store(calendarId, remote) { upsertSeries(calendarId, it) }) {
				skipped++
				invalidSeries += remote.key.externalId
			}
		}
		var orphanedOverride = false
		for (remote in overrides) {
			if (!store(calendarId, remote) { upsertOverride(calendarId, it) }) {
				skipped++
				// A series that was invalid comes again only when it changes; a missing one may come with a full fetch.
				if (remote.key.externalId !in invalidSeries &&
					events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId, remote.key.externalId) == null
				) {
					orphanedOverride = true
				}
			}
		}
		last.filterValues { it == null }.keys.forEach { remove(calendarId, it) }
		if (pages.any { it.complete }) {
			removeMissing(calendarId, masters.map { it.key.externalId }.toSet(), overrides.map { it.key }.toSet())
		}
		return MergeResult(skipped, orphanedOverride)
	}

	/** Stores [remote], normalized, with [upsert]; false when it could not be stored. */
	private fun store(
		calendarId: UUID,
		remote: RemoteEvent,
		upsert: (RemoteEvent) -> Boolean,
	): Boolean =
		try {
			// Normalized before anything of the local copy changes: an invalid remote change leaves it as it was.
			upsert(remote.normalized())
		} catch (e: IllegalArgumentException) {
			log.warn("Skipped an invalid remote event of calendar {}: {}", calendarId, e.message)
			false
		}

	private fun upsertSeries(
		calendarId: UUID,
		remote: RemoteEvent,
	): Boolean {
		val existing = events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId, remote.key.externalId)
		if (existing != null && isCurrent(existing, remote)) return true
		val event = existing ?: CalendarEvent(calendarId, UNTITLED)
		if (event.isDeleted) event.restore()
		event.copyFrom(remote)
		changed(events.saveAndFlush(event), if (existing == null) ChangeOp.CREATED else ChangeOp.UPDATED)
		return true
	}

	private fun upsertOverride(
		calendarId: UUID,
		remote: RemoteEvent,
	): Boolean {
		val occurrence = remote.key.recurrenceId!!
		val master = events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId, remote.key.externalId)
		if (master == null || !master.isRecurring || master.allDay != (occurrence.date != null)) {
			log.warn("Skipped a remote override of calendar {} without a matching series", calendarId)
			return false
		}
		val existing = events.findByParentEventIdOrderByIdAsc(master.id).firstOrNull { it.recurrenceId == occurrence }
		if (existing != null && isCurrent(existing, remote)) return true
		val event = existing ?: CalendarEvent(calendarId, UNTITLED).apply { overrideOf(master, occurrence) }
		if (event.isDeleted) event.restore()
		event.copyFrom(remote)
		changed(events.saveAndFlush(event), if (existing == null) ChangeOp.CREATED else ChangeOp.UPDATED)
		return true
	}

	private fun isCurrent(
		local: CalendarEvent,
		remote: RemoteEvent,
	) = remote.etag != null && local.externalEtag == remote.etag && local.syncState == EventSyncState.SYNCED && !local.isDeleted

	/** A series or single event the provider deleted goes for good; a removed occurrence becomes an EXDATE. */
	private fun remove(
		calendarId: UUID,
		key: RemoteEventKey,
	) {
		val series = events.findByCalendarIdAndExternalIdAndParentEventIdIsNull(calendarId, key.externalId) ?: return
		val occurrence = key.recurrenceId
		if (occurrence == null) {
			deleteForGood(series)
			return
		}
		events.findByParentEventIdOrderByIdAsc(series.id).firstOrNull { it.recurrenceId == occurrence }?.let(::deleteForGood)
		if (series.isRecurring && series.allDay == (occurrence.date != null) && series.exdates.add(occurrence)) {
			changed(events.saveAndFlush(series), ChangeOp.UPDATED)
		}
	}

	/** After a complete listing: removes the synced events (and overrides of kept series) that were not in it. */
	private fun removeMissing(
		calendarId: UUID,
		series: Set<String>,
		overrides: Set<RemoteEventKey>,
	) {
		val all = events.findAllById(events.findIdsByCalendarId(calendarId))
		val (masters, exceptions) = all.partition { !it.isOverride }
		val gone = masters.filter { it.externalId != null && it.externalId !in series }
		gone.forEach(::deleteForGood)
		val goneIds = gone.map { it.id }.toSet()
		val kept = masters.filter { it.id !in goneIds }.associateBy { it.id }
		exceptions
			.filter { it.parentEventId !in goneIds }
			.filter { override ->
				val master = kept[override.parentEventId] ?: return@filter false
				master.externalId != null && RemoteEventKey(master.externalId!!, override.recurrenceId) !in overrides
			}.forEach(::deleteForGood)
	}

	/** Deletes [event] (a series with its overrides) without Trash: the provider no longer has it. */
	private fun deleteForGood(event: CalendarEvent) {
		val overrides = if (event.isOverride) emptyList() else events.findByParentEventIdOrderByIdAsc(event.id)
		(overrides + event).forEach {
			appEvents.publishEvent(ItemDeletedEvent(ItemRef(ItemType.EVENT, it.id)))
			changes.publish(ItemType.EVENT, it.id, ChangeOp.DELETED)
		}
		events.deleteAll(overrides)
		events.delete(event)
		events.flush()
	}

	private fun changed(
		event: CalendarEvent,
		op: ChangeOp,
	) {
		indexer.index(event)
		changes.publish(ItemType.EVENT, event.id, op)
	}

	/** What the user sees of a failure: the adapter's message, or nothing internal for unexpected errors. */
	private fun message(e: RuntimeException): String =
		if (e is CalendarProviderException) e.message.orEmpty().ifBlank { "The provider failed" } else "Unexpected error (see the server log)"

	// Only the type: messages of unexpected errors may contain event content.
	private fun describe(e: RuntimeException): String =
		if (e is CalendarProviderException) "${e.javaClass.simpleName}: ${e.message}" else e.javaClass.name

	/** When the scheduler syncs [view] next (now when it never synced); null when it is not synced. */
	fun nextSyncAt(view: SourceSyncView): Instant? {
		val adapter = view.adapter ?: return null
		return view.status?.lastAttemptAt?.plus(adapter.pollInterval) ?: clock.instant()
	}

	companion object {
		/** The `type` of change events about a source's sync status. */
		const val SOURCE_CHANGE_TYPE = "CALENDAR_SOURCE"

		const val MAX_PAGES = 1000
		const val MAX_ERROR_LENGTH = 1000
		private val COLOR = Regex("^#[0-9a-fA-F]{6}$")
	}
}
