package ch.sonensei.cockpit.calendar.infra.caldav

import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.sync.CalendarProviderAdapter
import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import ch.sonensei.cockpit.calendar.sync.RemoteCalendar
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventChangedException
import ch.sonensei.cockpit.calendar.sync.RemoteEventChanges
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration

/**
 * Two-way sync with CalDAV servers (SPEC.md §6.2, RFC 4791), e.g. Nextcloud, Radicale or iCloud with an app password.
 * The source's config holds the server URL and user name (`{"url": "https://...", "username": "..."}`), its credentials
 * the password, sent with HTTP Basic authentication to the URL's origin only.
 *
 * - Discovery: the URL's `current-user-principal` and its `calendar-home-set` (the URL itself when it names a calendar,
 *   `/.well-known/caldav` when it names the bare host); the calendars there that hold events. Calendars without the
 *   write privilege are read-only.
 * - Pull: `sync-collection` REPORT (RFC 6578) when the calendar has a sync token, else the calendar's `getctag` and the
 *   `getetag` of its resources compared with the last run; changed resources are read with `calendar-multiget`. A sync
 *   token the server no longer accepts starts a full listing (`complete`).
 * - Push: a series is one resource (`<uid>.ics` when the cockpit creates it) holding the master and its overrides, so
 *   every write reads the resource, changes its VEVENT in place and PUTs it back with `If-Match` (new resources with
 *   `If-None-Match: *`); 412 is [RemoteEventChangedException]. Removing an occurrence drops its override and adds an
 *   EXDATE. All events of a series share the resource's etag ([seriesShareEtag]).
 *
 * The cursor is a JSON object: the mode (`sync` or `etag`), the sync token or ctag, and per resource path its etag and
 * UID (a removal only names the path).
 */
