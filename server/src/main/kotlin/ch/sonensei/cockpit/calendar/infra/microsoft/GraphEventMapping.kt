package ch.sonensei.cockpit.calendar.infra.microsoft

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.RemoteCalendar
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import org.jsoup.Jsoup
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Translates between Microsoft Graph resources (`calendar`, `event`) and the sync engine's [RemoteCalendar]/[RemoteEvent].
 * Graph is asked for times in UTC and bodies as text (`Prefer` headers of [GraphClient]).
 *
 * - The event's zone is its `originalStartTimeZone` (Windows or IANA name, [WindowsZones]); unknown zones are UTC.
 * - All-day events are dates (Graph sends midnight to midnight).
 * - Graph has one reminder (`reminderMinutesBeforeStart`); `showAs` free is transparent, tentative is
 *   [EventStatus.TENTATIVE]; a cancelled meeting (`isCancelled`) is [EventStatus.CANCELLED].
 * - The conference URL is the Teams/online meeting join URL; it is read-only.
 */
internal object GraphEventMapping {
	private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

	/** Graph's `type` of a series master, an occurrence, a changed occurrence and a single event. */
	const val SERIES_MASTER = "seriesMaster"
	const val OCCURRENCE = "occurrence"
	const val EXCEPTION = "exception"
	const val SINGLE = "singleInstance"

	fun toCalendar(node: JsonNode): RemoteCalendar =
		RemoteCalendar(
			externalId = node.requireText("id"),
			name = node.text("name") ?: node.requireText("id"),
			color = node.text("hexColor")?.takeIf { it.isNotBlank() },
			readOnly = !node.path("canEdit").asBoolean(false),
		)

	/**
	 * [node] as a [RemoteEvent] with [key]. A series master gets its RRULE and [exdates]; the caller keys an exception by
	 * its series and occurrence.
	 *
	 * @throws IllegalArgumentException or [java.time.DateTimeException] for an event that cannot be read
	 */
	fun toRemote(
		node: JsonNode,
		key: RemoteEventKey,
		exdates: Set<OccurrenceId> = emptySet(),
	): RemoteEvent {
		val timing = timing(node)
		val master = node.text("type") == SERIES_MASTER
		val showAs = node.text("showAs")
		return RemoteEvent(
			key = key,
			etag = node.text("@odata.etag"),
			title = node.text("subject") ?: "",
			timing = timing,
			description = description(node.path("body")),
			location = node.path("location").text("displayName")?.takeIf { it.isNotBlank() },
			rrule = if (master) GraphRecurrence.toRule(node.path("recurrence"), timing) else null,
			exdates = if (master) exdates else emptySet(),
			attendees = node.path("attendees").mapNotNull(::attendee),
			reminderMinutes =
				if (node.path("isReminderOn").asBoolean(false) && node.path("reminderMinutesBeforeStart").isNumber) {
					listOf(node.path("reminderMinutesBeforeStart").asInt())
				} else {
					emptyList()
				},
			status =
				when {
					node.path("isCancelled").asBoolean(false) -> EventStatus.CANCELLED
					showAs == "tentative" -> EventStatus.TENTATIVE
					else -> EventStatus.CONFIRMED
				},
			transparency = if (showAs == "free") EventTransparency.TRANSPARENT else EventTransparency.OPAQUE,
			conferenceUrl = node.path("onlineMeeting").text("joinUrl") ?: node.text("onlineMeetingUrl"),
		)
	}

	/** The zone [node] was planned in. */
	fun zone(node: JsonNode): ZoneId =
		WindowsZones.zone(node.text("originalStartTimeZone")) ?: WindowsZones.zone(node.path("start").text("timeZone")) ?: ZoneOffset.UTC

	/** When [node] takes place. */
	fun timing(node: JsonNode): Timing {
		val zone = zone(node)
		val start = dateTime(node.path("start"))
		val end = node.path("end").takeIf { it.isObject }?.let(::dateTime) ?: start
		if (node.path("isAllDay").asBoolean(false)) {
			val first = date(start, zone)
			val last = date(end, zone)
			return Timing.AllDay(first, if (last.isAfter(first)) last else first.plusDays(1))
		}
		return Timing.Timed(start, if (end.isBefore(start)) start else end, zone)
	}

	/** The original start of an occurrence or exception [node]: `originalStart`, else its start. */
	fun originalStart(node: JsonNode): Instant =
		node.text("originalStart")?.let {
			java.time.OffsetDateTime
				.parse(it)
				.toInstant()
		} ?: dateTime(node.path("start"))

