package ch.sonensei.cockpit.calendar.infra.microsoft

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.api.setTiming
import ch.sonensei.cockpit.calendar.infra.microsoft.GraphEventMapping.text
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.recurrence.OccurrenceExpander
import ch.sonensei.cockpit.calendar.recurrence.OccurrenceRange
import ch.sonensei.cockpit.calendar.sync.CalendarProviderAdapter
import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.calendar.sync.RemoteCalendar
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventChangedException
import ch.sonensei.cockpit.calendar.sync.RemoteEventChanges
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs

/**
 * Two-way sync with Microsoft 365 / Outlook calendars (SPEC.md §6.2) through Microsoft Graph v1.0, authorized by the
 * OAuth connection named in the source's config (`{"connectionId": "<uuid>"}`).
 *
 * Pull: `calendarView/delta` over a window of [PAST_DAYS] back to [FUTURE_DAYS] ahead, incrementally with the delta
 * link of the last run. Graph's delta lists occurrences, not series: a series is read from its master (`seriesMasterId`)
 * and the master's instances in the window, which give its exceptions and, as the occurrences its pattern has but Graph
 * no longer does, its EXDATEs. A delta that removed anything (Graph names only the removed id, so a removed occurrence
 * cannot be told from a removed event) and a delta link older than [REFRESH_DAYS] (the window moves on) start a full
 * listing in the same run (`complete`), as does Graph answering 410 (delta token expired).
 *
 * Push: creates (with `transactionId`, so a retried create is not doubled) and updates that send only the fields that
 * differ from Graph's copy (a series keeps its exceptions, attendees are not invited again), with `If-Match` on the
 * event's etag; 412 is [RemoteEventChangedException]. An update with nothing to send succeeds whatever the etag (Graph
 * already has what the cockpit wants) and returns Graph's version. An occurrence of a series is changed or deleted
 * through its instance (a series gone at Microsoft has none); EXDATEs and cancelled overrides delete instances. "This and following" arrives from the engine as the old
 * series ending (UNTIL, an `endDate`) plus a new series with its exceptions, which maps onto these calls. Exchange
 * drops a series' exceptions when its pattern changes, so those in the window that the new pattern still has are put
 * back afterwards. Events are addressed through their calendar (`/me/calendars/{calendar}/events/{id}`), which also
 * reaches calendars of other mailboxes shared with the user.
 *
 * Cursors are JSON: the delta link and the window's anchor date between runs; within a run the next page's link, whether
 * the run is a full listing, and the series already read.
 */