@Component
class CalDavCalendarAdapter(
	private val json: JsonMapper,
	private val clock: Clock,
) : CalendarProviderAdapter {
	private val log = LoggerFactory.getLogger(javaClass)
	private val http: HttpClient = CalDavClient.httpClient()

	override val sourceType = CalendarSourceType.CALDAV
	override val pollInterval: Duration = Duration.ofMinutes(10)
	override val seriesShareEtag = true

	// --- discovery

	override fun listCalendars(source: CalendarSource): List<RemoteCalendar> {
		val client = client(source)
		val what = "Finding the calendars"
		val start = client.base
		val props = "<D:current-user-principal/><C:calendar-home-set/><D:resourcetype/>"
		val bareHost = start.rawPath.isNullOrEmpty() || start.rawPath == "/"
		// A server may refuse "/" but answer at the well-known URL, so a 403 there only counts when nothing else answers.
		var forbidden = false
		val probe = { uri: URI ->
			val response = client.propfind(uri, 0, props, what)
			if (response.status == FORBIDDEN) forbidden = true
			if (response.status == MULTI_STATUS) MultiStatus.parse(response.body, what).responses.firstOrNull() else null
		}
		val first =
			probe(start)
				?: (if (bareHost) probe(start.resolve(WELL_KNOWN)) else null)
				?: throw if (forbidden) denied(what) else CalendarProviderException("No CalDAV service found at the source's URL")
		if (isCalendar(first)) return calendarsIn(client, client.resolve(first.href) ?: start, 0)
		val home =
			hrefIn(client, first, CalDavClient.CALDAV, "calendar-home-set")
				?: hrefIn(client, first, CalDavClient.DAV, "current-user-principal")?.let { principal ->
					propfind(client, principal, 0, "<C:calendar-home-set/>", what)?.let { hrefIn(client, it, CalDavClient.CALDAV, "calendar-home-set") }
				}
				?: throw CalendarProviderException("The CalDAV server names no calendar home for the user")
		return calendarsIn(client, home, 1)
	}

	private fun calendarsIn(
		client: CalDavClient,
		uri: URI,
		depth: Int,
	): List<RemoteCalendar> {
		val response =
			client.propfind(
				uri,
				depth,
				"<D:resourcetype/><D:displayname/><A:calendar-color/><D:current-user-privilege-set/><C:supported-calendar-component-set/>",
				"Listing the calendars",
			)
		if (response.status !=
			MULTI_STATUS
		) {
			throw CalendarProviderException("Listing the calendars failed at the CalDAV server (HTTP ${response.status})")
		}
		return MultiStatus
			.parse(response.body, "Listing the calendars")
			.responses
			.filter { isCalendar(it) && holdsEvents(it) }
			.mapNotNull { calendar ->
				val target = client.resolve(calendar.href) ?: return@mapNotNull null
				val path = target.path.orEmpty()
				RemoteCalendar(
					externalId = target.rawPath,
					name = calendar.text(CalDavClient.DAV, "displayname") ?: path.trimEnd('/').substringAfterLast('/'),
					color = calendar.text(CalDavClient.APPLE, "calendar-color")?.take(COLOR_LENGTH),
					readOnly = isReadOnly(calendar),
				)
			}
	}

	/**
	 * PROPFIND with depth 0; the first response, or null when the resource does not answer with a multistatus.
	 *
	 * @throws CalendarProviderException when the server denies access (403)
	 */
	private fun propfind(
		client: CalDavClient,
		uri: URI,
		depth: Int,
		props: String,
		what: String,
	): DavResponse? {
		val response = client.propfind(uri, depth, props, what)
		if (response.status == FORBIDDEN) throw denied(what)
		if (response.status != MULTI_STATUS) return null
		return MultiStatus.parse(response.body, what).responses.firstOrNull()
	}

	private fun hrefIn(
		client: CalDavClient,
		response: DavResponse,
		namespace: String,
		name: String,
	): URI? =
		response
			.prop(namespace, name)
			?.children(CalDavClient.DAV, "href")
			?.firstOrNull()
			?.textContent
			?.let(client::resolve)

	private fun isCalendar(response: DavResponse): Boolean =
		response.prop(CalDavClient.DAV, "resourcetype")?.children(CalDavClient.CALDAV, "calendar")?.isNotEmpty() == true

	private fun holdsEvents(response: DavResponse): Boolean {
		val components = response.prop(CalDavClient.CALDAV, "supported-calendar-component-set") ?: return true
		val names = components.children(CalDavClient.CALDAV, "comp").map { it.getAttribute("name").uppercase() }
		return names.isEmpty() || "VEVENT" in names
	}

	private fun isReadOnly(response: DavResponse): Boolean {
		val privileges = response.prop(CalDavClient.DAV, "current-user-privilege-set") ?: return false
		return WRITE_PRIVILEGES.none { privileges.getElementsByTagNameNS(CalDavClient.DAV, it).length > 0 }
	}

	// --- pull

	override fun fetchEvents(
		source: CalendarSource,
		calendarExternalId: String,
		cursor: String?,
	): RemoteEventChanges {
		val client = client(source)
		val calendar = calendarUri(client, calendarExternalId)
		val state = cursor?.let(::readCursor)
		if (state != null) {
			val changes =
				if (state.mode == SYNC) {
					val sync = syncCollection(client, calendar, state.token)
					if (sync == null) {
						log.info("The CalDAV server no longer accepts the sync token of a calendar; listing it completely")
						null
					} else {
						changes(client, calendar, state.resources, sync.changed, sync.removed, complete = false, Cursor(SYNC, sync.token, null, emptyMap()))
					}
				} else {
					byEtags(client, calendar, state)
				}
			if (changes != null) return changes
		}
		return full(client, calendar)
	}

	private fun full(
		client: CalDavClient,
		calendar: URI,
	): RemoteEventChanges {
		val info = propfind(client, calendar, 0, "<D:sync-token/><CS:getctag/>", FETCH)
		if (info?.text(CalDavClient.DAV, "sync-token") != null) {
			val sync = syncCollection(client, calendar, null)
			if (sync !=
				null
			) {
				return changes(client, calendar, emptyMap(), sync.changed, emptySet(), complete = true, Cursor(SYNC, sync.token, null, emptyMap()))
			}
			log.info("A CalDAV calendar has a sync token but no sync-collection report; comparing etags")
		}
		val ctag = info?.text(CalDavClient.CALENDARSERVER, "getctag")
		return changes(client, calendar, emptyMap(), etags(client, calendar), emptySet(), complete = true, Cursor(ETAG, null, ctag, emptyMap()))
	}

	/** The changes since [state] by ctag and etags; nothing when the ctag is unchanged. */
	private fun byEtags(
		client: CalDavClient,
		calendar: URI,
		state: Cursor,
	): RemoteEventChanges {
		val ctag = propfind(client, calendar, 0, "<CS:getctag/>", FETCH)?.text(CalDavClient.CALENDARSERVER, "getctag")
		if (ctag != null && ctag == state.ctag) return RemoteEventChanges(cursor = writeCursor(state))
		val listing = etags(client, calendar)
		val changed = listing.filter { (path, etag) -> etag == null || state.resources[path]?.etag != etag }
		val removed = state.resources.keys - listing.keys
		return changes(client, calendar, state.resources, changed, removed, complete = false, Cursor(ETAG, null, ctag, emptyMap()))
	}

	/** The members of [calendar] (decoded paths) with their etags. */
	private fun etags(
		client: CalDavClient,
		calendar: URI,
	): Map<String, String?> {
		val response = client.propfind(calendar, 1, "<D:getetag/><D:resourcetype/>", FETCH)
		if (response.status != MULTI_STATUS) throw failed(FETCH, response)
		val own = calendar.path.trimEnd('/')
		return MultiStatus
			.parse(response.body, FETCH)
			.responses
			.filter { it.prop(CalDavClient.DAV, "resourcetype")?.elements().isNullOrEmpty() }
			.mapNotNull { member ->
				client.resolve(member.href)?.path?.takeIf { it.trimEnd('/') != own }?.let {
					it to
						member.text(CalDavClient.DAV, "getetag")
				}
			}.toMap()
	}

	private class SyncResult(
		val changed: Map<String, String?>,
		val removed: Set<String>,
		val token: String?,
	)

	/**
	 * `sync-collection` REPORT since [token] (null: everything), following truncated answers (507). Null when the server
	 * refuses it (an invalid token, or no support).
	 */
	private fun syncCollection(
		client: CalDavClient,
		calendar: URI,
		token: String?,
	): SyncResult? {
		val changed = mutableMapOf<String, String?>()
		val removed = mutableSetOf<String>()
		val own = calendar.path.trimEnd('/')
		var current = token
		repeat(MAX_SYNC_ROUNDS) {
			val body =
				"""<?xml version="1.0" encoding="utf-8"?><D:sync-collection xmlns:D="DAV:"><D:sync-token>${xml(current.orEmpty())}</D:sync-token>""" +
					"<D:sync-level>1</D:sync-level><D:prop><D:getetag/></D:prop></D:sync-collection>"
			val response = client.report(calendar, 0, body, FETCH)
			if (response.status != MULTI_STATUS) {
				if (response.status in 400..499) return null
				throw failed(FETCH, response)
			}
			val status = MultiStatus.parse(response.body, FETCH)
			var truncated = false
			for (member in status.responses) {
				val path = client.resolve(member.href)?.path ?: continue
				if (path.trimEnd('/') == own) {
					truncated = truncated || member.status == INSUFFICIENT_STORAGE
					continue
				}
				if (member.status == NOT_FOUND) {
					removed += path
					changed -= path
				} else {
					changed[path] = member.text(CalDavClient.DAV, "getetag")
					removed -= path
				}
			}
			current = status.syncToken ?: return SyncResult(changed, removed, null)
			if (!truncated) return SyncResult(changed, removed, current)
		}
		throw CalendarProviderException("$FETCH: the CalDAV server sent more than $MAX_SYNC_ROUNDS partial answers")
	}

	/**
	 * Reads the [changed] resources (those whose etag differs from [previous]) and turns them and the [removed] ones
	 * into changes; [next] gets the resource list after them.
	 */
	private fun changes(
		client: CalDavClient,
		calendar: URI,
		previous: Map<String, Resource>,
		changed: Map<String, String?>,
		removed: Set<String>,
		complete: Boolean,
		next: Cursor,
	): RemoteEventChanges {
		val resources = previous.toMutableMap()
		val wanted = changed.filter { (path, etag) -> complete || etag == null || previous[path]?.etag != etag }.keys
		val fetched = multiget(client, calendar, wanted)
		val events = mutableListOf<RemoteEvent>()
		for ((path, answer) in fetched) {
			val (etag, data) = answer
			val resource =
				try {
					CalDavEventMapping.parse(data, etag)
				} catch (e: IllegalArgumentException) {
					log.warn("Skipped a CalDAV resource that is no iCalendar object: {}", e.message)
					null
				}
			if (resource != null &&
				resource.skipped > 0
			) {
				log.warn("Skipped {} VEVENTs of a CalDAV resource that could not be read", resource.skipped)
			}
			resources[path] = Resource(etag, resource?.uid)
			resource?.events?.let { events += it }
		}
		// Gone between the listing and the reading.
		val gone = removed + (wanted - fetched.keys)
		gone.forEach { resources.remove(it) }
		val present = resources.values.mapNotNull { it.uid }.toSet()
		val deleted =
			(gone + fetched.keys)
				.mapNotNull { previous[it]?.uid }
				.filter { it !in present }
				.distinct()
				.map { RemoteEventKey(it) }
		return RemoteEventChanges(
			events = events,
			deleted = deleted,
			cursor = writeCursor(next.copy(resources = resources)),
			complete = complete,
		)
	}

	/** `calendar-multiget` of [paths] in batches: per decoded path its etag and calendar data. */
	private fun multiget(
		client: CalDavClient,
		calendar: URI,
		paths: Collection<String>,
	): Map<String, Pair<String?, String>> {
		val result = mutableMapOf<String, Pair<String?, String>>()
		paths.chunked(MULTIGET_BATCH).forEach { batch ->
			val hrefs = batch.joinToString("") { "<D:href>${xml(encodePath(it))}</D:href>" }
			val body =
				"""<?xml version="1.0" encoding="utf-8"?><C:calendar-multiget ${CalDavClient.NAMESPACES}>""" +
					"<D:prop><D:getetag/><C:calendar-data/></D:prop>$hrefs</C:calendar-multiget>"
			val response = client.report(calendar, 1, body, FETCH)
			if (response.status != MULTI_STATUS) throw failed(FETCH, response)
			MultiStatus.parse(response.body, FETCH).responses.forEach { member ->
				val path = client.resolve(member.href)?.path ?: return@forEach
				val data = member.prop(CalDavClient.CALDAV, "calendar-data")?.textContent ?: return@forEach
				result[path] = member.text(CalDavClient.DAV, "getetag") to data
			}
		}
		return result
	}

	// --- push

	override fun createEvent(
		source: CalendarSource,
		calendarExternalId: String,
		event: RemoteEvent,
	): RemoteEvent {
		val client = client(source)
		val calendar = calendarUri(client, calendarExternalId)
		val uid = event.key.externalId
		if (event.key.recurrenceId != null) {
			val found = find(client, calendar, uid) ?: throw RemoteEventChangedException("The series is no longer on the CalDAV server")
			if (CalDavEventMapping.master(found.calendar, uid) ==
				null
			) {
				throw RemoteEventChangedException("The series is no longer on the CalDAV server")
			}
			return write(client, found.uri, found.calendar, event, ifMatch = found.etag)
		}
		val target = calendar.resolve(encodeSegment(uid) + ".ics")
		val ics = CalDavEventMapping.newCalendar()
		CalDavEventMapping.write(ics, event, clock.instant())
		val response = client.send("PUT", target, ics.format(), CalDavClient.ICS, mapOf("If-None-Match" to "*"), CREATE)
		if (response.status == PRECONDITION_FAILED) {
			// The resource exists: an earlier attempt reached the server but its answer got lost.
			val existing = find(client, calendar, uid) ?: throw CalendarProviderException("$CREATE failed: the resource name is taken")
			return answer(existing.calendar.format(), existing.etag, event.key)
		}
		if (!response.ok) throw failed(CREATE, response)
		return stored(client, target, ics, response, event.key)
	}

	override fun updateEvent(
		source: CalendarSource,
		calendarExternalId: String,
		event: RemoteEvent,
		ifMatch: String?,
	): RemoteEvent {
		val client = client(source)
		val calendar = calendarUri(client, calendarExternalId)
		val uid = event.key.externalId
		val found = find(client, calendar, uid) ?: throw RemoteEventChangedException("The event was deleted on the CalDAV server")
		if (CalDavEventMapping.master(found.calendar, uid) ==
			null
		) {
			throw RemoteEventChangedException("The series was deleted on the CalDAV server")
		}
		return write(client, found.uri, found.calendar, event, ifMatch ?: found.etag)
	}

	override fun deleteEvent(
		source: CalendarSource,
		calendarExternalId: String,
		key: RemoteEventKey,
		ifMatch: String?,
	): String? {
		val client = client(source)
		val calendar = calendarUri(client, calendarExternalId)
		// Already gone.
		val found = find(client, calendar, key.externalId) ?: return null
		val occurrence = key.recurrenceId
		if (occurrence == null) {
			val response = client.send("DELETE", found.uri, headers = ifMatch?.let { mapOf("If-Match" to it) }.orEmpty(), what = DELETE)
			return when {
				response.status == PRECONDITION_FAILED -> throw RemoteEventChangedException()
				response.ok || response.status == NOT_FOUND -> null
				else -> throw failed(DELETE, response)
			}
		}
		CalDavEventMapping.removeOccurrence(found.calendar, key.externalId, occurrence, clock.instant())
		val response = put(client, found.uri, found.calendar, ifMatch ?: found.etag, DELETE)
		return response.header("ETag") ?: fetch(client, found.uri)?.etag
	}

	/** Writes [event] into the resource [calendar] at [uri] and PUTs it with `If-Match: ifMatch`; the stored event. */
	private fun write(
		client: CalDavClient,
		uri: URI,
		calendar: IcsComponent,
		event: RemoteEvent,
		ifMatch: String?,
	): RemoteEvent {
		try {
			CalDavEventMapping.write(calendar, event, clock.instant())
		} catch (e: IllegalArgumentException) {
			throw RemoteEventChangedException("The series is no longer on the CalDAV server", e)
		}
		val response = put(client, uri, calendar, ifMatch, UPDATE)
		return stored(client, uri, calendar, response, event.key)
	}

	private fun put(
		client: CalDavClient,
		uri: URI,
		calendar: IcsComponent,
		ifMatch: String?,
		what: String,
	): CalDavClient.Response {
		val response = client.send("PUT", uri, calendar.format(), CalDavClient.ICS, ifMatch?.let { mapOf("If-Match" to it) }.orEmpty(), what)
		when {
			response.status == PRECONDITION_FAILED -> throw RemoteEventChangedException()
			// Deleted meanwhile: the refetch shows that.
			response.status == NOT_FOUND -> throw RemoteEventChangedException("The event was deleted on the CalDAV server")
			!response.ok -> throw failed(what, response)
		}
		return response
	}

	/**
	 * The event [key] as the server stored it: what was sent when the server answered with an ETag (it must not, had it
	 * changed the data, RFC 4791 §5.3.4), else read back.
	 */
	private fun stored(
		client: CalDavClient,
		uri: URI,
		sent: IcsComponent,
		response: CalDavClient.Response,
		key: RemoteEventKey,
	): RemoteEvent {
		val etag = response.header("ETag")
		if (etag != null) return answer(sent.format(), etag, key)
		val read = fetch(client, uri) ?: throw CalendarProviderException("The CalDAV server lost a stored event")
		return answer(read.calendar.format(), read.etag, key)
	}

	private fun answer(
		data: String,
		etag: String?,
		key: RemoteEventKey,
	): RemoteEvent {
		val zones = CalDavEventMapping.zones(CalDavEventMapping.calendar(data))
		val vevent =
			CalDavEventMapping.calendar(data).children("VEVENT").firstOrNull { vevent ->
				vevent.text("UID")?.trim() == key.externalId &&
					vevent.property("RECURRENCE-ID")?.let { runCatching { CalDavEventMapping.occurrence(it, zones) }.getOrNull() } == key.recurrenceId
			} ?: throw CalendarProviderException("The CalDAV server answered without the event")
		return try {
			CalDavEventMapping.read(vevent, zones, etag)
		} catch (e: RuntimeException) {
			if (e !is IllegalArgumentException && e !is DateTimeException && e !is ArithmeticException) throw e
			throw CalendarProviderException("The CalDAV server answered with an event that could not be read", e)
		}
	}

	/** A resource as read: where it is, its etag and its VCALENDAR. */
	private class Found(
		val uri: URI,
		val etag: String?,
		val calendar: IcsComponent,
	)

	/** The resource of [uid] in [calendar]: `<uid>.ics` (as the cockpit names them), else by a `calendar-query`. */
	private fun find(
		client: CalDavClient,
		calendar: URI,
		uid: String,
	): Found? {
		fetch(client, calendar.resolve(encodeSegment(uid) + ".ics"))?.let { found ->
			if (found.calendar.children("VEVENT").any { it.text("UID")?.trim() == uid }) return found
		}
		val body =
			"""<?xml version="1.0" encoding="utf-8"?><C:calendar-query ${CalDavClient.NAMESPACES}>""" +
				"<D:prop><D:getetag/><C:calendar-data/></D:prop><C:filter><C:comp-filter name=\"VCALENDAR\"><C:comp-filter name=\"VEVENT\">" +
				"<C:prop-filter name=\"UID\"><C:text-match collation=\"i;octet\">${xml(uid)}</C:text-match></C:prop-filter>" +
				"</C:comp-filter></C:comp-filter></C:filter></C:calendar-query>"
		val response = client.report(calendar, 1, body, FIND)
		if (response.status != MULTI_STATUS) throw failed(FIND, response)
		return MultiStatus.parse(response.body, FIND).responses.firstNotNullOfOrNull { member ->
			val uri = client.resolve(member.href) ?: return@firstNotNullOfOrNull null
			val data = member.prop(CalDavClient.CALDAV, "calendar-data")?.textContent ?: return@firstNotNullOfOrNull null
			val parsed = runCatching { CalDavEventMapping.calendar(data) }.getOrNull() ?: return@firstNotNullOfOrNull null
			Found(uri, member.text(CalDavClient.DAV, "getetag"), parsed).takeIf { found ->
				found.calendar.children("VEVENT").any { it.text("UID")?.trim() == uid }
			}
		}
	}

	/** GET of a resource; null when there is none (or it is no iCalendar object). */
	private fun fetch(
		client: CalDavClient,
		uri: URI,
	): Found? {
		val response = client.send("GET", uri, what = FIND)
		if (response.status == NOT_FOUND || response.status == GONE) return null
		if (!response.ok) throw failed(FIND, response)
		val calendar =
			try {
				CalDavEventMapping.calendar(response.body)
			} catch (e: IllegalArgumentException) {
				return null
			}
		return Found(uri, response.header("ETag"), calendar)
	}

	// --- source, cursor

	private fun client(source: CalendarSource): CalDavClient {
		val config =
			try {
				json.readTree(source.config)
			} catch (e: JacksonException) {
				null
			}
		val url =
			config
				?.path(URL)
				?.takeIf { it.isString }
				?.asString()
				?.trim()
		val base =
			url?.let {
				try {
					URI(it)
				} catch (e: URISyntaxException) {
					null
				}
			}
		if (base == null || base.host == null || base.scheme?.lowercase() !in setOf("http", "https") || base.rawUserInfo != null) {
			throw CalendarProviderException("The source has no valid CalDAV URL")
		}
		val username =
			config
				.path(USERNAME)
				.takeIf { it.isString }
				?.asString()
				?.ifEmpty { null }
		return CalDavClient(base, username, source.credentials?.reveal(), http)
	}

	private fun calendarUri(
		client: CalDavClient,
		externalId: String,
	): URI {
		val uri = client.resolve(externalId) ?: throw CalendarProviderException("The calendar is not on the source's CalDAV server")
		return if (uri.rawPath.endsWith("/")) uri else client.resolve(uri.rawPath + "/")!!
	}

	/** A resource as of the last run: its etag and the UID of its events (null: none could be read). */
	private data class Resource(
		val etag: String?,
		val uid: String?,
	)

	private data class Cursor(
		val mode: String,
		val token: String?,
		val ctag: String?,
		val resources: Map<String, Resource>,
	)

	private fun writeCursor(cursor: Cursor): String {
		val node = json.createObjectNode()
		node.put("mode", cursor.mode)
		cursor.token?.let { node.put("token", it) }
		cursor.ctag?.let { node.put("ctag", it) }
		val resources = node.putObject("resources")
		cursor.resources.forEach { (path, resource) ->
			resources.putObject(path).apply {
				resource.etag?.let { put("etag", it) }
				resource.uid?.let { put("uid", it) }
			}
		}
		return json.writeValueAsString(node)
	}

	/** The cursor of [text]; null when it is not one of ours (a full listing follows). */
	private fun readCursor(text: String): Cursor? {
		val node =
			try {
				json.readTree(text)
			} catch (e: JacksonException) {
				return null
			}
		val mode = node.string("mode")?.takeIf { it == SYNC || it == ETAG } ?: return null
		val token = node.string("token")
		if (mode == SYNC && token == null) return null
		val resources =
			node
				.path("resources")
				.properties()
				.associate { (path, value) -> path to Resource(value.string("etag"), value.string("uid")) }
		return Cursor(mode, token, node.string("ctag"), resources)
	}

	private fun JsonNode.string(name: String): String? = path(name).takeIf { it.isString }?.asString()

	private fun failed(
		what: String,
		response: CalDavClient.Response,
	) = CalendarProviderException("$what failed at the CalDAV server (HTTP ${response.status})")

	private fun denied(what: String) =
		CalendarProviderException("$what: the CalDAV server denied access (HTTP 403), the user lacks permission")

	companion object {
		/** Keys of a CalDAV source's config. */
		const val URL = "url"
		const val USERNAME = "username"

		private const val SYNC = "sync"
		private const val ETAG = "etag"
		private const val WELL_KNOWN = "/.well-known/caldav"
		private const val FETCH = "Fetching the events"
		private const val CREATE = "Creating an event"
		private const val UPDATE = "Changing an event"
		private const val DELETE = "Deleting an event"
		private const val FIND = "Reading an event"
		private const val MULTI_STATUS = 207
		private const val FORBIDDEN = 403
		private const val NOT_FOUND = 404
		private const val GONE = 410
		private const val PRECONDITION_FAILED = 412
		private const val INSUFFICIENT_STORAGE = 507
		private const val MULTIGET_BATCH = 100
		private const val MAX_SYNC_ROUNDS = 100
		private const val COLOR_LENGTH = 7
		private val WRITE_PRIVILEGES = listOf("write", "write-content", "all")

		private fun xml(text: String): String =
			text
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")

		/** [segment] percent-encoded as one path segment (unreserved characters kept). */
		fun encodeSegment(segment: String): String =
			buildString {
				segment.toByteArray(Charsets.UTF_8).forEach { b ->
					val c = b.toInt() and 0xff
					if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-._~@")) append(c.toChar()) else append("%%%02X".format(c))
				}
			}

		/** A decoded path percent-encoded segment by segment. */
		fun encodePath(path: String): String = path.split('/').joinToString("/") { encodeSegment(it) }
	}
}
