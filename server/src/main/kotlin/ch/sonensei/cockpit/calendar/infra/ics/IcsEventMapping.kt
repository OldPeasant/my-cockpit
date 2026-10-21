package ch.sonensei.cockpit.calendar.infra.ics

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.infra.ics.IcsParser.unescape
import ch.sonensei.cockpit.calendar.infra.microsoft.WindowsZones
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.Temporal

/**
 * Reads the `VEVENT`s of an ICS feed into [RemoteEvent]s (RFC 5545).
 *
 * - Time zones: a `TZID` is looked up as an IANA id, then as the `Area/City` at the end of a prefixed id
 *   (`/mozilla.org/.../Europe/Zurich`), as a Windows name, and as the `X-LIC-LOCATION` of the feed's `VTIMEZONE`;
 *   anything else and floating times are read in the feed's `X-WR-TIMEZONE`, else UTC. The feed's VTIMEZONE rules
 *   themselves are not used (java.time has current rules).
 * - A missing `DTEND` comes from `DURATION` (its days and weeks are calendar days in the event's zone), else the event
 *   lasts one day (all-day) or no time (timed).
 * - An event with `RECURRENCE-ID` is the exception of that occurrence of its series; `RDATE` is not supported.
 *   Exceptions without a recurring (`RRULE`), not cancelled series in the feed are left out.
 * - Cancelled series and single events are left out (the feed no longer has them); cancelled exceptions stay, they
 *   remove their occurrence.
 * - Reminders are the `VALARM`s triggered before the start; the conference URL is `CONFERENCE` (RFC 7986) or
 *   Google's `X-GOOGLE-CONFERENCE`.
 * - An event without `UID` gets one from its start and title, so it keeps its identity between fetches.
 * - Feeds have no versions per event: the etag is a hash of the event's content, so an unchanged event is not stored
 *   again (`DTSTAMP`, which many feeds set to the time of the download, is not part of it).
 */
internal object IcsEventMapping {
	/** The events of [calendars] (the feed's `VCALENDAR`s); events that cannot be read go to [skipped]. */
	fun events(
		calendars: List<IcsComponent>,
		skipped: (RuntimeException) -> Unit = {},
	): List<RemoteEvent> {
		val result = mutableListOf<RemoteEvent>()
		for (calendar in calendars.filter { it.name == "VCALENDAR" }) {
			val zones = Zones(calendar)
			for (vevent in calendar.children("VEVENT")) {
				try {
					toRemote(vevent, zones)?.let { result += it }
				} catch (e: DateTimeException) {
					skipped(e)
				} catch (e: IllegalArgumentException) {
					skipped(e)
				}
			}
		}
		// An exception whose series is not here (cancelled, without RRULE, or unreadable) has nothing to change.
		val series = result.filter { it.key.recurrenceId == null && it.rrule != null }.map { it.key.externalId }.toSet()
		return result.filter { it.key.recurrenceId == null || it.key.externalId in series }
	}

	/**
	 * [vevent] as a remote event; null when it is a cancelled series or single event.
	 *
	 * @throws IllegalArgumentException or [DateTimeException] when it has no readable start
	 */
	fun toRemote(
		vevent: IcsComponent,
		zones: Zones,
	): RemoteEvent? {
		val status =
			when (
				vevent
					.first("STATUS")
					?.value
					?.trim()
					?.uppercase()
			) {
				"CANCELLED" -> EventStatus.CANCELLED
				"TENTATIVE" -> EventStatus.TENTATIVE
				else -> EventStatus.CONFIRMED
			}
		val recurrenceId = vevent.first("RECURRENCE-ID")?.let { occurrence(zones.time(it)) }
		if (status == EventStatus.CANCELLED && recurrenceId == null) return null
		val start = zones.time(vevent.first("DTSTART") ?: throw IllegalArgumentException("The event has no DTSTART"))
		val timing = timing(start, vevent, zones)
		val title =
			vevent
				.first("SUMMARY")
				?.value
				?.let(::unescape)
				?.trim()
				.orEmpty()
		val uid =
			vevent
				.first("UID")
				?.value
				?.trim()
				?.ifEmpty { null }
				?: "generated-" + hash("$start|$title").take(GENERATED_UID_LENGTH)
		val event =
			RemoteEvent(
				key = RemoteEventKey(uid, recurrenceId),
				etag = null,
				title = title,
				timing = timing,
				description =
					vevent
						.first("DESCRIPTION")
						?.value
						?.let(::unescape)
						?.ifBlank { null },
				location =
					vevent
						.first("LOCATION")
						?.value
						?.let(::unescape)
						?.trim()
						?.ifEmpty { null },
				rrule =
					if (recurrenceId == null) {
						vevent
							.first("RRULE")
							?.value
							?.trim()
							?.ifEmpty { null }
					} else {
						null
					},
				exdates = if (recurrenceId == null) exdates(vevent, zones) else emptySet(),
				attendees = vevent.all("ATTENDEE").mapNotNull(::attendee),
				reminderMinutes =
					vevent
						.children("VALARM")
						.mapNotNull(::reminderMinutes)
						.distinct()
						.sorted(),
				status = status,
				transparency =
					if (vevent
							.first("TRANSP")
							?.value
							?.trim()
							?.uppercase() == "TRANSPARENT"
					) {
						EventTransparency.TRANSPARENT
					} else {
						EventTransparency.OPAQUE
					},
				conferenceUrl = (vevent.first("CONFERENCE") ?: vevent.first("X-GOOGLE-CONFERENCE"))?.value?.trim()?.ifEmpty { null },
			)
		return event.copy(etag = hash(event.toString()))
	}

