package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.AttendeeStatus
import ch.sonensei.cockpit.calendar.model.Calendar
import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventSyncState
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.recurrence.Occurrence
import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import jakarta.validation.constraints.NotNull
import org.springframework.http.MediaType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE

/** The `occurrence` parameter and response value: the original start, an instant (timed) or a date (all-day series). */
internal fun OccurrenceId.format(): String = at?.toString() ?: date.toString()

@Schema(description = "A calendar. `version` is also sent as the ETag.")
data class CalendarResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val sourceId: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val sourceType: CalendarSourceType,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val name: String,
	@get:Schema(description = "CSS hex color", example = "#3366ff") val color: String?,
	@get:Schema(description = "Shown in the calendar views", requiredMode = RequiredMode.REQUIRED) val visible: Boolean,
	@get:Schema(description = "Its events cannot be changed", requiredMode = RequiredMode.REQUIRED) val readOnly: Boolean,
	@get:Schema(description = "The calendar for new events (at most one)", requiredMode = RequiredMode.REQUIRED)
	@get:JsonProperty("isDefault")
	val isDefault: Boolean,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(
			calendar: Calendar,
			sourceType: CalendarSourceType,
		) = CalendarResponse(
			id = calendar.id,
			sourceId = calendar.sourceId,
			sourceType = sourceType,
			name = calendar.name,
			color = calendar.color,
			visible = calendar.visible,
			readOnly = calendar.readOnly,
			isDefault = calendar.isDefault,
			createdAt = checkNotNull(calendar.createdAt),
			updatedAt = checkNotNull(calendar.updatedAt),
			version = calendar.version,
		)
	}
}

@Schema(description = "Every calendar, ordered by name")
data class CalendarListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<CalendarResponse>,
)

@Schema(description = "A new local calendar (stored in the cockpit)")
data class CreateCalendarRequest(
	@field:NotNull
	@get:Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 200)
	val name: String? = null,
	@get:Schema(description = "CSS hex color `#rrggbb`", example = "#3366ff")
	val color: String? = null,
	@get:Schema(description = "Default true")
	val visible: Boolean? = null,
	@get:Schema(description = "Make it the calendar for new events. The first calendar becomes the default anyway.")
	@param:JsonProperty("isDefault")
	@get:JsonProperty("isDefault")
	val isDefault: Boolean? = null,
)

/** The fields PATCH accepts (documentation and type conversion; which fields were sent is read from the body). */
@Schema(
	description =
		"Changes to a calendar: only the fields sent change. `isDefault: true` makes it the calendar for new events " +
			"(the previous default loses the flag). `color` can be cleared with `null`.",
)
data class CalendarPatchRequest(
	@get:Schema(maxLength = 200) val name: String? = null,
	@get:Schema(types = ["string", "null"], example = "#3366ff") val color: String? = null,
	val visible: Boolean? = null,
	@param:JsonProperty("isDefault")
	@get:JsonProperty("isDefault")
	val isDefault: Boolean? = null,
)

@Schema(description = "An invited person")
data class AttendeeDto(
	@get:Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 320) val email: String,
	@get:Schema(maxLength = 200) val name: String? = null,
	@get:Schema(description = "Default NEEDS_ACTION") val status: AttendeeStatus? = null,
) {
	fun toModel() = Attendee(email.trim(), name, status ?: AttendeeStatus.NEEDS_ACTION)

	companion object {
		fun of(attendee: Attendee) = AttendeeDto(attendee.email, attendee.name, attendee.status)
	}
}

