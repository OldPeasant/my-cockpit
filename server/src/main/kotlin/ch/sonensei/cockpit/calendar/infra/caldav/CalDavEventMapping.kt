package ch.sonensei.cockpit.calendar.infra.caldav

import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.infra.microsoft.WindowsZones
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneRules

/**
 * iCalendar resources of a CalDAV calendar to [RemoteEvent]s and back (RFC 4791: one resource per UID, holding the
 * series master or single event and the overrides of its occurrences as VEVENTs with a RECURRENCE-ID).
 *
 * Times: a TZID is resolved as an IANA zone (also in path form like `/mozilla.org/.../Europe/Zurich`), a Windows zone
 * name, the `X-LIC-LOCATION` of its VTIMEZONE, or else the standard offset of that VTIMEZONE; floating times are UTC.
 * Written times use the IANA zone with a VTIMEZONE generated from the Java zone rules (UTC as `Z`).
 */
internal object CalDavEventMapping {
	private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
	private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
	private val DURATION =
		Regex("""([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")
	const val PRODID = "-//sonensei//My Cockpit//EN"

	/** The events of one resource and how many of its VEVENTs could not be read. */
	class Resource(
		val uid: String?,
		val events: List<RemoteEvent>,
		val skipped: Int,
	)

	/** The VCALENDAR of iCalendar [data]. @throws IllegalArgumentException when there is none */
	fun calendar(data: String): IcsComponent =
		IcsComponent.parse(data).firstOrNull { it.name == "VCALENDAR" } ?: throw IllegalArgumentException("No VCALENDAR")

	/**
	 * The events of resource [data] with version [etag]: master or single event first, then its overrides. VEVENTs that
	 * cannot be read are skipped, overrides without their series too (a series only shares single occurrences).
	 *
	 * @throws IllegalArgumentException when [data] is no iCalendar object
	 */
	fun parse(
		data: String,
		etag: String?,
	): Resource {
		val calendar = calendar(data)
		val zones = zones(calendar)
		val vevents = calendar.children("VEVENT")
		val uid = vevents.firstNotNullOfOrNull { it.text("UID")?.trim()?.ifEmpty { null } }
		val (masters, overrides) = vevents.filter { it.text("UID")?.trim() == uid }.partition { it.property("RECURRENCE-ID") == null }
		var skipped = vevents.size - masters.size - overrides.size
		val master = masters.firstOrNull()?.let { readOrNull(it, zones, etag) }
		skipped += masters.size - (if (master != null) 1 else 0)
		val exceptions =
			if (master?.rrule == null) {
				skipped += overrides.size
				emptyList()
			} else {
				overrides.mapNotNull { readOrNull(it, zones, etag) }.also { skipped += overrides.size - it.size }
			}
		return Resource(uid, listOfNotNull(master) + exceptions, skipped)
	}

	private fun readOrNull(
		vevent: IcsComponent,
		zones: Map<String, IcsComponent>,
		etag: String?,
	): RemoteEvent? =
		try {
			read(vevent, zones, etag)
		} catch (e: IllegalArgumentException) {
			null
		} catch (e: DateTimeException) {
			null
		} catch (e: ArithmeticException) {
			null
		}

	/**
	 * One VEVENT as a remote event.
	 *
	 * @throws IllegalArgumentException, [DateTimeException] or [ArithmeticException] (times out of range) when it cannot
	 *   be read
	 */
	fun read(
		vevent: IcsComponent,
		zones: Map<String, IcsComponent>,
		etag: String?,
	): RemoteEvent {
		val uid = vevent.text("UID")?.trim()?.ifEmpty { null } ?: throw IllegalArgumentException("A VEVENT without UID")
		val recurrenceId = vevent.property("RECURRENCE-ID")?.let { occurrence(it, zones) }
		val timing = timing(vevent, zones)
		val rrule =
			if (recurrenceId == null) {
				vevent
					.property("RRULE")
					?.value
					?.trim()
					?.ifEmpty { null }
			} else {
				null
			}
		val exdates =
			if (rrule == null) {
				emptySet()
			} else {
				vevent
					.all("EXDATE")
					.flatMap { property -> property.value.split(',').map { times(property, it.trim(), zones) } }
					.map { asOccurrenceOf(it, timing) }
					.toSet()
			}
		return RemoteEvent(
			key = RemoteEventKey(uid, recurrenceId),
			etag = etag,
			title = vevent.text("SUMMARY").orEmpty(),
			timing = timing,
			description = vevent.text("DESCRIPTION")?.ifEmpty { null },
			location = vevent.text("LOCATION")?.ifEmpty { null },
			rrule = rrule,
			exdates = exdates,
			attendees = vevent.all("ATTENDEE").mapNotNull(::attendee),
			reminderMinutes =
				vevent
					.children("VALARM")
					.mapNotNull(::reminderMinutes)
					.distinct()
					.sorted(),
			status =
				when (vevent.text("STATUS")?.trim()?.uppercase()) {
					"CANCELLED" -> EventStatus.CANCELLED
					"TENTATIVE" -> EventStatus.TENTATIVE
					else -> EventStatus.CONFIRMED
				},
			transparency =
				if (vevent.text("TRANSP")?.trim()?.uppercase() == "TRANSPARENT") EventTransparency.TRANSPARENT else EventTransparency.OPAQUE,
			conferenceUrl =
				vevent.all("CONFERENCE").map { it.value.trim() }.firstOrNull {
					it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
				},
		)
	}

	// --- times

	/** A DATE or DATE-TIME value: [Day] or [Moment] (with the zone it was written in). */
	sealed interface Time {
		data class Day(
			val date: LocalDate,
		) : Time

		data class Moment(
			val at: Instant,
			val zone: ZoneId,
		) : Time
	}

	fun zones(calendar: IcsComponent): Map<String, IcsComponent> =
		calendar.children("VTIMEZONE").mapNotNull { tz -> tz.text("TZID")?.let { it to tz } }.toMap()

	private fun timing(
		vevent: IcsComponent,
		zones: Map<String, IcsComponent>,
	): Timing {
		val dtstart = vevent.property("DTSTART") ?: throw IllegalArgumentException("A VEVENT without DTSTART")
		val start = times(dtstart, dtstart.value.trim(), zones)
		val end = vevent.property("DTEND")?.let { times(it, it.value.trim(), zones) }
		val duration =
			if (end == null) {
				vevent.property("DURATION")?.let {
					duration(it.value.trim()) ?: throw IllegalArgumentException("A VEVENT with an unreadable DURATION")
				}
			} else {
				null
			}
		return when (start) {
			is Time.Day -> {
				val endDate =
					when {
						end is Time.Day -> end.date
						end is Time.Moment -> end.at.atZone(end.zone).toLocalDate()
						duration != null -> start.date.plusDays(Math.addExact(duration.days, duration.time.toDays()))
						else -> start.date.plusDays(1)
					}
				// A zero-length all-day event is one day long.
				Timing.AllDay(start.date, if (endDate.isAfter(start.date)) endDate else start.date.plusDays(1))
			}
			is Time.Moment -> {
				val endAt =
					when {
						end is Time.Moment -> end.at
						end is Time.Day -> end.date.atStartOfDay(start.zone).toInstant()
						duration != null -> plus(start, duration)
						else -> start.at
					}
				Timing.Timed(start.at.truncatedTo(ChronoUnit.MICROS), endAt.truncatedTo(ChronoUnit.MICROS), start.zone)
			}
		}
	}

	/** Start plus an iCalendar duration: days are calendar days in the event's zone, the time is exact (RFC 5545 §3.3.6). */
	private fun plus(
		start: Time.Moment,
		duration: IcsDuration,
	): Instant =
		start.at
			.atZone(start.zone)
			.plusDays(duration.days)
			.plus(duration.time)
			.toInstant()

	/** The value [value] of date property [property] (its VALUE and TZID parameters apply). */
	fun times(
		property: IcsProperty,
		value: String,
		zones: Map<String, IcsComponent>,
	): Time {
		if (property.param("VALUE").equals("DATE", ignoreCase = true) || value.length == DATE_LENGTH) {
			return Time.Day(LocalDate.parse(value.take(DATE_LENGTH), DATE))
		}
		val utc = value.endsWith("Z") || value.endsWith("z")
		val local = LocalDateTime.parse(value.trimEnd('Z', 'z').take(LOCAL_LENGTH), LOCAL)
		val zone = if (utc) ZoneOffset.UTC else zone(property.param("TZID"), zones)
		return Time.Moment(local.atZone(zone).toInstant(), zone)
	}

	/** The occurrence a RECURRENCE-ID names. */
	fun occurrence(
		property: IcsProperty,
		zones: Map<String, IcsComponent>,
	): OccurrenceId =
		when (val time = times(property, property.value.trim(), zones)) {
			is Time.Day -> OccurrenceId.of(time.date)
			is Time.Moment -> OccurrenceId.of(time.at.truncatedTo(ChronoUnit.MICROS))
		}

	/** An EXDATE as an occurrence of a series with [timing]: a date of an all-day series, else a time. */
	private fun asOccurrenceOf(
		time: Time,
		timing: Timing,
	): OccurrenceId =
		when (timing) {
			is Timing.AllDay ->
				when (time) {
					is Time.Day -> OccurrenceId.of(time.date)
					is Time.Moment -> OccurrenceId.of(time.at.atZone(time.zone).toLocalDate())
				}
			is Timing.Timed ->
				when (time) {
					is Time.Moment -> OccurrenceId.of(time.at.truncatedTo(ChronoUnit.MICROS))
					is Time.Day ->
						OccurrenceId.of(
							time.date
								.atTime(timing.start.atZone(timing.zone).toLocalTime())
								.atZone(timing.zone)
								.toInstant()
								.truncatedTo(ChronoUnit.MICROS),
						)
				}
		}

	/** The zone of [tzid] (see the class comment); UTC for floating times. */
	fun zone(
		tzid: String?,
		zones: Map<String, IcsComponent>,
	): ZoneId {
		if (tzid.isNullOrBlank()) return ZoneOffset.UTC
		known(tzid)?.let { return it }
		val vtimezone = zones[tzid] ?: return ZoneOffset.UTC
		vtimezone.text("X-LIC-LOCATION")?.let(::known)?.let { return it }
		val standard =
			vtimezone
				.children("STANDARD")
				.maxByOrNull { it.property("DTSTART")?.value.orEmpty() }
				?: vtimezone.children("DAYLIGHT").firstOrNull()
		return standard?.text("TZOFFSETTO")?.let { runCatching { ZoneOffset.of(it.trim()) }.getOrNull() } ?: ZoneOffset.UTC
	}

	private fun known(tzid: String): ZoneId? {
		val id = tzid.trim()
		val available = ZoneId.getAvailableZoneIds()
		if (id in available) return ZoneId.of(id)
		if (id.equals("UTC", ignoreCase = true) || id.equals("GMT", ignoreCase = true) || id == "Z") return ZoneOffset.UTC
		WindowsZones.zone(id)?.let { return it }
		// Path forms such as /mozilla.org/20050126_1/Europe/Zurich: the longest known suffix.
		val segments = id.split('/').filter { it.isNotEmpty() }
		return segments.indices
			.map { segments.drop(it).joinToString("/") }
			.firstOrNull { it in available }
			?.let(ZoneId::of)
	}

	/**
	 * An iCalendar duration (RFC 5545 §3.3.6): [days] (weeks included) are nominal, calendar days in the event's zone;
	 * [time] (hours, minutes, seconds) is exact. Both have the duration's sign.
	 */
	data class IcsDuration(
		val days: Long,
		val time: Duration,
	) {
		val isPositive: Boolean get() = days > 0 || time > Duration.ZERO

		/** In minutes, a day as 24 hours. @throws ArithmeticException when that does not fit a Long */
		fun toMinutes(): Long = Math.addExact(Math.multiplyExact(days, MINUTES_PER_DAY), time.toMinutes())
	}

	/** The iCalendar duration [value]; null when it is malformed or out of range. */
	fun duration(value: String): IcsDuration? {
		val match = DURATION.matchEntire(value) ?: return null
		val (sign, weeks, days, hours, minutes, seconds) = match.destructured
		return try {
			val nominal = Math.addExact(Math.multiplyExact(number(weeks), DAYS_PER_WEEK.toLong()), number(days))
			val exact =
				Duration
					.ofHours(number(hours))
					.plusMinutes(number(minutes))
					.plusSeconds(number(seconds))
			if (sign == "-") IcsDuration(-nominal, exact.negated()) else IcsDuration(nominal, exact)
		} catch (e: ArithmeticException) {
			null
		} catch (e: NumberFormatException) {
			null
		}
	}

	/** The digits [digits] of a duration part (0 when absent). @throws NumberFormatException when they exceed a Long */
	private fun number(digits: String): Long = if (digits.isEmpty()) 0 else digits.toLong()

	// --- attendees, alarms

	private fun attendee(property: IcsProperty): Attendee? {
		val value = property.value.trim()
		if (!value.startsWith("mailto:", ignoreCase = true)) return null
		val email = value.substring("mailto:".length).trim()
		if (email.isEmpty()) return null
		return Attendee(email, property.param("CN")?.trim()?.ifEmpty { null }, partStat(property.param("PARTSTAT")))
	}

	private fun partStat(value: String?): AttendeeStatus =
		when (value?.trim()?.uppercase()) {
			"ACCEPTED" -> AttendeeStatus.ACCEPTED
			"DECLINED" -> AttendeeStatus.DECLINED
			"TENTATIVE" -> AttendeeStatus.TENTATIVE
			"DELEGATED" -> AttendeeStatus.DELEGATED
			else -> AttendeeStatus.NEEDS_ACTION
		}

	private fun partStat(status: AttendeeStatus): String = status.name.replace('_', '-')

	/** Minutes before the start of an alarm relative to the start; null for other alarms. */
	private fun reminderMinutes(valarm: IcsComponent): Int? {
		val trigger = valarm.property("TRIGGER") ?: return null
		if (trigger.param("VALUE").equals("DATE-TIME", ignoreCase = true)) return null
		if (trigger.param("RELATED").equals("END", ignoreCase = true)) return null
		val duration = duration(trigger.value.trim()) ?: return null
		if (duration.isPositive) return null
		return try {
			Math.toIntExact(Math.negateExact(duration.toMinutes()))
		} catch (e: ArithmeticException) {
			null
		}
	}

	// --- writing

	/** A new, empty VCALENDAR. */
	fun newCalendar(): IcsComponent =
		IcsComponent("VCALENDAR").apply {
			set("VERSION", "2.0")
			set("PRODID", PRODID)
		}

	/**
	 * Writes [event] into [calendar] (the resource of its UID): changes its VEVENT in place, or adds one (an override of
	 * its series, which must be in [calendar]). Properties the cockpit does not edit stay as they are.
	 */
	fun write(
		calendar: IcsComponent,
		event: RemoteEvent,
		now: Instant,
	) {
		val uid = event.key.externalId
		val occurrence = event.key.recurrenceId
		val zones = zones(calendar)
		val existing =
			if (occurrence == null) {
				master(calendar, uid)
			} else {
				overrides(calendar, uid).firstOrNull { runCatching { occurrence(it.property("RECURRENCE-ID")!!, zones) }.getOrNull() == occurrence }
			}
		val vevent =
			existing ?: IcsComponent("VEVENT").also { created ->
				created.set("UID", escapeText(uid))
				if (occurrence != null) {
					val master = master(calendar, uid) ?: throw IllegalArgumentException("The series is not in the resource")
					val (value, params) = formatLike(master.property("DTSTART"), occurrence, zones)
					created.set("RECURRENCE-ID", value, params)
				}
				calendar.components += created
			}
		vevent.set("DTSTAMP", stamp(now))
		vevent.set("LAST-MODIFIED", stamp(now))
		val sequence =
			vevent
				.property("SEQUENCE")
				?.value
				?.trim()
				?.toIntOrNull()
		vevent.set("SEQUENCE", ((sequence ?: -1) + 1).toString())
		vevent.setText("SUMMARY", event.title)
		vevent.setText("DESCRIPTION", event.description)
		vevent.setText("LOCATION", event.location)
		vevent.remove("DURATION")
		writeTiming(calendar, vevent, event.timing)
		if (occurrence == null) {
			vevent.set("RRULE", event.rrule)
			vevent.remove("RDATE")
			vevent.remove("EXDATE")
			if (event.rrule != null) {
				event.exdates.sortedWith(compareBy({ it.date }, { it.at })).forEach { exdate ->
					val (value, params) = formatOccurrence(exdate, event.timing)
					vevent.properties += IcsProperty("EXDATE", params.toMutableList(), value)
				}
			}
		}
		writeAttendees(vevent, event.attendees)
		writeAlarms(vevent, event.reminderMinutes)
		vevent.set("STATUS", event.status.name)
		vevent.set("TRANSP", event.transparency.name)
		vevent.remove("CONFERENCE")
		event.conferenceUrl?.let {
			vevent.properties += IcsProperty("CONFERENCE", mutableListOf("VALUE" to "URI", "FEATURE" to "VIDEO"), it)
		}
	}

	/** Removes occurrence [occurrence] of series [uid] from [calendar]: drops its override and adds an EXDATE. */
	fun removeOccurrence(
		calendar: IcsComponent,
		uid: String,
		occurrence: OccurrenceId,
		now: Instant,
	) {
		val zones = zones(calendar)
		calendar.components.removeAll { component ->
			component.name == "VEVENT" &&
				component.text("UID")?.trim() == uid &&
				component.property("RECURRENCE-ID")?.let { runCatching { occurrence(it, zones) }.getOrNull() } == occurrence
		}
		val master = master(calendar, uid) ?: return
		val listed =
			master.all("EXDATE").any { property ->
				property.value.split(',').any {
					runCatching { occurrence(IcsProperty("EXDATE", property.params, it.trim()), zones) }.getOrNull() ==
						occurrence
				}
			}
		if (!listed) {
			val (value, params) = formatLike(master.property("DTSTART"), occurrence, zones)
			master.properties += IcsProperty("EXDATE", params.toMutableList(), value)
			master.set("DTSTAMP", stamp(now))
			master.set("LAST-MODIFIED", stamp(now))
		}
	}

	fun master(
		calendar: IcsComponent,
		uid: String,
	): IcsComponent? = calendar.children("VEVENT").firstOrNull { it.text("UID")?.trim() == uid && it.property("RECURRENCE-ID") == null }

	private fun overrides(
		calendar: IcsComponent,
		uid: String,
	): List<IcsComponent> = calendar.children("VEVENT").filter { it.text("UID")?.trim() == uid && it.property("RECURRENCE-ID") != null }

	private fun writeTiming(
		calendar: IcsComponent,
		vevent: IcsComponent,
		timing: Timing,
	) {
		when (timing) {
			is Timing.AllDay -> {
				vevent.set("DTSTART", timing.startDate.format(DATE), listOf("VALUE" to "DATE"))
				vevent.set("DTEND", timing.endDate.format(DATE), listOf("VALUE" to "DATE"))
			}
			is Timing.Timed -> {
				val (start, params) = formatTime(timing.start, timing.zone)
				vevent.set("DTSTART", start, params)
				vevent.set("DTEND", formatTime(timing.end, timing.zone).first, params)
				if (params.isNotEmpty()) ensureTimeZone(calendar, timing.zone)
			}
		}
	}

	/** [at] in [zone]: UTC (and fixed offsets, which have no VTIMEZONE) as `...Z`, else local time with TZID. */
	private fun formatTime(
		at: Instant,
		zone: ZoneId,
	): Pair<String, List<Pair<String, String>>> =
		if (zone is ZoneOffset || zone.normalized() is ZoneOffset) {
			stamp(at) to emptyList()
		} else {
			at.atZone(zone).toLocalDateTime().format(LOCAL) to listOf("TZID" to quoteParam(zone.id))
		}

	private fun formatOccurrence(
		occurrence: OccurrenceId,
		timing: Timing,
	): Pair<String, List<Pair<String, String>>> =
		occurrence.date?.let { it.format(DATE) to listOf("VALUE" to "DATE") }
			?: formatTime(occurrence.at!!, (timing as? Timing.Timed)?.zone ?: ZoneOffset.UTC)

	/** [occurrence] written like the series' DTSTART [dtstart] (same TZID), as RECURRENCE-ID and EXDATE must be. */
	private fun formatLike(
		dtstart: IcsProperty?,
		occurrence: OccurrenceId,
		zones: Map<String, IcsComponent>,
	): Pair<String, List<Pair<String, String>>> {
		occurrence.date?.let { return it.format(DATE) to listOf("VALUE" to "DATE") }
		val at = occurrence.at!!
		val tzid = dtstart?.param("TZID")
		if (dtstart == null || dtstart.value.trim().endsWith("Z", ignoreCase = true)) return stamp(at) to emptyList()
		val zone = zone(tzid, zones)
		val local = at.atZone(zone).toLocalDateTime().format(LOCAL)
		return if (tzid == null) local to emptyList() else local to listOf("TZID" to quoteParam(tzid))
	}

	private fun stamp(at: Instant): String = at.atOffset(ZoneOffset.UTC).toLocalDateTime().format(LOCAL) + "Z"

	/** Keeps the ATTENDEE lines of [attendees] that exist (with their other parameters), adds the new ones. */
	private fun writeAttendees(
		vevent: IcsComponent,
		attendees: List<Attendee>,
	) {
		val existing = vevent.all("ATTENDEE")
		val lines =
			attendees.map { attendee ->
				val line =
					existing.firstOrNull { it.value.trim().equals("mailto:${attendee.email}", ignoreCase = true) }
						?: IcsProperty("ATTENDEE", mutableListOf(), "mailto:${attendee.email}")
				line.setParam("CN", attendee.name)
				line.setParam("PARTSTAT", partStat(attendee.status))
				line
			}
		val index = vevent.properties.indexOfFirst { it.name == "ATTENDEE" }
		vevent.remove("ATTENDEE")
		vevent.properties.addAll(if (index < 0) vevent.properties.size else index, lines)
	}

	/** Keeps the alarms that are still wanted (with their details) and the ones the cockpit cannot show; adds new ones. */
	private fun writeAlarms(
		vevent: IcsComponent,
		minutes: List<Int>,
	) {
		val wanted = minutes.toMutableSet()
		vevent.components.removeAll { component ->
			if (component.name != "VALARM") return@removeAll false
			val existing = reminderMinutes(component) ?: return@removeAll false
			!wanted.remove(existing)
		}
		wanted.sorted().forEach { m ->
			vevent.components +=
				IcsComponent("VALARM").apply {
					set("ACTION", "DISPLAY")
					set("DESCRIPTION", "Reminder")
					set("TRIGGER", if (m == 0) "PT0S" else "-PT${m}M")
				}
		}
	}

	// --- VTIMEZONE

	/** Adds a VTIMEZONE for [zone] to [calendar] unless it has one with its id. */
	private fun ensureTimeZone(
		calendar: IcsComponent,
		zone: ZoneId,
	) {
		if (calendar.children("VTIMEZONE").any { it.text("TZID") == zone.id }) return
		val index = calendar.components.indexOfFirst { it.name != "VTIMEZONE" }
		calendar.components.add(if (index < 0) calendar.components.size else index, vtimezone(zone))
	}

	/** A VTIMEZONE for [zone] from its current Java rules (yearly transitions as RRULEs, else its present offset). */
	fun vtimezone(zone: ZoneId): IcsComponent = vtimezone(zone.id, zone.rules)

	/** A VTIMEZONE with id [tzid] for [rules]. */
	fun vtimezone(
		tzid: String,
		rules: ZoneRules,
	): IcsComponent {
		val vtimezone = IcsComponent("VTIMEZONE").apply { set("TZID", tzid) }
		val transitions = rules.transitionRules
		if (transitions.isEmpty()) {
			val offset = rules.getOffset(Instant.now())
			vtimezone.components += observance("STANDARD", EPOCH, offset, offset, null)
			return vtimezone
		}
		transitions.forEach { rule ->
			val kind = if (rule.offsetAfter == rule.standardOffset) "STANDARD" else "DAYLIGHT"
			vtimezone.components += observances(kind, rule)
		}
		return vtimezone
	}

	/**
	 * The observances of the yearly transition [rule], at its wall-clock time before the transition. That can fall on
	 * another day than the rule's own date (a time in UTC or standard time, or 24:00), so the rule's days are shifted by
	 * that difference. Where the shifted days reach into a neighbouring month, each month gets its own observance; where
	 * that depends on leap years, the transitions up to [LAST_LISTED_YEAR] are listed as RDATEs.
	 */
	private fun observances(
		kind: String,
		rule: ZoneOffsetTransitionRule,
	): List<IcsComponent> {
		val years = REFERENCE_YEAR..LAST_LISTED_YEAR
		val onsets = years.map { rule.createTransition(it).dateTimeBefore }
		val shift = ChronoUnit.DAYS.between(ruleDate(rule, REFERENCE_YEAR), onsets.first().toLocalDate()).toInt()
		val days =
			window(rule, shift)
				?: return listOf(
					observance(kind, onsets.first(), rule.offsetBefore, rule.offsetAfter, null).apply {
						set("RDATE", onsets.drop(1).joinToString(",") { it.format(LOCAL) })
					},
				)
		val weekday = rule.dayOfWeek?.plus(shift.toLong())
		return days.groupBy({ it.first }, { it.second }).map { (monthShift, monthDays) ->
			val month = rule.month.plus(monthShift.toLong())
			val byDay = weekday?.let { ";BYDAY=${it.name.take(2)}" }.orEmpty()
			observance(
				kind,
				onsets.first { it.month == month },
				rule.offsetBefore,
				rule.offsetAfter,
				"FREQ=YEARLY;BYMONTH=${month.value}$byDay;BYMONTHDAY=${monthDays.joinToString(",")}",
			)
		}
	}

	/** The date of [rule] in [year] before its time of day applies, as [ZoneOffsetTransitionRule.createTransition] finds it. */
	private fun ruleDate(
		rule: ZoneOffsetTransitionRule,
		year: Int,
	): LocalDate {
		val indicator = rule.dayOfMonthIndicator
		val month = YearMonth.of(year, rule.month)
		val date = if (indicator < 0) month.atEndOfMonth().plusDays(indicator + 1L) else month.atDay(1).plusDays(indicator - 1L)
		val weekday = rule.dayOfWeek ?: return date
		return date.with(if (indicator < 0) TemporalAdjusters.previousOrSame(weekday) else TemporalAdjusters.nextOrSame(weekday))
	}

	/**
	 * The days the wall-clock transition of [rule] can fall on, shifted by [shift] days, as (month shift, BYMONTHDAY): the
	 * rule's day, or the week from (or up to) it for a weekday rule. Null when a day's month depends on leap years.
	 */
	private fun window(
		rule: ZoneOffsetTransitionRule,
		shift: Int,
	): List<Pair<Int, Int>>? {
		val indicator = rule.dayOfMonthIndicator
		val first = indicator + shift
		val days =
			when {
				rule.dayOfWeek == null -> first..first
				indicator > 0 -> first..(first + DAYS_PER_WEEK - 1)
				else -> (first - DAYS_PER_WEEK + 1)..first
			}
		return days.map { monthDay(rule.month, it, fromStart = indicator > 0) ?: return null }
	}

	/**
	 * Day [day] of [month] (counted from its start, or from its end when negative) as (month shift, BYMONTHDAY) in the
	 * month it falls in. Null when that depends on leap years.
	 */
	private fun monthDay(
		month: Month,
		day: Int,
		fromStart: Boolean,
	): Pair<Int, Int>? {
		val length = month.minLength()
		val fixedLength = length == month.maxLength()
		return if (fromStart) {
			when {
				day in 1..length -> 0 to day
				day < 1 -> -1 to day - 1
				fixedLength -> 1 to day - length
				else -> null
			}
		} else {
			when {
				day in -length..-1 -> 0 to day
				day >= 0 -> 1 to day + 1
				fixedLength -> -1 to day + length
				else -> null
			}
		}
	}

	private fun observance(
		kind: String,
		start: LocalDateTime,
		from: ZoneOffset,
		to: ZoneOffset,
		rrule: String?,
	): IcsComponent =
		IcsComponent(kind).apply {
			set("DTSTART", start.format(LOCAL))
			set("TZOFFSETFROM", offset(from))
			set("TZOFFSETTO", offset(to))
			set("RRULE", rrule)
		}

	private fun offset(offset: ZoneOffset): String {
		val seconds = offset.totalSeconds
		val abs = Math.abs(seconds)
		val sign = if (seconds < 0) "-" else "+"
		val hhmm = "%02d%02d".format(abs / SECONDS_PER_HOUR, abs % SECONDS_PER_HOUR / SECONDS_PER_MINUTE)
		val ss = abs % SECONDS_PER_MINUTE
		return sign + hhmm + (if (ss != 0) "%02d".format(ss) else "")
	}

	private const val DATE_LENGTH = 8
	private const val LOCAL_LENGTH = 15
	private const val DAYS_PER_WEEK = 7
	private const val MINUTES_PER_DAY = 1440L
	private const val SECONDS_PER_HOUR = 3600
	private const val SECONDS_PER_MINUTE = 60
	private const val REFERENCE_YEAR = 1970
	private const val LAST_LISTED_YEAR = 2100
	private val EPOCH: LocalDateTime = LocalDateTime.of(REFERENCE_YEAR, 1, 1, 0, 0)
}
