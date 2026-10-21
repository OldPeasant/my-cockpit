package ch.sonensei.cockpit.calendar.infra.google

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.RemoteCalendar
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Translates between Google Calendar API v3 resources (`CalendarListEntry`, `Event`) and the sync engine's
 * [RemoteCalendar]/[RemoteEvent].
 *
 * - A series master or single event is keyed by its Google `id`; a changed instance of a series (an exception) by the
 *   series' `id` (`recurringEventId`) plus its `originalStartTime`.
 * - `recurrence` holds the `RRULE` and `EXDATE` lines; other lines (`RDATE`, `EXRULE`) are not supported and dropped.
 * - Reminders with `useDefault` take the calendar's default reminders; only popup and e-mail minutes are kept.
 * - The conference URL is the Meet link (`hangoutLink`) or the first video entry point; it is read-only.
 */
internal object GoogleEventMapping {
	private val UTC_BASIC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
	private val LOCAL_BASIC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
	private val RFC3339: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

	fun isCancelled(node: JsonNode): Boolean = node.text("status") == "cancelled"

	/** The key of [node]: its `id`, or for an instance the series' `id` and the occurrence. */
	fun key(node: JsonNode): RemoteEventKey {
		val series = node.text("recurringEventId") ?: return RemoteEventKey(node.requireText("id"))
		return RemoteEventKey(series, occurrence(node.path("originalStartTime")))
	}

	/**
	 * [node] as a [RemoteEvent]. A cancelled one (Google's answer to cancelling an occurrence) keeps the status
	 * CANCELLED; a listing treats cancelled items as deletions before it maps them.
	 *
	 * [calendarZone] (the calendar's `timeZone`) is the zone of times without one; [defaultReminders] the
	 * calendar's reminders for events with `useDefault`.
	 */
	fun toRemote(
		node: JsonNode,
		calendarZone: ZoneId? = null,
		defaultReminders: List<Int> = emptyList(),
	): RemoteEvent {
		val key = key(node)
		val timing = timing(node.path("start"), node.path("end"), calendarZone)
		val recurrence = if (key.recurrenceId == null) node.path("recurrence").mapNotNull { it.asString(null) } else emptyList()
		return RemoteEvent(
			key = key,
			etag = node.text("etag"),
			title = node.text("summary") ?: "",
			timing = timing,
			description = node.text("description"),
			location = node.text("location"),
			rrule = recurrence.firstOrNull { it.startsWith("RRULE:", ignoreCase = true) }?.substring("RRULE:".length),
			exdates = recurrence.filter { it.startsWith("EXDATE", ignoreCase = true) }.flatMap { exdates(it, timing) }.toSet(),
			attendees = node.path("attendees").mapNotNull(::attendee),
			reminderMinutes = reminders(node.path("reminders"), defaultReminders),
			status =
				when (node.text("status")) {
					"tentative" -> EventStatus.TENTATIVE
					// Only in answers to writes: a listing's cancelled items are deletions (isCancelled).
					"cancelled" -> EventStatus.CANCELLED
					else -> EventStatus.CONFIRMED
				},
			transparency = if (node.text("transparency") == "transparent") EventTransparency.TRANSPARENT else EventTransparency.OPAQUE,
			conferenceUrl = node.text("hangoutLink") ?: videoEntryPoint(node.path("conferenceData")),
		)
	}

	fun toCalendar(node: JsonNode): RemoteCalendar =
		RemoteCalendar(
			externalId = node.requireText("id"),
			name = node.text("summaryOverride") ?: node.text("summary") ?: node.requireText("id"),
			color = node.text("backgroundColor"),
			readOnly = node.text("accessRole") !in setOf("owner", "writer"),
		)

	/** The calendar's `defaultReminders` (of an events list) as minutes. */
	fun defaultReminders(list: JsonNode): List<Int> = minutes(list.path("defaultReminders"))

	fun zone(id: String?): ZoneId? =
		id?.let {
			try {
				ZoneId.of(it)
			} catch (e: DateTimeException) {
				null
			}
		}

