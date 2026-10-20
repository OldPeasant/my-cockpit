package ch.sonensei.cockpit.calendar.model

import ch.sonensei.cockpit.core.persistence.SoftDeletableEntity
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * An event of a [Calendar] (SPEC.md §6.1), or one exception of a recurring series.
 *
 * Time: a timed event has [startAt]/[endAt] (UTC instants) and the [timeZone] it was planned in (recurrences keep
 * their wall-clock time in that zone); an all-day event has [startDate] and an exclusive [endDate] (a one-day event on
 * the 3rd ends on the 4th). Set them with [setTimed] or [setAllDay], which keep the two forms apart.
 *
 * Recurrence: a series is a master event with [rrule] (an iCalendar RRULE without the `RRULE:` prefix) and [exdates]
 * (excluded occurrences). An override is a separate event with [parentEventId] (the master) and [recurrenceId] (the
 * original start of the occurrence it replaces); a [EventStatus.CANCELLED] override removes the occurrence.
 *
 * [externalId], [externalEtag] and [syncState] tie the row to the provider's copy (SPEC.md §6.2).
 */
@Entity
@Table(name = "calendar_event")
class CalendarEvent(
	@Column(name = "calendar_id", nullable = false)
	var calendarId: UUID,
	@Column(name = "title", nullable = false)
	var title: String,
	id: UUID? = null,
) : SoftDeletableEntity(id) {
	@Column(name = "description")
	var description: String? = null

	@Column(name = "location")
	var location: String? = null

	@Column(name = "all_day", nullable = false)
	var allDay: Boolean = false
		protected set

	@Column(name = "start_at")
	var startAt: Instant? = null
		protected set

	@Column(name = "end_at")
	var endAt: Instant? = null
		protected set

	/** IANA zone id of a timed event, e.g. `Europe/Zurich`. */
	@Column(name = "time_zone")
	var timeZone: String? = null
		protected set

	@Column(name = "start_date")
	var startDate: LocalDate? = null
		protected set

	/** Exclusive. */
	@Column(name = "end_date")
	var endDate: LocalDate? = null
		protected set

	@Column(name = "rrule")
	var rrule: String? = null

	@ElementCollection
	@CollectionTable(name = "calendar_event_exdate", joinColumns = [JoinColumn(name = "event_id")])
	var exdates: MutableSet<OccurrenceId> = mutableSetOf()

	@Column(name = "parent_event_id", updatable = false)
	var parentEventId: UUID? = null
		protected set

	@Column(name = "recurrence_id_at", updatable = false)
	private var recurrenceIdAt: Instant? = null

	@Column(name = "recurrence_id_date", updatable = false)
	private var recurrenceIdDate: LocalDate? = null

	/** The original start of the occurrence this override replaces; null for a normal event or a series master. */
	val recurrenceId: OccurrenceId?
		get() = if (recurrenceIdAt == null && recurrenceIdDate == null) null else OccurrenceId(recurrenceIdAt, recurrenceIdDate)

	val isOverride: Boolean get() = parentEventId != null

	val isRecurring: Boolean get() = rrule != null

	@Column(name = "attendees", nullable = false)
	@JdbcTypeCode(SqlTypes.JSON)
	var attendees: List<Attendee> = emptyList()

	/** Minutes before the start of each reminder. */
	@Column(name = "reminder_minutes", nullable = false)
	@JdbcTypeCode(SqlTypes.ARRAY)
	var reminderMinutes: List<Int> = emptyList()

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	var status: EventStatus = EventStatus.CONFIRMED

	@Enumerated(EnumType.STRING)
	@Column(name = "transparency", nullable = false)
	var transparency: EventTransparency = EventTransparency.OPAQUE

	@Column(name = "conference_url")
	var conferenceUrl: String? = null

	@Column(name = "external_id")
	var externalId: String? = null

	@Column(name = "external_etag")
	var externalEtag: String? = null

	@Enumerated(EnumType.STRING)
	@Column(name = "sync_state", nullable = false)
	var syncState: EventSyncState = EventSyncState.LOCAL

	/** Makes this a timed event from [start] to [end] (not before [start]), planned in [zone]. */
	fun setTimed(
		start: Instant,
		end: Instant,
		zone: ZoneId,
	) {
		require(!end.isBefore(start)) { "An event cannot end before it starts" }
		allDay = false
		startAt = start
		endAt = end
		timeZone = zone.id
		startDate = null
		endDate = null
	}

	/** Makes this an all-day event from [start] to the exclusive [end] (after [start]). */
	fun setAllDay(
		start: LocalDate,
		end: LocalDate = start.plusDays(1),
	) {
		require(end.isAfter(start)) { "An all-day event ends after its first day (the end date is exclusive)" }
		allDay = true
		startDate = start
		endDate = end
		startAt = null
		endAt = null
		timeZone = null
	}

	/** Makes this event the override of the occurrence [occurrence] of the series [master]. Only once, before saving. */
	fun overrideOf(
		master: CalendarEvent,
		occurrence: OccurrenceId,
	) {
		check(parentEventId == null) { "The event already overrides an occurrence" }
		require(master.isRecurring && !master.isOverride) { "Only an occurrence of a recurring series can be overridden" }
		parentEventId = master.id
		recurrenceIdAt = occurrence.at
		recurrenceIdDate = occurrence.date
		rrule = null
	}
}

/**
 * Identifies an occurrence of a series by its original start: [at] for a timed series, [date] for an all-day series
 * (exactly one is set). Used for EXDATEs and the recurrence id of overrides.
 */
@Embeddable
data class OccurrenceId(
	@Column(name = "occurrence_at")
	val at: Instant?,
	@Column(name = "occurrence_date")
	val date: LocalDate?,
) {
	init {
		require((at == null) != (date == null)) { "An occurrence is identified by either an instant or a date" }
	}

	companion object {
		fun of(at: Instant) = OccurrenceId(at, null)

		fun of(date: LocalDate) = OccurrenceId(null, date)
	}
}

/**
 * An invited person (iCalendar `ATTENDEE`), stored as JSON. Hibernate's own JSON mapper has no Kotlin support, hence
 * the explicit creator.
 */
data class Attendee
	@JsonCreator
	constructor(
		@param:JsonProperty("email") val email: String,
		@param:JsonProperty("name") val name: String? = null,
		@param:JsonProperty("status") val status: AttendeeStatus = AttendeeStatus.NEEDS_ACTION,
	)