	private fun timing(
		start: Temporal,
		vevent: IcsComponent,
		zones: Zones,
	): Timing {
		val end = vevent.first("DTEND")?.let { zones.time(it) }
		val durationValue = vevent.first("DURATION")?.value
		val duration = durationValue?.let(::duration)
		return when (start) {
			is LocalDate -> {
				val endDate =
					when {
						end is LocalDate -> end
						end is Instant -> end.atZone(zones.default).toLocalDate()
						duration != null -> start.plusDays(maxOf(1, duration.toDays()))
						else -> start.plusDays(1)
					}
				// A zero-length all-day event (DTEND = DTSTART) is read as one day.
				Timing.AllDay(start, if (endDate.isAfter(start)) endDate else start.plusDays(1))
			}
			is Instant -> {
				val zone = zones.zoneOf(vevent.first("DTSTART")!!)
				val endAt =
					when {
						end is Instant -> end
						end is LocalDate -> end.atStartOfDay(zone).toInstant()
						duration != null -> plusNominal(start.atZone(zone), durationValue, duration).toInstant()
						else -> start
					}
				Timing.Timed(start, endAt, zone)
			}
			else -> throw IllegalArgumentException("Unsupported start")
		}
	}

	/**
	 * [start] plus the DURATION [value] (read as [duration]): its days and weeks are calendar days in the start's zone,
	 * so they keep the local time across a daylight saving change; hours, minutes and seconds are exact time
	 * (RFC 5545 §3.3.6).
	 */
	private fun plusNominal(
		start: ZonedDateTime,
		value: String,
		duration: Duration,
	): ZonedDateTime {
		val match = DAYS.find(value.trim().uppercase()) ?: return start.plus(duration)
		val count = match.groupValues[2].toLong() * (if (match.groupValues[3] == "W") DAYS_PER_WEEK else 1)
		val days = if (match.groupValues[1] == "-") -count else count
		return start.plusDays(days).plus(duration.minusDays(days))
	}

	private fun exdates(
		vevent: IcsComponent,
		zones: Zones,
	): Set<OccurrenceId> =
		vevent
			.all("EXDATE")
			.flatMap { property ->
				property.value.split(',').filter { it.isNotBlank() }.mapNotNull { value ->
					runCatching { occurrence(zones.time(property.copy(value = value.trim()))) }.getOrNull()
				}
			}.toSet()

	private fun occurrence(time: Temporal): OccurrenceId =
		when (time) {
			is LocalDate -> OccurrenceId.of(time)
			is Instant -> OccurrenceId.of(time)
			else -> throw IllegalArgumentException("Unsupported occurrence")
		}

	private fun attendee(property: IcsProperty): Attendee? {
		val address = property.value.trim()
		if (!address.startsWith("mailto:", ignoreCase = true)) return null
		val status =
			when (property.param("PARTSTAT")?.uppercase()) {
				"ACCEPTED" -> AttendeeStatus.ACCEPTED
				"DECLINED" -> AttendeeStatus.DECLINED
				"TENTATIVE" -> AttendeeStatus.TENTATIVE
				"DELEGATED" -> AttendeeStatus.DELEGATED
				else -> AttendeeStatus.NEEDS_ACTION
			}
		return Attendee(address.substring(MAILTO.length).trim(), property.param("CN")?.trim()?.ifEmpty { null }, status)
	}

