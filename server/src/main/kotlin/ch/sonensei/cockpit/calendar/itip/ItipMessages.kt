package ch.sonensei.cockpit.calendar.itip

import ch.sonensei.cockpit.calendar.infra.caldav.CalDavEventMapping
import ch.sonensei.cockpit.calendar.infra.caldav.IcsComponent
import ch.sonensei.cockpit.calendar.infra.caldav.IcsProperty
import ch.sonensei.cockpit.calendar.infra.caldav.quoteParam
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.toRemote
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The iTIP methods (RFC 5546) the cockpit sends as organizer of a local event. */
enum class ItipMethod {
	/** Invites the attendees, or tells them the event changed. */
	REQUEST,

	/** Tells the attendees the event is cancelled (or that they are no longer invited). */
	CANCEL,
}

/** The organizer of an invitation: the address it is sent from, where replies go. */
data class ItipOrganizer(
	val email: String,
	val name: String?,
)

/**
 * An attendee's answer read from an iTIP REPLY: [uid] and [recurrenceId] name the event, [email] the attendee. [sequence]
 * (the SEQUENCE answered) and [stamp] (DTSTAMP, when the answer was written) order answers; null when missing or
 * unreadable.
 */
data class ItipReply(
	val uid: String,
	val recurrenceId: OccurrenceId?,
	val email: String,
	val status: AttendeeStatus,
	val sequence: Int? = null,
	val stamp: Instant? = null,
) {
	/** Same attendee answering for the same event or occurrence. */
	val key: Triple<String, OccurrenceId?, String> get() = Triple(uid.trim(), recurrenceId, email.lowercase())

	companion object {
		/** Older answers first: by SEQUENCE, then DTSTAMP (missing values count as oldest). */
		val ORDER: Comparator<ItipReply> =
			compareBy<ItipReply, Int?>(nullsFirst()) { it.sequence }.thenBy(nullsFirst()) { it.stamp }
	}
}

/**
 * iTIP (RFC 5546) messages for events of LOCAL calendars, whose organizer is the user: REQUEST and CANCEL objects to
 * send, REPLY objects to read.
 *
 * A local event's UID is the cockpit id of its series master (or single event); an override is sent with its master's
 * UID and its RECURRENCE-ID. The SEQUENCE is the event's version, which grows with every change. The VEVENTs are
 * written by [CalDavEventMapping], without the user's reminders.
 */
internal object ItipMessages {
	private const val MAILTO = "mailto:"
	private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")

	/** The UID of [event] in invitations: the id of its series master, or its own. */
	fun uid(event: CalendarEvent): String = (event.parentEventId ?: event.id).toString()

	/** The cockpit id of the series master or single event of iTIP [uid]; null when it is none of the cockpit's. */
	fun eventId(uid: String): UUID? = runCatching { UUID.fromString(uid.trim()) }.getOrNull()