	/**
	 * [event] as the body of an insert or patch. Every field the cockpit edits is sent (null clears it); the
	 * conference URL is not (Google creates Meet links itself). The recurrence is sent for series and single events,
	 * not for instances; [clearRecurrence] sends an empty one when [event] has no rule (a patch of a former series).
	 */
	fun toBody(
		json: JsonMapper,
		event: RemoteEvent,
		clearRecurrence: Boolean,
	): ObjectNode {
		val body = json.createObjectNode()
		body.put("summary", event.title)
		body.put("description", event.description)
		body.put("location", event.location)
		val (start, end) = times(json, event.timing)
		body.set("start", start)
		body.set("end", end)
		if (event.key.recurrenceId == null && (event.rrule != null || clearRecurrence)) {
			val lines = body.putArray("recurrence")
			event.rrule?.let { rule ->
				lines.add("RRULE:$rule")
				exdateLine(event.exdates, event.timing)?.let(lines::add)
			}
		}
		val attendees = body.putArray("attendees")
		event.attendees.forEach {
			attendees
				.addObject()
				.put("email", it.email)
				.put("displayName", it.name)
				.put("responseStatus", responseStatus(it.status))
		}
		val reminders = body.putObject("reminders").put("useDefault", false)
		val overrides = reminders.putArray("overrides")
		event.reminderMinutes.forEach { overrides.addObject().put("method", "popup").put("minutes", it) }
		body.put("status", if (event.status == EventStatus.TENTATIVE) "tentative" else event.status.name.lowercase())
		body.put("transparency", event.transparency.name.lowercase())
		return body
	}

	/** The Google `id` of the occurrence [occurrence] of series [seriesId]: `<id>_<UTC start>` or `<id>_<date>`. */
	fun instanceId(
		seriesId: String,
		occurrence: OccurrenceId,
	): String = seriesId + "_" + (occurrence.at?.let(UTC_BASIC::format) ?: DateTimeFormatter.BASIC_ISO_DATE.format(occurrence.date))

	// --- reading

	private fun timing(
		start: JsonNode,
		end: JsonNode,
		calendarZone: ZoneId?,
	): Timing {
		val startDate = start.text("date")
		if (startDate != null) {
			val first = LocalDate.parse(startDate)
			return Timing.AllDay(first, end.text("date")?.let(LocalDate::parse) ?: first.plusDays(1))
		}
		val startAt = OffsetDateTime.parse(start.requireText("dateTime"))
		val endAt = end.text("dateTime")?.let(OffsetDateTime::parse) ?: startAt
		val zone = zone(start.text("timeZone")) ?: calendarZone ?: startAt.offset
		return Timing.Timed(startAt.toInstant(), endAt.toInstant(), zone)
	}

	private fun occurrence(original: JsonNode): OccurrenceId {
		original.text("date")?.let { return OccurrenceId.of(LocalDate.parse(it)) }
		return OccurrenceId.of(OffsetDateTime.parse(original.requireText("dateTime")).toInstant())
	}

	/**
	 * The occurrences of one `EXDATE[;TZID=..|;VALUE=DATE]:v1,v2` line of a series with [timing], of the series' kind:
	 * dates for all-day series, instants for timed ones (a date then means the occurrence on that day).
	 */
	private fun exdates(
		line: String,
		timing: Timing,
	): List<OccurrenceId> {
		val colon = line.indexOf(':')
		if (colon < 0) return emptyList()
		val params =
			line
				.substring(0, colon)
				.split(';')
				.drop(1)
				.associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
		val seriesZone = (timing as? Timing.Timed)?.zone ?: ZoneOffset.UTC
		val zone = zone(params["TZID"]) ?: seriesZone
		return line.substring(colon + 1).split(',').mapNotNull { raw ->
			val value = raw.trim()
			try {
				when {
					value.length == 8 -> {
						val date = LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
						when (timing) {
							is Timing.AllDay -> OccurrenceId.of(date)
							is Timing.Timed -> OccurrenceId.of(date.atTime(timing.localStart.toLocalTime()).atZone(timing.zone).toInstant())
						}
					}
					else -> {
						val instant =
							if (value.endsWith("Z")) {
								LocalDateTime.parse(value.dropLast(1), LOCAL_BASIC).toInstant(ZoneOffset.UTC)
							} else {
								LocalDateTime.parse(value, LOCAL_BASIC).atZone(zone).toInstant()
							}
						when (timing) {
							is Timing.AllDay -> OccurrenceId.of(instant.atZone(zone).toLocalDate())
							is Timing.Timed -> OccurrenceId.of(instant)
						}
					}
				}
			} catch (e: DateTimeParseException) {
				null
			}
		}
	}

