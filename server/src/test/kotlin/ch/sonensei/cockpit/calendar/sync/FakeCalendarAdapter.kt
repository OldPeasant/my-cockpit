package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Registers a [FakeCalendarAdapter] for CALDAV sources, and a registry with only that adapter: every other non-LOCAL
 * type stays without adapter in these tests, whichever real adapters exist.
 */
@TestConfiguration(proxyBeanMethods = false)
class FakeCalendarAdapterConfiguration {
	@Bean
	fun fakeCalendarAdapter() = FakeCalendarAdapter()

	@Bean
	@Primary
	fun fakeOnlyCalendarProviderRegistry(fake: FakeCalendarAdapter) = CalendarProviderRegistry(listOf(fake))
}

/**
 * An in-memory provider: calendars, events with etags, and a change log whose position is the sync cursor. A fetch
 * without cursor (or with [rejectCursor]) lists everything (`complete`); with a cursor it returns the changes after it.
 * Writes check `If-Match` like a real provider (412 as [RemoteEventChangedException]).
 */
class FakeCalendarAdapter : CalendarProviderAdapter {
	override val sourceType = CalendarSourceType.CALDAV
	override val pollInterval: Duration = Duration.ofMinutes(10)
	override var seriesShareEtag = false

	val calendars = mutableListOf<RemoteCalendar>()
	private val store = mutableMapOf<String, MutableMap<RemoteEventKey, RemoteEvent>>()
	private val changeLog = mutableListOf<Pair<String, RemoteEventKey>>()
	private var etags = 0

	/** The cursors fetches were called with, per calendar. */
	val fetchCursors = mutableListOf<Pair<String, String?>>()

	/** The writes the engine made: `create|update|delete <uid>[@<recurrence>] if-match=<etag>`. */
	val writes = mutableListOf<String>()

	var failListing: RuntimeException? = null
	val failFetch = mutableMapOf<String, RuntimeException>()
	var failWrites: RuntimeException? = null

	/** Fails only the writes of one kind (`create`, `update`, `delete`). */
	val failWriteOps = mutableMapOf<String, RuntimeException>()

	/** Answers a fetch instead of the store when it returns non-null (to script pages or act during a run). */
	var fetchHook: ((calendar: String, cursor: String?) -> RemoteEventChanges?)? = null
	var rejectCursor = false

	/** How the provider normalizes what it is sent (it answers with the result), e.g. trimming the title. */
	var normalizeWrites: (RemoteEvent) -> RemoteEvent = { it }

	/** Page size of fetches (to exercise `hasMore`). */
	var pageSize = Int.MAX_VALUE

	fun reset() {
		calendars.clear()
		store.clear()
		changeLog.clear()
		fetchCursors.clear()
		writes.clear()
		failListing = null
		failFetch.clear()
		failWrites = null
		failWriteOps.clear()
		fetchHook = null
		rejectCursor = false
		pageSize = Int.MAX_VALUE
		seriesShareEtag = false
		normalizeWrites = { it }
	}

	fun addCalendar(
		externalId: String,
		name: String = externalId,
		color: String? = null,
		readOnly: Boolean = false,
	) {
		calendars += RemoteCalendar(externalId, name, color, readOnly)
		store.getOrPut(externalId) { mutableMapOf() }
	}

	/**
	 * Stores [event] (as the provider changing it) with a new etag; returns the stored copy. With [seriesShareEtag] the
	 * series is one resource (CalDAV): every event of it gets the new etag.
	 */
	fun put(
		calendar: String,
		event: RemoteEvent,
	): RemoteEvent {
		val stored = event.copy(etag = "etag-${++etags}")
		store.getValue(calendar)[event.key] = stored
		changeLog += calendar to event.key
		if (seriesShareEtag) shareEtag(calendar, event.key.externalId, stored.etag!!)
		return stored
	}

	fun remove(
		calendar: String,
		key: RemoteEventKey,
	) {
		store.getValue(calendar).remove(key)
		changeLog += calendar to key
	}

	private fun shareEtag(
		calendar: String,
		uid: String,
		etag: String,
	) {
		val events = store.getValue(calendar)
		events.keys.filter { it.externalId == uid }.forEach { events[it] = events.getValue(it).copy(etag = etag) }
	}

