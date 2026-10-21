package ch.sonensei.cockpit.calendar.infra.google

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.infra.google.GoogleEventMapping.text
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
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
import java.time.DateTimeException
import java.time.Duration
import java.util.UUID

/**
 * Two-way sync with Google Calendar (SPEC.md §6.2) through the Calendar API v3, authorized by the OAuth connection
 * named in the source's config (`{"connectionId": "<uuid>"}`).
 *
 * - Pull: `events.list` with `singleEvents=false` and `showDeleted=true`, incrementally with the `nextSyncToken` of the
 *   last run. Google answering 410 Gone (token expired) starts a full listing (`complete`). Cancelled events are
 *   deletions; a cancelled instance removes its occurrence. When a series changed in an incremental run, its
 *   cancelled instances are looked up (`iCalUID`), so they stay removed after the series is stored again.
 * - Push: inserts and patches (only the fields the cockpit edits) with `If-Match` on the event's etag; 412 is
 *   [RemoteEventChangedException]. An exception of a series is a patch of the instance `<seriesId>_<start>`, its
 *   removal a delete of that instance. "This and following" arrives from the engine as the old series ending (UNTIL)
 *   plus a new series with its exceptions, which maps onto these calls. Attendees are not notified (`sendUpdates=none`).
 *
 * Cursors: `sync:<token>` between runs; within a run `page:<token>` (incremental) or `full:<token>` (full listing).
 */
