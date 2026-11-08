package ch.sonensei.cockpit.gtd.task

import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.models.ConvertToEventRequest
import ch.sonensei.cockpit.core.network.generated.models.EventResponse
import ch.sonensei.cockpit.core.network.generated.models.NoteResponse
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.body
import retrofit2.Response
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A calendar an event can be created in (writable, not computed by the server). */
data class TargetCalendar(
    val id: UUID,
    val name: String,
    val isDefault: Boolean = false,
)

/** Where and when "To calendar" puts a task (SPEC.md §5.3): a calendar and the start, timed with a duration or all-day. */
data class EventPlan(
    val calendarId: UUID?,
    val date: LocalDate,
    val time: LocalTime,
    val durationMin: Int = DEFAULT_DURATION_MIN,
    val allDay: Boolean = false,
) {
    val isValid: Boolean get() = calendarId != null && (allDay || durationMin > 0)

    /** The request for `POST /tasks/{id}/convert-to-event`; timed events start at [date] [time] in [zone]. */
    fun request(zone: ZoneId): ConvertToEventRequest {
        if (allDay) {
            // The end date is exclusive: an all-day event of one day ends the day after.
            return ConvertToEventRequest(allDay = true, calendarId = calendarId, startDate = date, endDate = date.plusDays(1))
        }
        val start = date.atTime(time).atZone(zone)
        return ConvertToEventRequest(
            allDay = false,
            calendarId = calendarId,
            start = start.toOffsetDateTime(),
            end = start.plusMinutes(durationMin.toLong()).toOffsetDateTime(),
            timeZone = zone.id,
        )
    }

    /** The plan with a calendar of [calendars]: the chosen one if it is still offered, else the default one. */
    fun withCalendarOf(calendars: List<TargetCalendar>): EventPlan =
        if (calendars.any { it.id == calendarId }) this else copy(calendarId = defaultCalendar(calendars))

    companion object {
        const val DEFAULT_DURATION_MIN = 60

        /** The durations offered, in minutes. */
        val DURATIONS_MIN = listOf(15, 30, 60, 90, 120, 180)

        /** Starts at the next full hour after [now], for one hour (as on the web), in the default calendar. */
        fun startingAfter(
            now: ZonedDateTime,
            calendars: List<TargetCalendar> = emptyList(),
        ): EventPlan {
            val start = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            return EventPlan(defaultCalendar(calendars), start.toLocalDate(), start.toLocalTime())
        }

        /** The default calendar, or the first one when none of [calendars] is the default. */
        fun defaultCalendar(calendars: List<TargetCalendar>): UUID? =
            (calendars.firstOrNull { it.isDefault } ?: calendars.firstOrNull())?.id
    }
}

/** What a task was converted into. */
enum class ConvertedType { NOTE, EVENT }

/** Where a converted task went: the new note or event, which the user can open. */
data class ConvertedItem(
    val type: ConvertedType,
    val id: UUID,
    val title: String,
    /** The day the event starts on (on its own clock); `null` for a note. */
    val day: LocalDate? = null,
)

/**
 * Converts tasks into notes and events on the server (`POST /tasks/{id}/convert-to-note|event`): the server creates
 * the item, moves the task's links to it and trashes the task in one transaction. The calls throw like the other GTD
 * requests ([body]), so callers run them inside their connectivity handling.
 */
@Singleton
class TaskConverter
    @Inject
    constructor(
        private val tasksApi: TasksApi,
        private val calendarsApi: CalendarsApi,
    ) {
        suspend fun toNote(task: GtdTask): ConvertedItem = body(noteResponse(task)).toConverted()

        suspend fun toEvent(
            task: GtdTask,
            request: ConvertToEventRequest,
        ): ConvertedItem = body(eventResponse(task, request)).toConverted()

        /** The server's answer to converting [task] into a note, for callers that map refusals themselves. */
        internal suspend fun noteResponse(task: GtdTask): Response<NoteResponse> = tasksApi.convertTaskToNote(task.id, etag(task.version))

        /** The server's answer to converting [task] into the event of [request], for callers that map refusals themselves. */
        internal suspend fun eventResponse(
            task: GtdTask,
            request: ConvertToEventRequest,
        ): Response<EventResponse> = tasksApi.convertTaskToEvent(task.id, request, etag(task.version))

        /** The calendars an event can be created in, the default one first, then by name. */
        suspend fun calendars(): List<TargetCalendar> =
            body(calendarsApi.listCalendars())
                .items
                .filter { !it.readOnly }
                .map { TargetCalendar(it.id, it.name, it.isDefault) }
                .sortedWith(compareBy<TargetCalendar> { !it.isDefault }.thenBy { it.name.lowercase() })
    }

internal fun NoteResponse.toConverted() = ConvertedItem(ConvertedType.NOTE, id, displayTitle)

internal fun EventResponse.toConverted() = ConvertedItem(ConvertedType.EVENT, id, title, startDay())

private fun EventResponse.startDay(): LocalDate? {
    if (allDay) return startDate
    val zone = timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    return start?.atZoneSameInstant(zone)?.toLocalDate()
}

/** The `If-Match` value of [version] (the server's strong ETag). */
private fun etag(version: Long) = "\"$version\""