	/** Minutes before the start of a `VALARM` triggered relative to the start; null for any other trigger. */
	private fun reminderMinutes(alarm: IcsComponent): Int? {
		val trigger = alarm.first("TRIGGER") ?: return null
		if (trigger.param("VALUE")?.uppercase() == "DATE-TIME" || trigger.param("RELATED")?.uppercase() == "END") return null
		val offset = duration(trigger.value) ?: return null
		if (offset.isPositive) return null
		return offset.negated().toMinutes().toInt()
	}

	/** An iCalendar DURATION (`-P1W`, `P1DT2H`, `PT15M`); null when it is not one. */
	fun duration(value: String): Duration? {
		val text = value.trim().uppercase()
		val weeks = WEEKS.matchEntire(text)
		return try {
			if (weeks != null) {
				val days = Duration.ofDays(weeks.groupValues[2].toLong() * DAYS_PER_WEEK)
				if (weeks.groupValues[1] == "-") days.negated() else days
			} else {
				Duration.parse(text)
			}
		} catch (e: RuntimeException) {
			null
		}
	}

	private fun hash(text: String): String =
		MessageDigest
			.getInstance("SHA-256")
			.digest(text.toByteArray())
			.joinToString("") { "%02x".format(it) }

	/** Resolves the dates and time zones of one `VCALENDAR`. */
	class Zones(
		calendar: IcsComponent,
	) {
		/** Zone of floating times and of unknown TZIDs. */
		val default: ZoneId = calendar.first("X-WR-TIMEZONE")?.value?.let(::lookup) ?: ZoneOffset.UTC

		/** TZIDs of the feed's VTIMEZONEs to their `X-LIC-LOCATION`. */
		private val locations: Map<String, String> =
			calendar
				.children("VTIMEZONE")
				.mapNotNull { tz ->
					val id = tz.first("TZID")?.value?.trim() ?: return@mapNotNull null
					tz
						.first("X-LIC-LOCATION")
						?.value
						?.trim()
						?.let { id to it }
				}.toMap()

		/** The zone of a date-time property (its `TZID`, else [default]); UTC values still have their TZID's zone. */
		fun zoneOf(property: IcsProperty): ZoneId = property.param("TZID")?.let(::zone) ?: default

		private fun zone(tzid: String): ZoneId = lookup(tzid) ?: locations[tzid.trim()]?.let(::lookup) ?: default

		/** The value of a DATE or DATE-TIME property: a [LocalDate] or an [Instant]. */
		fun time(property: IcsProperty): Temporal {
			val value = property.value.trim()
			val isDate = property.param("VALUE")?.uppercase() == "DATE" || DATE.matches(value)
			if (isDate) return LocalDate.parse(value.take(DATE_LENGTH), BASIC_DATE)
			if (value.endsWith("Z", ignoreCase = true)) {
				return LocalDateTime.parse(value.dropLast(1), BASIC_DATE_TIME).toInstant(ZoneOffset.UTC)
			}
			return LocalDateTime.parse(value, BASIC_DATE_TIME).atZone(zoneOf(property)).toInstant()
		}

		private companion object {
			private val AREA_CITY = Regex("""([A-Za-z_]+/[A-Za-z_+\-]+(?:/[A-Za-z_+\-]+)?)$""")

			fun lookup(name: String): ZoneId? {
				val id = name.trim().removeSurrounding("\"")
				if (id.isEmpty()) return null
				return runCatching { ZoneId.of(id) }.getOrNull()
					?: AREA_CITY.find(id)?.let { match -> runCatching { ZoneId.of(match.groupValues[1]) }.getOrNull() }
					?: WindowsZones.zone(id)
			}
		}
	}

	private const val MAILTO = "mailto:"
	private const val GENERATED_UID_LENGTH = 32
	private const val DATE_LENGTH = 8
	private const val DAYS_PER_WEEK = 7
	private val WEEKS = Regex("""([+-]?)P(\d+)W""")
	private val DAYS = Regex("""^([+-]?)P(\d+)([DW])""")
	private val DATE = Regex("""\d{8}""")
	private val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
	private val BASIC_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
}
