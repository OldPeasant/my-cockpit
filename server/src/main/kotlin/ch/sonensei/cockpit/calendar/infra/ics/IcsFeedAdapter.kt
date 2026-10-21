package ch.sonensei.cockpit.calendar.infra.ics

import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.sync.CalendarProviderAdapter
import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.calendar.sync.RemoteCalendar
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventChanges
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * Read-only ICS subscriptions (SPEC.md §6.1): a source of type `ICS_FEED` with the config `{"url": "<http(s) or
 * webcal URL>"}` has one read-only calendar ([FEED_CALENDAR], named like the source) holding the feed's events.
 *
 * Every poll downloads the feed with the validators (`ETag`, `Last-Modified`) of the last download ([IcsFeedClient]);
 * `304 Not Modified` is no change. A new download is the whole calendar (`complete`), so the engine updates changed
 * events and deletes those the feed no longer has; unchanged events keep their content hash ([IcsEventMapping]) and are
 * not stored again. The cursor is the validators as JSON (none: every poll downloads the feed).
 *
 * Writes are refused: the calendar is read-only, so the event API does not get that far.
 */
@Component
class IcsFeedAdapter internal constructor(
	private val json: JsonMapper,
	private val properties: IcsFeedProperties,
	private val client: IcsFeedClient,
) : CalendarProviderAdapter {
	@Autowired
	constructor(json: JsonMapper, properties: IcsFeedProperties) : this(json, properties, IcsFeedClient(properties))

	private val log = LoggerFactory.getLogger(javaClass)

	override val sourceType = CalendarSourceType.ICS_FEED
	override val pollInterval: Duration = properties.pollInterval

	override fun listCalendars(source: CalendarSource): List<RemoteCalendar> {
		url(source)
		return listOf(RemoteCalendar(FEED_CALENDAR, source.name, readOnly = true))
	}

	override fun fetchEvents(
		source: CalendarSource,
		calendarExternalId: String,
		cursor: String?,
	): RemoteEventChanges {
		if (calendarExternalId != FEED_CALENDAR) throw CalendarProviderException("An ICS feed has only one calendar")
		val validators = cursor?.let(::validators)
		return when (val response = client.fetch(url(source), validators)) {
			FeedResponse.NotModified -> RemoteEventChanges(cursor = cursor)
			is FeedResponse.Fetched -> {
				var skipped = 0
				val events = IcsEventMapping.events(IcsParser.parse(response.text)) { skipped++ }
				if (skipped > 0) log.warn("Skipped {} unreadable events of ICS source {}", skipped, source.id)
				RemoteEventChanges(events = events, cursor = cursorOf(response.validators), complete = true)
			}
		}
	}

	override fun createEvent(
		source: CalendarSource,
		calendarExternalId: String,
		event: RemoteEvent,
	): RemoteEvent = throw readOnly()

	override fun updateEvent(
		source: CalendarSource,
		calendarExternalId: String,
		event: RemoteEvent,
		ifMatch: String?,
	): RemoteEvent = throw readOnly()

	override fun deleteEvent(
		source: CalendarSource,
		calendarExternalId: String,
		key: RemoteEventKey,
		ifMatch: String?,
	): String? = throw readOnly()

	private fun readOnly() = CalendarProviderException("ICS feeds are read-only")

	private fun url(source: CalendarSource): String =
		config(source.config)?.text("url")?.trim()?.ifEmpty { null }
			?: throw CalendarProviderException("The ICS source has no feed URL")

	private fun validators(cursor: String): FeedValidators? = config(cursor)?.let { FeedValidators(it.text(ETAG), it.text(LAST_MODIFIED)) }

	private fun cursorOf(validators: FeedValidators): String? {
		if (validators.etag == null && validators.lastModified == null) return null
		val node = json.createObjectNode()
		validators.etag?.let { node.put(ETAG, it) }
		validators.lastModified?.let { node.put(LAST_MODIFIED, it) }
		return json.writeValueAsString(node)
	}

	private fun config(text: String): JsonNode? =
		try {
			json.readTree(text)?.takeIf { it.isObject }
		} catch (e: JacksonException) {
			null
		}

	private fun JsonNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.asString()

	companion object {
		/** External id of a feed's only calendar. */
		const val FEED_CALENDAR = "feed"
		private const val ETAG = "etag"
		private const val LAST_MODIFIED = "lastModified"
	}
}