	private fun attendee(node: JsonNode): Attendee? {
		val email = node.text("email") ?: return null
		val status =
			when (node.text("responseStatus")) {
				"accepted" -> AttendeeStatus.ACCEPTED
				"declined" -> AttendeeStatus.DECLINED
				"tentative" -> AttendeeStatus.TENTATIVE
				else -> AttendeeStatus.NEEDS_ACTION
			}
		return Attendee(email, node.text("displayName"), status)
	}

	private fun reminders(
		node: JsonNode,
		defaults: List<Int>,
	): List<Int> = if (node.path("useDefault").asBoolean(false)) defaults else minutes(node.path("overrides"))

	private fun minutes(list: JsonNode): List<Int> =
		list
			.filter { it.text("method") in setOf("popup", "email") && it.path("minutes").isNumber }
			.map { it.path("minutes").asInt() }
			.distinct()

	private fun videoEntryPoint(conference: JsonNode): String? =
		conference.path("entryPoints").firstOrNull { it.text("entryPointType") == "video" }?.text("uri")

	// --- writing

	private fun times(
		json: JsonMapper,
		timing: Timing,
	): Pair<ObjectNode, ObjectNode> =
		when (timing) {
			// Patch merges objects: the other kind's fields are cleared explicitly.
			is Timing.AllDay ->
				json
					.createObjectNode()
					.put("date", timing.startDate.toString())
					.putNull("dateTime")
					.putNull("timeZone") to
					json
						.createObjectNode()
						.put("date", timing.endDate.toString())
						.putNull("dateTime")
						.putNull("timeZone")
			is Timing.Timed -> {
				val zoneName = zoneName(timing.zone)

				fun at(instant: Instant) =
					json
						.createObjectNode()
						.putNull("date")
						.put("dateTime", RFC3339.format(instant.atZone(timing.zone)))
						.put("timeZone", zoneName)
				at(timing.start) to at(timing.end)
			}
		}

	/** Google wants IANA zone names; a fixed offset other than UTC has none (the offset in the time says it all). */
	private fun zoneName(zone: ZoneId): String? =
		when (val normalized = zone.normalized()) {
			is ZoneOffset -> if (normalized == ZoneOffset.UTC) "UTC" else null
			else -> zone.id
		}

	private fun exdateLine(
		exdates: Set<OccurrenceId>,
		timing: Timing,
	): String? {
		if (exdates.isEmpty()) return null
		return when (timing) {
			is Timing.AllDay ->
				"EXDATE;VALUE=DATE:" + exdates.mapNotNull { it.date }.sorted().joinToString(",") { DateTimeFormatter.BASIC_ISO_DATE.format(it) }
			is Timing.Timed -> "EXDATE:" + exdates.mapNotNull { it.at }.sorted().joinToString(",") { UTC_BASIC.format(it) }
		}
	}

	fun responseStatus(status: AttendeeStatus): String =
		when (status) {
			AttendeeStatus.ACCEPTED -> "accepted"
			AttendeeStatus.DECLINED -> "declined"
			AttendeeStatus.TENTATIVE -> "tentative"
			AttendeeStatus.NEEDS_ACTION, AttendeeStatus.DELEGATED -> "needsAction"
		}

	// --- JSON helpers

	fun JsonNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.asString()

	private fun JsonNode.requireText(name: String): String =
		text(name) ?: throw IllegalArgumentException("Google sent a resource without $name")
}