@Component
class MicrosoftCalendarAdapter(
	tokens: OAuthTokenProvider,
	private val json: JsonMapper,
	private val expander: OccurrenceExpander,
	private val clock: Clock,
	@Value("\${cockpit.calendar.microsoft.api-url:${GraphClient.DEFAULT_API_URL}}") apiUrl: String,
) : CalendarProviderAdapter {
	private val log = LoggerFactory.getLogger(javaClass)
	private val client = GraphClient(apiUrl, tokens, json)
	private val restored = RestoredEtags()

	override val sourceType = CalendarSourceType.MICROSOFT
	override val pollInterval: Duration = Duration.ofMinutes(5)

	override fun listCalendars(source: CalendarSource): List<RemoteCalendar> {
		val connection = connectionId(source)
		val result = mutableListOf<RemoteCalendar>()
		var page = expectOk(client.call(connection, HttpMethod.GET, "/me/calendars"), "Listing the calendars")
		var pages = 1
		while (true) {
			page.path("value").forEach { result += GraphEventMapping.toCalendar(it) }
			val next = page.text(NEXT_LINK) ?: break
			if (++pages > MAX_PAGES) throw CalendarProviderException("Microsoft Graph sent too many pages of calendars")
			page = expectOk(client.follow(connection, client.relative(next)), "Listing the calendars")
		}
		return result
	}

	// --- pull

	/** Where a run stands: [link] to fetch next, the window's [anchor], a [full] listing, the series already read. */
	private data class Cursor(
		val mode: String,
		val anchor: LocalDate,
		val link: String? = null,
		val full: Boolean = false,
		val seen: Set<String> = emptySet(),
	)

	override fun fetchEvents(
		source: CalendarSource,
		calendarExternalId: String,
		cursor: String?,
	): RemoteEventChanges {
		val connection = connectionId(source)
		val state = readCursor(cursor)
		val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
		val restart =
			state == null ||
				state.mode == RESTART ||
				state.link == null ||
				(state.mode == DELTA && state.anchor.plusDays(REFRESH_DAYS).isBefore(today.plusDays(1)))
		val run =
			if (restart) {
				Cursor(NEXT, today, full = true)
			} else {
				// A next page continues the run; a delta link starts a new one.
				val continuing = state.mode == NEXT
				val seen = if (continuing) state.seen else emptySet()
				Cursor(NEXT, state.anchor, state.link, full = continuing && state.full, seen = seen)
			}
		val window = window(run.anchor)
		val response =
			if (restart) {
				client.call(
					connection,
					HttpMethod.GET,
					"/me/calendars/{calendar}/calendarView/delta",
					mapOf("calendar" to calendarExternalId),
					mapOf("startDateTime" to window.from.toString(), "endDateTime" to window.to.toString()),
				)
			} else {
				client.follow(connection, run.link!!)
			}
		if (response.status == 410 && !run.full) {
			log.info("Microsoft Graph expired the delta token of a calendar; listing it completely")
			return fetchEvents(source, calendarExternalId, null)
		}
		val page = expectOk(response, "Fetching the events")
		val events = mutableListOf<RemoteEvent>()
		val seen = run.seen.toMutableSet()
		var removed = false
		for (item in page.path("value")) {
			if (item.has("@removed")) {
				removed = true
				continue
			}
			try {
				val type = item.text("type")
				val seriesId = if (type == GraphEventMapping.SERIES_MASTER) item.text("id") else item.text("seriesMasterId")
				when {
					seriesId != null -> if (seen.add(seriesId)) events += readSeries(connection, calendarExternalId, seriesId, window)
					else -> events += GraphEventMapping.toRemote(item, RemoteEventKey(requireId(item)))
				}
			} catch (e: RuntimeException) {
				if (e !is IllegalArgumentException && e !is DateTimeException) throw e
				log.warn("Skipped a Microsoft event of a calendar that could not be read: {}", e.message)
			}
		}
		if (removed && !run.full) {
			return RemoteEventChanges(events = events, cursor = writeCursor(Cursor(RESTART, run.anchor)), hasMore = true)
		}
		val next = page.text(NEXT_LINK)
		val delta = page.text(DELTA_LINK)
		val nextCursor =
			when {
				next != null -> writeCursor(run.copy(link = client.relative(next), seen = seen))
				delta != null -> writeCursor(Cursor(DELTA, run.anchor, client.relative(delta)))
				else -> null
			}
		return RemoteEventChanges(events = events, cursor = nextCursor, complete = run.full, hasMore = next != null)
	}

	/** From midnight (UTC) [PAST_DAYS] before [anchor] to [FUTURE_DAYS] after it. */
	private fun window(anchor: LocalDate) =
		OccurrenceRange(
			anchor.minusDays(PAST_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant(),
			anchor.plusDays(FUTURE_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant(),
			ZoneOffset.UTC,
		)

	/**
	 * Series [seriesId]: its master with the EXDATEs of [window] first, then its exceptions in [window]. Empty when
	 * Graph no longer has it.
	 */
	private fun readSeries(
		connection: UUID,
		calendar: String,
		seriesId: String,
		window: OccurrenceRange,
	): List<RemoteEvent> {
		val response = client.call(connection, HttpMethod.GET, EVENT, eventPath(calendar, seriesId))
		if (response.status == 404) return emptyList()
		val master = expectOk(response, "Fetching a series")
		val key = RemoteEventKey(seriesId)
		val plain = GraphEventMapping.toRemote(master, key)
		if (plain.rrule == null) return listOf(plain)
		val instances = instances(connection, calendar, seriesId, window.from, window.to)
		val expected = expected(plain, window)
		val matched = mutableSetOf<OccurrenceId>()
		val exceptions = mutableListOf<RemoteEvent>()
		for (instance in instances) {
			val original = GraphEventMapping.originalStart(instance)
			val occurrence = nearest(expected, original)
			occurrence?.let(matched::add)
			if (instance.text("type") == GraphEventMapping.EXCEPTION) {
				val id = occurrence ?: fallbackOccurrence(original, plain.timing)
				try {
					exceptions += GraphEventMapping.toRemote(instance, RemoteEventKey(seriesId, id))
				} catch (e: RuntimeException) {
					if (e !is IllegalArgumentException && e !is DateTimeException) throw e
					log.warn("Skipped a Microsoft exception of a series that could not be read: {}", e.message)
				}
			}
		}
		// Next to the window's edges, Graph and the expansion may disagree about what is in it.
		val inner = window.from.plus(EDGE)..window.to.minus(EDGE)
		val exdates = expected.filter { it !in matched && instantOf(it) in inner }.toSet()
		return listOf(plain.copy(exdates = exdates)) + exceptions
	}

	/** The original starts of [series]'s occurrences in [window], expanded like the cockpit expands it. */
	private fun expected(
		series: RemoteEvent,
		window: OccurrenceRange,
	): List<OccurrenceId> {
		val event = CalendarEvent(PLACEHOLDER_CALENDAR, series.title)
		event.setTiming(series.timing)
		event.rrule = series.rrule
		return expander.expandSeries(event, emptyList(), window).mapNotNull { it.occurrenceId }
	}

	/** Every instance (occurrences and exceptions) of series [seriesId] from [from] to [to]. */
	private fun instances(
		connection: UUID,
		calendar: String,
		seriesId: String,
		from: Instant,
		to: Instant,
	): List<JsonNode> =
		instancesOrNull(connection, calendar, seriesId, from, to)
			?: throw CalendarProviderException("Fetching the occurrences of a series failed at Microsoft (HTTP 404)")

	/** Like [instances], but null when Graph no longer has series [seriesId] (404). */
	private fun instancesOrNull(
		connection: UUID,
		calendar: String,
		seriesId: String,
		from: Instant,
		to: Instant,
	): List<JsonNode>? {
		val result = mutableListOf<JsonNode>()
		val first =
			client.call(
				connection,
				HttpMethod.GET,
				"$EVENT/instances",
				eventPath(calendar, seriesId),
				mapOf("startDateTime" to from.toString(), "endDateTime" to to.toString()),
			)
		if (first.status == 404) return null
		var page = expectOk(first, "Fetching the occurrences of a series")
		var pages = 1
		while (true) {
			page.path("value").forEach { result.add(it) }
			val next = page.text(NEXT_LINK) ?: break
			if (++pages > MAX_PAGES) throw CalendarProviderException("Microsoft Graph sent too many pages of occurrences")
			page = expectOk(client.follow(connection, client.relative(next)), "Fetching the occurrences of a series")
		}
		return result
	}

	/** The occurrence of [candidates] that starts closest to [instant], within half a day (Graph repeats daily at most). */
	private fun nearest(
		candidates: Collection<OccurrenceId>,
		instant: Instant,
	): OccurrenceId? =
		candidates
			.map { it to abs(Duration.between(instantOf(it), instant).toMinutes()) }
			.filter { it.second < HALF_DAY_MINUTES }
			.minByOrNull { it.second }
			?.first

	private fun instantOf(occurrence: OccurrenceId): Instant = occurrence.at ?: occurrence.date!!.atStartOfDay(ZoneOffset.UTC).toInstant()

	/** The occurrence an exception without a match in the expansion stands for: its instant, or its day. */
	private fun fallbackOccurrence(
		original: Instant,
		timing: Timing,
	): OccurrenceId =
		when (timing) {
			is Timing.Timed -> OccurrenceId.of(original)
			// Midnight in some zone: the nearest midnight in UTC.
			is Timing.AllDay -> OccurrenceId.of(original.plus(Duration.ofHours(12)).atZone(ZoneOffset.UTC).toLocalDate())
		}

	// --- push

	override fun createEvent(
		source: CalendarSource,
		calendarExternalId: String,
		event: RemoteEvent,
	): RemoteEvent {
		val connection = connectionId(source)
		val occurrence = event.key.recurrenceId
		if (occurrence != null) {
			// The instance exists with its series: its exception is a change of it.
			val instance =
				findInstance(connection, calendarExternalId, event.key.externalId, occurrence)
					?: throw CalendarProviderException("Microsoft has no such occurrence of the series")
			return writeInstance(connection, calendarExternalId, instance, event, ifMatch = null)
		}
		val body = body(event, recurrence = event.rrule != null)
		body.put("transactionId", event.key.externalId)
		val created =
			expectOk(
				client.call(connection, HttpMethod.POST, "/me/calendars/{calendar}/events", mapOf("calendar" to calendarExternalId), body = body),
				"Creating an event",
			)
		val id = requireId(created)
		if (event.rrule == null) return answer(created, RemoteEventKey(id))
		deleteOccurrences(connection, calendarExternalId, id, event.exdates)
		return currentSeries(connection, calendarExternalId, id)
	}

	override fun updateEvent(
		source: CalendarSource,
		calendarExternalId: String,
		event: RemoteEvent,
		ifMatch: String?,
	): RemoteEvent {
		val connection = connectionId(source)
		val occurrence = event.key.recurrenceId
		if (occurrence != null) {
			val instance =
				findInstance(connection, calendarExternalId, event.key.externalId, occurrence)
					?: throw RemoteEventChangedException("The occurrence was deleted at Microsoft")
			return writeInstance(connection, calendarExternalId, instance, event, ifMatch)
		}
		val id = event.key.externalId
		val response = client.call(connection, HttpMethod.GET, EVENT, eventPath(calendarExternalId, id))
		if (response.status == 404) throw RemoteEventChangedException("The event was deleted at Microsoft")
		val current = expectOk(response, "Fetching an event")
		val master = current.text("type") == GraphEventMapping.SERIES_MASTER
		val recurrence = event.rrule != null || master
		val desired = body(event, recurrence)
		val changes = GraphEventMapping.changes(json, desired, currentBody(current, RemoteEventKey(id), recurrence) ?: json.createObjectNode())
		GraphEventMapping.keepAttendeeTypes(changes, current)
		// Exchange drops a series' exceptions when its pattern changes: read them first to put back what it dropped.
		val window = window(LocalDate.now(clock.withZone(ZoneOffset.UTC)))
		val exceptions =
			if (master && event.rrule != null && changes.has("recurrence")) {
				instances(connection, calendarExternalId, id, window.from, window.to).filter { it.text("type") == GraphEventMapping.EXCEPTION }
			} else {
				emptyList()
			}
		// Nothing to send: Graph already has what the cockpit wants, so no change at Microsoft is lost, whatever the etag.
		val stored = if (changes.isEmpty) current else patch(connection, calendarExternalId, id, changes, ifMatch, "Changing an event")
		if (event.rrule == null) return answer(stored, RemoteEventKey(id))
		restoreExceptions(connection, calendarExternalId, id, exceptions, window)
		deleteOccurrences(connection, calendarExternalId, id, event.exdates)
		return currentSeries(connection, calendarExternalId, id)
	}

	override fun deleteEvent(
		source: CalendarSource,
		calendarExternalId: String,
		key: RemoteEventKey,
		ifMatch: String?,
	): String? {
		val connection = connectionId(source)
		val id =
			key.recurrenceId?.let { occurrence ->
				// Already gone.
				findInstance(connection, calendarExternalId, key.externalId, occurrence)?.let(::requireId) ?: return null
			} ?: key.externalId
		delete(connection, calendarExternalId, id, ifMatch)
		return null
	}

	/**
	 * Changes the instance [instance] like [event] (an override); a cancelled override deletes it. When Graph already has
	 * what [event] says, nothing is sent and Graph's version is returned whatever [ifMatch] is: no change at Microsoft
	 * can be lost. An instance this adapter put back after a pattern change counts as the version it was put back from.
	 */
	private fun writeInstance(
		connection: UUID,
		calendar: String,
		instance: JsonNode,
		event: RemoteEvent,
		ifMatch: String?,
	): RemoteEvent {
		val id = requireId(instance)
		val expected = ifMatch?.let { restored.current(calendar, id, instance.text(ETAG), it) }
		if (event.status == EventStatus.CANCELLED) {
			delete(connection, calendar, id, expected)
			restored.forget(calendar, id)
			return event.copy(etag = null)
		}
		val changes =
			GraphEventMapping.changes(
				json,
				body(event, recurrence = false),
				currentBody(instance, event.key, false) ?: json.createObjectNode(),
			)
		GraphEventMapping.keepAttendeeTypes(changes, instance)
		if (changes.isEmpty) return answer(instance, event.key)
		val stored = patch(connection, calendar, id, changes, expected, "Changing an occurrence")
		restored.forget(calendar, id)
		return answer(stored, event.key)
	}

	/**
	 * The instance of series [seriesId] that originally starts at [occurrence], looked up in the weeks around it and,
	 * when it is not there (an exception moved further away), in the whole sync window; null when there is none, also
	 * when the series itself is gone at Microsoft.
	 */
	private fun findInstance(
		connection: UUID,
		calendar: String,
		seriesId: String,
		occurrence: OccurrenceId,
	): JsonNode? {
		val at = instantOf(occurrence)
		val near = instancesOrNull(connection, calendar, seriesId, at.minus(INSTANCE_SEARCH), at.plus(INSTANCE_SEARCH)) ?: return null
		instanceAt(near, at)?.let { return it }
		val window = window(LocalDate.now(clock.withZone(ZoneOffset.UTC)))
		return instanceAt(instancesOrNull(connection, calendar, seriesId, window.from, window.to) ?: return null, at)
	}

	/** The instance of [instances] that originally starts closest to [at], within half a day. */
	private fun instanceAt(
		instances: List<JsonNode>,
		at: Instant,
	): JsonNode? =
		instances
			.map { it to abs(Duration.between(GraphEventMapping.originalStart(it), at).toMinutes()) }
			.filter { it.second < HALF_DAY_MINUTES }
			.minByOrNull { it.second }
			?.first

	/**
	 * Puts back [exceptions] (series [seriesId]'s exceptions in [window] before its pattern changed) that Graph turned
	 * back into plain occurrences: each such occurrence is patched with the fields the exception had. Exceptions whose
	 * occurrence the new pattern no longer has stay gone. Best effort: a failure leaves that occurrence as the pattern has
	 * it (the series itself is already changed).
	 *
	 * Each occurrence that has the exception's fields again (put back, or kept by Graph) gets a new etag although nothing
	 * the cockpit knows of changed: [restored] remembers that it stands for the exception's old etag, so that the engine's
	 * next write of that override (an `ALL` change writes every override after the series) is not taken as a change at
	 * Microsoft.
	 */
	private fun restoreExceptions(
		connection: UUID,
		calendar: String,
		seriesId: String,
		exceptions: List<JsonNode>,
		window: OccurrenceRange,
	) {
		if (exceptions.isEmpty()) return
		val after = instances(connection, calendar, seriesId, window.from, window.to)
		for (exception in exceptions) {
			val instance = instanceAt(after, GraphEventMapping.originalStart(exception)) ?: continue
			val key = RemoteEventKey(seriesId)
			val wanted = currentBody(exception, key, false) ?: continue
			val changes = GraphEventMapping.changes(json, wanted, currentBody(instance, key, false) ?: json.createObjectNode())
			GraphEventMapping.keepAttendeeTypes(changes, exception)
			val now =
				when {
					changes.isEmpty -> instance
					// Kept by Graph, but changed: not the exception any more.
					instance.text("type") == GraphEventMapping.EXCEPTION -> continue
					else ->
						try {
							patch(connection, calendar, requireId(instance), changes, null, "Restoring a changed occurrence")
						} catch (e: CalendarProviderException) {
							log.warn("Could not restore a changed occurrence of a Microsoft series: {}", e.message)
							continue
						}
				}
			restored.remember(calendar, now.text("id") ?: continue, now.text(ETAG), exception.text(ETAG))
		}
	}

	/** Deletes the instances of series [seriesId] at [exdates] (those in the current window) that Graph still has. */
	private fun deleteOccurrences(
		connection: UUID,
		calendar: String,
		seriesId: String,
		exdates: Set<OccurrenceId>,
	) {
		if (exdates.isEmpty()) return
		val window = window(LocalDate.now(clock.withZone(ZoneOffset.UTC)))
		val wanted = exdates.filter { instantOf(it) in window.from..window.to }
		if (wanted.isEmpty()) return
		val instances = instances(connection, calendar, seriesId, window.from, window.to)
		val byOccurrence = instances.groupBy { nearest(wanted, GraphEventMapping.originalStart(it)) }
		wanted.forEach { exdate -> byOccurrence[exdate]?.forEach { delete(connection, calendar, requireId(it), null) } }
	}

	/** Series [seriesId] as Graph has it now, with its EXDATEs. */
	private fun currentSeries(
		connection: UUID,
		calendar: String,
		seriesId: String,
	): RemoteEvent =
		try {
			readSeries(connection, calendar, seriesId, window(LocalDate.now(clock.withZone(ZoneOffset.UTC)))).firstOrNull()
				?: throw RemoteEventChangedException("The event was deleted at Microsoft")
		} catch (e: RuntimeException) {
			if (e !is IllegalArgumentException && e !is DateTimeException) throw e
			throw CalendarProviderException("Microsoft answered with an event that could not be read", e)
		}

	private fun patch(
		connection: UUID,
		calendar: String,
		id: String,
		changes: JsonNode,
		ifMatch: String?,
		what: String,
	): JsonNode {
		val response = client.call(connection, HttpMethod.PATCH, EVENT, eventPath(calendar, id), body = changes, ifMatch = ifMatch)
		when (response.status) {
			// Deleted at Microsoft meanwhile: the refetch shows that.
			404 -> throw RemoteEventChangedException("The event was deleted at Microsoft")
			412 -> throw RemoteEventChangedException()
		}
		return expectOk(response, what)
	}

	private fun delete(
		connection: UUID,
		calendar: String,
		id: String,
		ifMatch: String?,
	) {
		val response = client.call(connection, HttpMethod.DELETE, EVENT, eventPath(calendar, id), ifMatch = ifMatch)
		when {
			// Already gone.
			response.status == 404 -> return
			response.status == 412 -> throw RemoteEventChangedException()
			!response.ok -> throw CalendarProviderException("Deleting an event failed at Microsoft (HTTP ${response.status})")
		}
	}

	/** [event] as a Graph body; a rule Graph cannot express is refused with a message for the user. */
	private fun body(
		event: RemoteEvent,
		recurrence: Boolean,
	) = try {
		GraphEventMapping.toBody(json, event, recurrence)
	} catch (e: IllegalArgumentException) {
		throw CalendarProviderException(e.message ?: "Microsoft calendars cannot store this event", e)
	}

	/** Graph's [node] as the body the cockpit would send for it, to compare with; null when it cannot be read. */
	private fun currentBody(
		node: JsonNode,
		key: RemoteEventKey,
		recurrence: Boolean,
	) = try {
		GraphEventMapping.toBody(json, GraphEventMapping.toRemote(node, key), recurrence)
	} catch (e: RuntimeException) {
		if (e !is IllegalArgumentException && e !is DateTimeException) throw e
		null
	}

	/** Graph's answer to a write as the stored event. */
	private fun answer(
		node: JsonNode,
		key: RemoteEventKey,
	): RemoteEvent =
		try {
			GraphEventMapping.toRemote(node, key)
		} catch (e: RuntimeException) {
			if (e !is IllegalArgumentException && e !is DateTimeException) throw e
			throw CalendarProviderException("Microsoft answered with an event that could not be read", e)
		}

	private fun expectOk(
		response: GraphClient.Response,
		what: String,
	): JsonNode {
		if (!response.ok) throw CalendarProviderException("$what failed at Microsoft (HTTP ${response.status})")
		if (!response.body.isObject) throw CalendarProviderException("$what: Microsoft answered without a JSON object")
		return response.body
	}

	/** The variables of [EVENT] for event [id] in [calendar]. */
	private fun eventPath(
		calendar: String,
		id: String,
	) = mapOf("calendar" to calendar, "event" to id)

	private fun requireId(node: JsonNode): String =
		node.text("id") ?: throw IllegalArgumentException("Microsoft Graph sent an event without id")

	// --- cursor and config

	private fun readCursor(cursor: String?): Cursor? {
		if (cursor == null) return null
		return try {
			val node = json.readTree(cursor)
			Cursor(
				mode = node.text("mode") ?: return null,
				anchor = LocalDate.parse(node.text("anchor") ?: return null),
				link = node.text("link"),
				full = node.path("full").asBoolean(false),
				seen = node.path("seen").mapNotNull { it.asString(null) }.toSet(),
			)
		} catch (e: JacksonException) {
			null
		} catch (e: DateTimeException) {
			null
		}
	}

	private fun writeCursor(cursor: Cursor): String {
		val node = json.createObjectNode().put("mode", cursor.mode).put("anchor", DateTimeFormatter.ISO_LOCAL_DATE.format(cursor.anchor))
		cursor.link?.let { node.put("link", it) }
		if (cursor.mode == NEXT) {
			node.put("full", cursor.full)
			val seen = node.putArray("seen")
			cursor.seen.sorted().forEach(seen::add)
		}
		return json.writeValueAsString(node)
	}

	/** The OAuth connection of [source], from its config. */
	private fun connectionId(source: CalendarSource): UUID {
		val id =
			try {
				json.readTree(source.config).text(CONNECTION_ID)
			} catch (e: JacksonException) {
				null
			}
		return id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
			?: throw CalendarProviderException("The source is not linked to a Microsoft connection")
	}

	/**
	 * The etags of occurrences that [restoreExceptions] gave back the fields of an exception, each with the etag that
	 * exception had: the same content in a new version. Bounded (the oldest go first) and kept in memory only; a forgotten
	 * entry just makes a write of such an override "changed remotely" again.
	 */
	private class RestoredEtags {
		private val entries =
			object : LinkedHashMap<String, Pair<String, String>>() {
				override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>) = size > MAX_RESTORED
			}

		/** Notes that instance [id] of [calendar] at version [etag] has the content it had at version [before]. */
		fun remember(
			calendar: String,
			id: String,
			etag: String?,
			before: String?,
		) {
			if (etag == null || before == null || etag == before) return
			synchronized(entries) { entries["$calendar/$id"] = etag to before }
		}

		/** The etag to send for a write of instance [id] at version [etag] that expects version [ifMatch]. */
		fun current(
			calendar: String,
			id: String,
			etag: String?,
			ifMatch: String,
		): String = if (etag != null && synchronized(entries) { entries["$calendar/$id"] } == (etag to ifMatch)) etag else ifMatch

		fun forget(
			calendar: String,
			id: String,
		) {
			synchronized(entries) { entries.remove("$calendar/$id") }
		}
	}

	companion object {
		/** Key of the OAuth connection's id in a Microsoft source's config. */
		const val CONNECTION_ID = "connectionId"

		/** How far back and ahead events are synced, and after how many days the window moves (a full listing). */
		const val PAST_DAYS = 365L
		const val FUTURE_DAYS = 730L
		const val REFRESH_DAYS = 7L

		/** An event, addressed through its calendar: `/me/events` only has the events of the user's own mailbox. */
		private const val EVENT = "/me/calendars/{calendar}/events/{event}"
		private const val NEXT_LINK = "@odata.nextLink"
		private const val DELTA_LINK = "@odata.deltaLink"
		private const val ETAG = "@odata.etag"
		private const val DELTA = "delta"
		private const val NEXT = "next"
		private const val RESTART = "restart"
		private const val MAX_PAGES = 100
		private const val MAX_RESTORED = 1000
		private const val HALF_DAY_MINUTES = 12 * 60L
		private val EDGE: Duration = Duration.ofDays(2)
		private val INSTANCE_SEARCH: Duration = Duration.ofDays(35)
		private val PLACEHOLDER_CALENDAR = UUID(0, 0)
	}
}