	fun event(
		calendar: String,
		key: RemoteEventKey,
	): RemoteEvent? = store.getValue(calendar)[key]

	fun events(calendar: String): Map<RemoteEventKey, RemoteEvent> = store.getValue(calendar).toMap()

	override fun listCalendars(source: CalendarSource): List<RemoteCalendar> {
		failListing?.let { throw it }
		return calendars.toList()
	}

	override fun fetchEvents(
		source: CalendarSource,
		calendarExternalId: String,
		cursor: String?,
	): RemoteEventChanges {
		fetchCursors += calendarExternalId to cursor
		failFetch[calendarExternalId]?.let { throw it }
		fetchHook?.invoke(calendarExternalId, cursor)?.let { return it }
		val events = store.getValue(calendarExternalId)
		// Cursor "<log position>" or "full:<offset>" while paging through a complete listing.
		if (cursor == null || rejectCursor || cursor.startsWith("full:")) {
			val offset = cursor?.takeIf { it.startsWith("full:") }?.substringAfter("full:")?.toInt() ?: 0
			val all = events.values.sortedBy { it.key.recurrenceId != null }.drop(offset)
			val page = all.take(pageSize)
			val more = all.size > page.size
			return RemoteEventChanges(
				events = page,
				cursor = if (more) "full:${offset + page.size}" else changeLog.size.toString(),
				complete = offset == 0,
				hasMore = more,
			)
		}
		val since = cursor.toInt()
		val keys =
			changeLog
				.drop(since)
				.filter { it.first == calendarExternalId }
				.map { it.second }
				.distinct()
		return RemoteEventChanges(
			events = keys.mapNotNull { events[it] },
			deleted = keys.filter { it !in events },
			cursor = changeLog.size.toString(),
		)
	}

	override fun createEvent(
		source: CalendarSource,
		calendarExternalId: String,
		event: RemoteEvent,
	): RemoteEvent {
		writes += "create ${label(event.key)}"
		failWrites?.let { throw it }
		failWriteOps["create"]?.let { throw it }
		return put(calendarExternalId, normalizeWrites(event))
	}

	override fun updateEvent(
		source: CalendarSource,
		calendarExternalId: String,
		event: RemoteEvent,
		ifMatch: String?,
	): RemoteEvent {
		writes += "update ${label(event.key)} if-match=$ifMatch"
		failWrites?.let { throw it }
		failWriteOps["update"]?.let { throw it }
		val current = store.getValue(calendarExternalId)[event.key]
		if (current != null && ifMatch != null && current.etag != ifMatch) throw RemoteEventChangedException()
		return put(calendarExternalId, normalizeWrites(event))
	}

	override fun deleteEvent(
		source: CalendarSource,
		calendarExternalId: String,
		key: RemoteEventKey,
		ifMatch: String?,
	): String? {
		writes += "delete ${label(key)} if-match=$ifMatch"
		failWrites?.let { throw it }
		failWriteOps["delete"]?.let { throw it }
		val current = store.getValue(calendarExternalId)[key] ?: return null
		if (ifMatch != null && current.etag != ifMatch) throw RemoteEventChangedException()
		remove(calendarExternalId, key)
		if (key.recurrenceId == null) {
			store
				.getValue(calendarExternalId)
				.keys
				.filter { it.externalId == key.externalId }
				.forEach { remove(calendarExternalId, it) }
		} else if (seriesShareEtag) {
			// Removing an exception changes the series resource: it gets a new etag, which the provider returns.
			val etag = "etag-${++etags}"
			shareEtag(calendarExternalId, key.externalId, etag)
			return etag
		}
		return null
	}

	private fun label(key: RemoteEventKey) = key.externalId + (key.recurrenceId?.let { "@${it.at ?: it.date}" } ?: "")

	companion object {
		val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")

		fun timed(
			start: String,
			minutes: Long = 60,
		): Timing.Timed {
			val at = Instant.parse(start)
			return Timing.Timed(at, at.plus(Duration.ofMinutes(minutes)), ZURICH)
		}

		fun remote(
			uid: String,
			title: String,
			start: String = "2026-03-02T09:00:00Z",
			rrule: String? = null,
		) = RemoteEvent(RemoteEventKey(uid), etag = null, title = title, timing = timed(start), rrule = rrule)
	}
}