@Component
class GoogleCalendarAdapter(
	tokens: OAuthTokenProvider,
	private val json: JsonMapper,
	@Value("\${cockpit.calendar.google.api-url:${GoogleCalendarClient.DEFAULT_API_URL}}") apiUrl: String,
) : CalendarProviderAdapter {
	private val log = LoggerFactory.getLogger(javaClass)
	private val client = GoogleCalendarClient(apiUrl, tokens, json)

	override val sourceType = CalendarSourceType.GOOGLE
	override val pollInterval: Duration = Duration.ofMinutes(5)

	override fun listCalendars(source: CalendarSource): List<RemoteCalendar> {
		val connection = connectionId(source)
		val result = mutableListOf<RemoteCalendar>()
		var pageToken: String? = null
		do {
			val page =
				expectOk(
					client.call(
						connection,
						HttpMethod.GET,
						"/users/me/calendarList",
						query = mapOf("maxResults" to PAGE_SIZE.toString(), "pageToken" to pageToken),
					),
					"Listing the calendars",
				)
			page
				.path("items")
				// Free/busy-only calendars have no event details to show.
				.filter { it.path("deleted").asBoolean(false).not() && it.text("accessRole") != "freeBusyReader" }
				.forEach { result += GoogleEventMapping.toCalendar(it) }
			pageToken = page.text("nextPageToken")
		} while (pageToken != null)
		return result
	}

	override fun fetchEvents(
		source: CalendarSource,
		calendarExternalId: String,
		cursor: String?,
	): RemoteEventChanges {
		val connection = connectionId(source)
		val full = cursor == null || cursor.startsWith(FULL)
		val query =
			mutableMapOf<String, String?>(
				"singleEvents" to "false",
				"showDeleted" to "true",
				"maxResults" to PAGE_SIZE.toString(),
			)
		when {
			cursor == null -> Unit
			cursor.startsWith(SYNC) -> query["syncToken"] = cursor.removePrefix(SYNC)
			cursor.startsWith(PAGE) -> query["pageToken"] = cursor.removePrefix(PAGE)
			cursor.startsWith(FULL) -> query["pageToken"] = cursor.removePrefix(FULL)
			// Not one of ours: start over.
			else -> return fetchEvents(source, calendarExternalId, null)
		}
		val response = client.call(connection, HttpMethod.GET, EVENTS, mapOf("calendar" to calendarExternalId), query)
		if (response.status == 410 && !full) {
			log.info("Google expired the sync token of a calendar; listing it completely")
			return fetchEvents(source, calendarExternalId, null)
		}
		val page = expectOk(response, "Fetching the events")
		val zone = GoogleEventMapping.zone(page.text("timeZone"))
		val defaults = GoogleEventMapping.defaultReminders(page)
		val events = mutableListOf<RemoteEvent>()
		val deleted = mutableListOf<RemoteEventKey>()
		val uids = mutableMapOf<String, String>()
		for (item in page.path("items")) {
			try {
				if (GoogleEventMapping.isCancelled(item)) {
					deleted += GoogleEventMapping.key(item)
				} else {
					val event = GoogleEventMapping.toRemote(item, zone, defaults)
					events += event
					item.text("iCalUID")?.let { uids[event.key.externalId] = it }
				}
			} catch (e: RuntimeException) {
				if (e !is IllegalArgumentException && e !is DateTimeException) throw e
				log.warn("Skipped a Google event of a calendar that could not be read: {}", e.message)
			}
		}
		val withCancellations =
			if (full) {
				events
			} else {
				events.map { event ->
					val uid = uids[event.key.externalId]
					if (event.rrule != null && uid != null) withCancelledInstances(connection, calendarExternalId, event, uid) else event
				}
			}
		val nextPage = page.text("nextPageToken")
		val nextCursor =
			when {
				nextPage != null -> (if (full) FULL else PAGE) + nextPage
				else -> page.text("nextSyncToken")?.let { SYNC + it }
			}
		return RemoteEventChanges(
			events = withCancellations,
			deleted = deleted,
			cursor = nextCursor,
			complete = full,
			hasMore = nextPage != null,
		)
	}

	/**
	 * [series] with the occurrences Google cancelled as EXDATEs: Google keeps them as cancelled instances, which an
	 * incremental listing only sends when they change, not with each change of their series.
	 */
	private fun withCancelledInstances(
		connection: UUID,
		calendarExternalId: String,
		series: RemoteEvent,
		uid: String,
	): RemoteEvent {
		val related =
			expectOk(
				client.call(
					connection,
					HttpMethod.GET,
					EVENTS,
					mapOf("calendar" to calendarExternalId),
					mapOf("iCalUID" to uid, "showDeleted" to "true", "singleEvents" to "false", "maxResults" to MAX_INSTANCES.toString()),
				),
				"Fetching the exceptions of a series",
			)
		val cancelled =
			related
				.path("items")
				.filter { GoogleEventMapping.isCancelled(it) && it.text("recurringEventId") == series.key.externalId }
				.mapNotNull { runCatching { GoogleEventMapping.key(it).recurrenceId }.getOrNull() }
				.filter { (it.date != null) == (series.timing is Timing.AllDay) }
		return if (cancelled.isEmpty()) series else series.copy(exdates = series.exdates + cancelled)
	}

	override fun createEvent(
		source: CalendarSource,
		calendarExternalId: String,
		event: RemoteEvent,
	): RemoteEvent {
		val connection = connectionId(source)
		val occurrence = event.key.recurrenceId
		if (occurrence != null) {
			// The instance exists with its series: its exception is a change of it.
			val response = patch(connection, calendarExternalId, event, ifMatch = null)
			if (response.status == 404 || response.status == 410) {
				throw CalendarProviderException("Google has no such occurrence of the series (HTTP ${response.status})")
			}
			return answer(expectOk(response, "Changing an occurrence"))
		}
		val body = GoogleEventMapping.toBody(json, event, clearRecurrence = false)
		val id = googleId(event.key.externalId)
		id?.let { body.put("id", it) }
		val response =
			client.call(connection, HttpMethod.POST, EVENTS, mapOf("calendar" to calendarExternalId), mapOf("sendUpdates" to "none"), body)
		if (response.status == 409 && id != null) {
			// The id exists: an earlier attempt reached Google but its answer got lost.
			return answer(
				expectOk(
					client.call(connection, HttpMethod.GET, "$EVENTS/{event}", mapOf("calendar" to calendarExternalId, "event" to id)),
					"Fetching a created event",
				),
			)
		}
		return answer(expectOk(response, "Creating an event"))
	}

	override fun updateEvent(
		source: CalendarSource,
		calendarExternalId: String,
		event: RemoteEvent,
		ifMatch: String?,
	): RemoteEvent {
		val response = patch(connectionId(source), calendarExternalId, event, ifMatch)
		when (response.status) {
			// Deleted at Google meanwhile: the refetch shows that.
			404, 410 -> throw RemoteEventChangedException("The event was deleted at Google")
			412 -> throw RemoteEventChangedException()
		}
		return answer(expectOk(response, "Changing an event"))
	}

	override fun deleteEvent(
		source: CalendarSource,
		calendarExternalId: String,
		key: RemoteEventKey,
		ifMatch: String?,
	): String? {
		val response =
			client.call(
				connectionId(source),
				HttpMethod.DELETE,
				"$EVENTS/{event}",
				mapOf("calendar" to calendarExternalId, "event" to eventId(key)),
				mapOf("sendUpdates" to "none"),
				ifMatch = ifMatch,
			)
		when (response.status) {
			// Already gone.
			404, 410 -> return null
			412 -> throw RemoteEventChangedException()
		}
		if (!response.ok) throw CalendarProviderException("Deleting an event failed at Google (HTTP ${response.status})")
		return null
	}

	private fun patch(
		connection: UUID,
		calendarExternalId: String,
		event: RemoteEvent,
		ifMatch: String?,
	): GoogleCalendarClient.Response =
		client.call(
			connection,
			HttpMethod.PATCH,
			"$EVENTS/{event}",
			mapOf("calendar" to calendarExternalId, "event" to eventId(event.key)),
			mapOf("sendUpdates" to "none"),
			GoogleEventMapping.toBody(json, event, clearRecurrence = true),
			ifMatch,
		)

	private fun eventId(key: RemoteEventKey): String =
		key.recurrenceId?.let { GoogleEventMapping.instanceId(key.externalId, it) } ?: key.externalId

	/** Google's answer to a write as the stored event. */
	private fun answer(node: JsonNode): RemoteEvent =
		try {
			GoogleEventMapping.toRemote(node)
		} catch (e: RuntimeException) {
			if (e !is IllegalArgumentException && e !is DateTimeException) throw e
			throw CalendarProviderException("Google answered with an event that could not be read", e)
		}

	private fun expectOk(
		response: GoogleCalendarClient.Response,
		what: String,
	): JsonNode {
		if (!response.ok) throw CalendarProviderException("$what failed at Google (HTTP ${response.status})")
		if (!response.body.isObject) throw CalendarProviderException("$what: Google answered without a JSON object")
		return response.body
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
			?: throw CalendarProviderException("The source is not linked to a Google connection")
	}

	companion object {
		/** Key of the OAuth connection's id in a Google source's config. */
		const val CONNECTION_ID = "connectionId"

		private const val EVENTS = "/calendars/{calendar}/events"
		private const val SYNC = "sync:"
		private const val PAGE = "page:"
		private const val FULL = "full:"
		private const val PAGE_SIZE = 250
		private const val MAX_INSTANCES = 2500
		private val GOOGLE_ID = Regex("[a-v0-9]{5,1024}")

		/**
		 * The Google `id` for a new event whose cockpit id is [externalId] (a UUID: its hex digits, which Google's
		 * base32hex alphabet allows), so a repeated insert is recognized; null lets Google choose one.
		 */
		fun googleId(externalId: String): String? = externalId.replace("-", "").lowercase().takeIf { GOOGLE_ID.matches(it) }
	}
}