@Schema(
	description =
		"An event, a series master (`rrule` set) or the override of one occurrence of a series (`seriesId` and " +
			"`recurrenceId` set). Timed events have `start`/`end` and `timeZone`, all-day events `startDate` and the " +
			"exclusive `endDate`. `version` is also sent as the ETag.",
)
data class EventResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val calendarId: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val title: String,
	val description: String?,
	val location: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val allDay: Boolean,
	val start: Instant?,
	val end: Instant?,
	@get:Schema(description = "IANA zone id the timed event was planned in", example = "Europe/Zurich") val timeZone: String?,
	val startDate: LocalDate?,
	@get:Schema(description = "Exclusive: a one-day event on the 3rd ends on the 4th") val endDate: LocalDate?,
	@get:Schema(description = "iCalendar RRULE without the `RRULE:` prefix (series masters)", example = "FREQ=WEEKLY;BYDAY=MO")
	val rrule: String?,
	@get:Schema(
		description = "Excluded occurrences (original starts: instants, or dates for all-day series)",
		requiredMode = RequiredMode.REQUIRED,
	)
	val exdates: List<String>,
	@get:Schema(description = "The series master of an override") val seriesId: UUID?,
	@get:Schema(description = "The original start of the occurrence an override replaces") val recurrenceId: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val attendees: List<AttendeeDto>,
	@get:Schema(description = "Minutes before the start of each reminder", requiredMode = RequiredMode.REQUIRED)
	val reminderMinutes: List<Int>,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val status: EventStatus,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val transparency: EventTransparency,
	val conferenceUrl: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val syncState: EventSyncState,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(event: CalendarEvent) =
			EventResponse(
				id = event.id,
				calendarId = event.calendarId,
				title = event.title,
				description = event.description,
				location = event.location,
				allDay = event.allDay,
				start = event.startAt,
				end = event.endAt,
				timeZone = event.timeZone,
				startDate = event.startDate,
				endDate = event.endDate,
				rrule = event.rrule,
				exdates = event.exdates.sortedWith(compareBy({ it.at }, { it.date })).map { it.format() },
				seriesId = event.parentEventId,
				recurrenceId = event.recurrenceId?.format(),
				attendees = event.attendees.map(AttendeeDto::of),
				reminderMinutes = event.reminderMinutes,
				status = event.status,
				transparency = event.transparency,
				conferenceUrl = event.conferenceUrl,
				syncState = event.syncState,
				createdAt = checkNotNull(event.createdAt),
				updatedAt = checkNotNull(event.updatedAt),
				version = event.version,
			)
	}
}

@Schema(
	description =
		"One occurrence in a range. `eventId` is the event that provides its data (the series master for a generated " +
			"occurrence, the override for a changed one, the event itself for a single event); `version` is that " +
			"event's. Occurrences of a series have `seriesId` and `occurrence` (the original start), which identify " +
			"them for `?scope=&occurrence=` edits.",
)
data class OccurrenceResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val eventId: UUID,
	val seriesId: UUID?,
	@get:Schema(description = "Original start of the occurrence: an instant, or a date for all-day series") val occurrence: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val calendarId: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val title: String,
	val location: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val allDay: Boolean,
	val start: Instant?,
	val end: Instant?,
	val timeZone: String?,
	val startDate: LocalDate?,
	@get:Schema(description = "Exclusive") val endDate: LocalDate?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val status: EventStatus,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val transparency: EventTransparency,
	val conferenceUrl: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(occurrence: Occurrence) =
			with(occurrence) {
				OccurrenceResponse(
					eventId = event.id,
					seriesId = seriesId,
					occurrence = occurrenceId?.format(),
					calendarId = event.calendarId,
					title = event.title,
					location = event.location,
					allDay = allDay,
					start = start,
					end = end,
					timeZone = event.timeZone,
					startDate = startDate,
					endDate = endDate,
					status = event.status,
					transparency = event.transparency,
					conferenceUrl = event.conferenceUrl,
					version = event.version,
				)
			}
	}
}

@Schema(description = "The occurrences overlapping the range, ordered by start")
data class OccurrenceListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<OccurrenceResponse>,
)