	/**
	 * [event] as the body of a create or update: every field the cockpit edits (not the conference URL). With
	 * [recurrence], the series' pattern (null for none) is part of it.
	 *
	 * @throws IllegalArgumentException when Graph cannot express the event's RRULE
	 */
	fun toBody(
		json: JsonMapper,
		event: RemoteEvent,
		recurrence: Boolean,
	): ObjectNode {
		val body = json.createObjectNode()
		body.put("subject", event.title)
		body.putObject("body").put("contentType", "text").put("content", event.description ?: "")
		body.putObject("location").put("displayName", event.location ?: "")
		val zoneName: String
		// The zone the times are sent in.
		val zone: ZoneId
		when (val timing = event.timing) {
			is Timing.AllDay -> {
				zoneName = "UTC"
				zone = ZoneOffset.UTC
				body.put("isAllDay", true)
				body.set("start", time(json, timing.startDate.atStartOfDay(), zoneName))
				body.set("end", time(json, timing.endDate.atStartOfDay(), zoneName))
			}
			is Timing.Timed -> {
				val named = WindowsZones.name(timing.zone)
				// A fixed offset has no name: the times go in UTC.
				zone = if (named == null) ZoneOffset.UTC else timing.zone
				zoneName = named ?: "UTC"
				body.put("isAllDay", false)
				body.set("start", time(json, LocalDateTime.ofInstant(timing.start, zone), zoneName))
				body.set("end", time(json, LocalDateTime.ofInstant(timing.end, zone), zoneName))
			}
		}
		if (recurrence) {
			val pattern = event.rrule?.let { GraphRecurrence.toRecurrence(json, it, event.timing, zoneName, zone) }
			body.set("recurrence", pattern ?: json.nullNode())
		}
		val attendees = body.putArray("attendees")
		event.attendees.forEach {
			val entry = attendees.addObject()
			entry.putObject("emailAddress").put("address", it.email).put("name", it.name)
			entry.put("type", "required")
		}
		val reminder = event.reminderMinutes.firstOrNull()
		body.put("isReminderOn", reminder != null)
		reminder?.let { body.put("reminderMinutesBeforeStart", it) }
		body.put(
			"showAs",
			when {
				event.transparency == EventTransparency.TRANSPARENT -> "free"
				event.status == EventStatus.TENTATIVE -> "tentative"
				else -> "busy"
			},
		)
		return body
	}

	/**
	 * The fields of [desired] (a [toBody]) that differ from [current] (the event at Graph, as a [toBody] too), so an
	 * update leaves alone what did not change: a series keeps its exceptions unless its pattern changed, and attendees
	 * are not notified again unless they changed.
	 */
	fun changes(
		json: JsonMapper,
		desired: ObjectNode,
		current: ObjectNode,
	): ObjectNode {
		val diff = json.createObjectNode()
		for (name in desired.propertyNames()) {
			val value = desired.get(name)
			if (value != current.get(name)) diff.set(name, value)
		}
		// The minutes alone mean nothing to Graph without the switch.
		if (diff.has("reminderMinutesBeforeStart") && desired.has("isReminderOn")) diff.set("isReminderOn", desired.get("isReminderOn"))
		// Times go together: Graph checks start against end.
		if (diff.has("start") || diff.has("end") || diff.has("isAllDay")) {
			listOf("start", "end", "isAllDay").forEach { diff.set(it, desired.get(it)) }
		}
		return diff
	}

	/**
	 * Gives the attendees of [changes] (a [changes] diff) the `type` (required, optional, resource) they have in
	 * [current] (the event at Graph), matched by email; attendees Graph does not have yet stay required.
	 */
	fun keepAttendeeTypes(
		changes: ObjectNode,
		current: JsonNode,
	) {
		val attendees = changes.get("attendees") ?: return
		val types =
			current
				.path("attendees")
				.mapNotNull { attendee ->
					val email = attendee.path("emailAddress").text("address") ?: return@mapNotNull null
					val type = attendee.text("type") ?: return@mapNotNull null
					email.lowercase() to type
				}.toMap()
		for (attendee in attendees) {
			val email = attendee.path("emailAddress").text("address")?.lowercase() ?: continue
			types[email]?.let { (attendee as ObjectNode).put("type", it) }
		}
	}

	// --- reading

	private fun dateTime(node: JsonNode): Instant {
		val local = LocalDateTime.parse(node.requireText("dateTime"))
		val zone = WindowsZones.zone(node.text("timeZone")) ?: ZoneOffset.UTC
		return local.atZone(zone).toInstant()
	}

	/** The day an all-day boundary [instant] (midnight in the event's [zone], or in UTC) stands for. */
	private fun date(
		instant: Instant,
		zone: ZoneId,
	) = instant.atZone(if (instant.atZone(ZoneOffset.UTC).toLocalTime() == LocalTime.MIDNIGHT) ZoneOffset.UTC else zone).toLocalDate()

	private fun description(body: JsonNode): String? {
		val content = body.text("content") ?: return null
		val text = if (body.text("contentType").equals("html", ignoreCase = true)) Jsoup.parse(content).wholeText() else content
		return text.trim().ifEmpty { null }
	}

	private fun attendee(node: JsonNode): Attendee? {
		val email = node.path("emailAddress").text("address") ?: return null
		val status =
			when (node.path("status").text("response")) {
				"accepted", "organizer" -> AttendeeStatus.ACCEPTED
				"declined" -> AttendeeStatus.DECLINED
				"tentativelyAccepted" -> AttendeeStatus.TENTATIVE
				else -> AttendeeStatus.NEEDS_ACTION
			}
		return Attendee(email, node.path("emailAddress").text("name")?.takeIf { it.isNotBlank() }, status)
	}

	// --- writing

	private fun time(
		json: JsonMapper,
		local: LocalDateTime,
		zoneName: String,
	): ObjectNode = json.createObjectNode().put("dateTime", LOCAL.format(local)).put("timeZone", zoneName)

	// --- JSON helpers

	fun JsonNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.asString()

	private fun JsonNode.requireText(name: String): String =
		text(name) ?: throw IllegalArgumentException("Microsoft Graph sent a resource without $name")
}