	/**
	 * A VCALENDAR with method [method] for [events]: a single event or series master first (for a series, its
	 * overrides may follow), or one override after its [master] (needed to write its RECURRENCE-ID like the series'
	 * DTSTART; it is not part of the message). With [attendeesOnly], a CANCEL lists only those attendees: the ones
	 * that are no longer invited; it then has no STATUS, as the event itself goes ahead (RFC 5546 §3.2.5).
	 */
	fun build(
		method: ItipMethod,
		events: List<CalendarEvent>,
		master: CalendarEvent?,
		organizer: ItipOrganizer,
		now: Instant,
		attendeesOnly: List<String>? = null,
	): String {
		val calendar = CalDavEventMapping.newCalendar()
		calendar.set("METHOD", method.name)
		master?.let { CalDavEventMapping.write(calendar, it.toRemote(uid(it)), now) }
		events.forEach { CalDavEventMapping.write(calendar, it.toRemote(uid(it)), now) }
		if (master != null) calendar.components.remove(CalDavEventMapping.master(calendar, uid(master)))
		val sequences = events.map { it.version.toString() }
		calendar.children("VEVENT").zip(sequences).forEach { (vevent, sequence) ->
			vevent.set("SEQUENCE", sequence)
			vevent.components.removeAll { it.name == "VALARM" }
			vevent.set("ORGANIZER", MAILTO + organizer.email, listOfNotNull(organizer.name?.let { "CN" to quoteParam(it) }))
			if (method == ItipMethod.CANCEL) {
				if (attendeesOnly == null) {
					vevent.set("STATUS", EventStatus.CANCELLED.name)
				} else {
					// Uninviting some attendees does not cancel the event: RFC 5546 §3.2.5 forbids STATUS here.
					vevent.remove("STATUS")
					vevent.remove("ATTENDEE")
					attendeesOnly.forEach { vevent.properties += IcsProperty("ATTENDEE", mutableListOf(), MAILTO + it) }
				}
			}
			vevent.all("ATTENDEE").forEach { attendee ->
				attendee.setParam("ROLE", "REQ-PARTICIPANT")
				if (method == ItipMethod.REQUEST && attendee.param("PARTSTAT") == "NEEDS-ACTION") attendee.setParam("RSVP", "TRUE")
			}
		}
		return calendar.format()
	}

	/**
	 * The answers in iCalendar [text] when it is an iTIP REPLY (other methods give none): one per VEVENT with a UID,
	 * an ATTENDEE with a `mailto:` address and a PARTSTAT. VEVENTs that cannot be read are skipped.
	 *
	 * @throws IllegalArgumentException when [text] is no iCalendar object
	 */
	fun replies(text: String): List<ItipReply> {
		val calendar = CalDavEventMapping.calendar(text)
		if (!calendar.text("METHOD")?.trim().equals("REPLY", ignoreCase = true)) return emptyList()
		val zones = CalDavEventMapping.zones(calendar)
		return calendar.children("VEVENT").flatMap { vevent -> reply(vevent, zones) }
	}

	private fun reply(
		vevent: IcsComponent,
		zones: Map<String, IcsComponent>,
	): List<ItipReply> {
		val uid = vevent.text("UID")?.trim()?.ifEmpty { null } ?: return emptyList()
		val recurrenceId =
			try {
				vevent.property("RECURRENCE-ID")?.let { CalDavEventMapping.occurrence(it, zones) }
			} catch (e: IllegalArgumentException) {
				return emptyList()
			} catch (e: DateTimeException) {
				return emptyList()
			}
		val sequence = vevent.text("SEQUENCE")?.trim()?.toIntOrNull()
		val stamp = stamp(vevent.text("DTSTAMP"))
		return vevent.all("ATTENDEE").mapNotNull { attendee ->
			val value = attendee.value.trim()
			if (!value.startsWith(MAILTO, ignoreCase = true)) return@mapNotNull null
			val email = value.substring(MAILTO.length).trim().ifEmpty { null } ?: return@mapNotNull null
			val status = partStat(attendee.param("PARTSTAT")) ?: return@mapNotNull null
			ItipReply(uid, recurrenceId, email, status, sequence, stamp)
		}
	}

	/** A DTSTAMP value: always UTC (RFC 5545 §3.8.7.2); null when it cannot be read. */
	private fun stamp(value: String?): Instant? =
		try {
			value?.trim()?.let { LocalDateTime.parse(it, STAMP).toInstant(ZoneOffset.UTC) }
		} catch (e: DateTimeException) {
			null
		}

	private fun partStat(value: String?): AttendeeStatus? =
		when (value?.trim()?.uppercase()) {
			"ACCEPTED" -> AttendeeStatus.ACCEPTED
			"DECLINED" -> AttendeeStatus.DECLINED
			"TENTATIVE" -> AttendeeStatus.TENTATIVE
			"DELEGATED" -> AttendeeStatus.DELEGATED
			"NEEDS-ACTION" -> AttendeeStatus.NEEDS_ACTION
			else -> null
		}
}