@Schema(
	description =
		"A new event. Timed: `start`, `end` (default `start` + 1 hour) and `timeZone` (default the user's); all-day " +
			"(`allDay: true`): `startDate` and the exclusive `endDate` (default the day after). `calendarId` defaults to " +
			"the default calendar.",
)
data class CreateEventRequest(
	@get:Schema(description = "Client-generated UUIDv7 for an idempotent create")
	val id: UUID? = null,
	val calendarId: UUID? = null,
	@field:NotNull
	@get:Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 500)
	val title: String? = null,
	val description: String? = null,
	val location: String? = null,
	@get:Schema(description = "Default false")
	val allDay: Boolean? = null,
	val start: Instant? = null,
	val end: Instant? = null,
	@get:Schema(example = "Europe/Zurich") val timeZone: String? = null,
	val startDate: LocalDate? = null,
	val endDate: LocalDate? = null,
	@get:Schema(description = "iCalendar RRULE (the `RRULE:` prefix is optional)", example = "FREQ=WEEKLY;BYDAY=MO")
	val rrule: String? = null,
	val attendees: List<AttendeeDto>? = null,
	val reminderMinutes: List<Int>? = null,
	@get:Schema(description = "Default CONFIRMED") val status: EventStatus? = null,
	@get:Schema(description = "Default OPAQUE") val transparency: EventTransparency? = null,
	val conferenceUrl: String? = null,
) {
	fun toChanges() =
		EventChanges(
			calendarId = calendarId?.let(::FieldChange),
			title = title?.let(::FieldChange),
			description = description?.let(::FieldChange),
			location = location?.let(::FieldChange),
			timing = TimingInput(allDay, start, end, timeZone, startDate, endDate).takeIf { it.isSent },
			rrule = rrule?.let(::FieldChange),
			attendees = attendees?.let { list -> FieldChange(list.map { it.toModel() }) },
			reminderMinutes = reminderMinutes?.let(::FieldChange),
			status = status?.let(::FieldChange),
			transparency = transparency?.let(::FieldChange),
			conferenceUrl = conferenceUrl?.let(::FieldChange),
		)
}

/** The fields PATCH accepts (documentation and type conversion; which fields were sent is read from the body). */
@Schema(
	description =
		"Changes to an event: only the fields sent change, `null` clears a field (`title` cannot be cleared). Time " +
			"fields not sent keep their value, the duration is kept when only `start` (or `startDate`) changes. With " +
			"`scope=THIS|FOLLOWING`, or `scope=ALL` with an `occurrence`, the time fields are the new times of that " +
			"occurrence. `rrule` and `calendarId` cannot change for a single occurrence (`scope=THIS`).",
)
data class EventPatchRequest(
	val calendarId: UUID? = null,
	@get:Schema(maxLength = 500) val title: String? = null,
	@get:Schema(types = ["string", "null"]) val description: String? = null,
	@get:Schema(types = ["string", "null"]) val location: String? = null,
	val allDay: Boolean? = null,
	val start: Instant? = null,
	val end: Instant? = null,
	val timeZone: String? = null,
	val startDate: LocalDate? = null,
	val endDate: LocalDate? = null,
	@get:Schema(types = ["string", "null"], description = "`null` makes a series a single event") val rrule: String? = null,
	@get:Schema(description = "Replaces the attendees") val attendees: List<AttendeeDto>? = null,
	@get:Schema(description = "Replaces the reminders") val reminderMinutes: List<Int>? = null,
	@get:Schema(description = "CANCELLED on an occurrence removes it") val status: EventStatus? = null,
	val transparency: EventTransparency? = null,
	@get:Schema(types = ["string", "null"]) val conferenceUrl: String? = null,
)

/** Which occurrences of a series a change or delete applies to (SPEC.md §6.2). */
enum class EditScope {
	/** Only the given occurrence. */
	THIS,

	/** The given occurrence and every later one (splits the series). */
	FOLLOWING,

	/** The whole series. */
	ALL,
}
