package ch.sonensei.cockpit.calendar.edit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** The repeat choices of the form; [CUSTOM] keeps a rule of the series the presets can't express. */
enum class Repeat { NONE, DAILY, WEEKLY, MONTHLY, YEARLY, CUSTOM }

/** The presets offered for every event; [Repeat.CUSTOM] only shows for a series that already has such a rule. */
val REPEAT_PRESETS = listOf(Repeat.NONE, Repeat.DAILY, Repeat.WEEKLY, Repeat.MONTHLY, Repeat.YEARLY)

/** Which occurrences of a series a change applies to (SPEC.md §6.2). */
enum class EditScope { THIS, FOLLOWING, ALL }

/** The reminder choices, in minutes before the start (as on the web). */
val REMINDER_PRESETS = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 10080)

/** The longest custom reminder: four weeks before the start. */
const val MAX_REMINDER_MINUTES = 40320

/** Whether a guest takes part (RFC 5545 PARTSTAT). */
enum class AttendeeStatus { NEEDS_ACTION, ACCEPTED, DECLINED, TENTATIVE, DELEGATED }

/** A guest of an event. */
data class Attendee(
    val email: String,
    val name: String? = null,
    val status: AttendeeStatus = AttendeeStatus.NEEDS_ACTION,
)

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/** Whether [text] looks like an email address (the server and the mail system check it fully). */
fun isEmailAddress(text: String): Boolean = EMAIL.matches(text.trim())

/** Why the form can't be saved yet. */
enum class FormError { TITLE_MISSING, CALENDAR_MISSING, END_BEFORE_START }

/**
 * What the event form edits. Dates and times are wall-clock values in [zone]; for all-day events [endDate] is the
 * last day (inclusive, as people read it), not the exclusive date of the API.
 */
data class EventForm(
    val title: String = "",
    val calendarId: UUID? = null,
    val allDay: Boolean = false,
    val startDate: LocalDate,
    val startTime: LocalTime,
    val endDate: LocalDate,
    val endTime: LocalTime,
    val zone: ZoneId,
    val repeat: Repeat = Repeat.NONE,
    /**
     * The rule the event was loaded with (without the `RRULE:` prefix), kept when another repeat is picked: a
     * [Repeat.CUSTOM] repeat stands for it.
     */
    val rule: String = "",
    val location: String = "",
    val description: String = "",
    /** Minutes before the start, ascending and without repeats. */
    val reminders: List<Int> = emptyList(),
    val attendees: List<Attendee> = emptyList(),
) {
    /** The problems that keep it from being saved; empty when it can be saved. */
    val errors: Set<FormError>
        get() =
            buildSet {
                if (title.isBlank()) add(FormError.TITLE_MISSING)
                if (calendarId == null) add(FormError.CALENDAR_MISSING)
                if (!endsAfterStart) add(FormError.END_BEFORE_START)
            }

    /** Timed: the end is after the start. All-day: the last day is not before the first. */
    val endsAfterStart: Boolean
        get() = if (allDay) endDate >= startDate else endDate.atTime(endTime) > startDate.atTime(startTime)

    /** The RRULE the repeat choice stands for; `null` for a single event. */
    val rrule: String?
        get() =
            when (repeat) {
                Repeat.NONE -> null
                Repeat.CUSTOM -> normalizeRule(rule).ifEmpty { null }
                // Without BYDAY/BYMONTHDAY the series repeats on the weekday or day of its start (RFC 5545).
                else -> "FREQ=${repeat.name}"
            }

    /** Adds a reminder [minutes] before the start (once). */
    fun withReminder(minutes: Int): EventForm = copy(reminders = (reminders + minutes).distinct().sorted())

    fun withoutReminder(minutes: Int): EventForm = copy(reminders = reminders - minutes)

    /** Adds the guest [email] (trimmed) unless the address is already invited (case-insensitive). */
    fun withAttendee(
        email: String,
        name: String? = null,
    ): EventForm {
        val address = email.trim()
        if (address.isEmpty() || attendees.any { it.email.equals(address, ignoreCase = true) }) return this
        return copy(attendees = attendees + Attendee(address, name?.trim()?.ifEmpty { null }))
    }

    fun withoutAttendee(email: String): EventForm = copy(attendees = attendees.filterNot { it.email.equals(email, ignoreCase = true) })

    /** Moves the start to [date] and keeps the length, as calendar apps do. */
    fun withStartDate(date: LocalDate): EventForm {
        val days = date.toEpochDay() - startDate.toEpochDay()
        return copy(startDate = date, endDate = endDate.plusDays(days))
    }

    /** Moves the start to [time] and keeps the length of a timed event. */
    fun withStartTime(time: LocalTime): EventForm {
        val length = Duration.between(startDate.atTime(startTime), endDate.atTime(endTime))
        val newEnd = startDate.atTime(time).plus(length)
        return copy(startTime = time, endDate = newEnd.toLocalDate(), endTime = newEnd.toLocalTime())
    }
}

/** The choice showing [rrule] (with or without the `RRULE:` prefix). */
fun repeatOf(rrule: String?): Repeat {
    val rule = normalizeRule(rrule)
    if (rule.isEmpty()) return Repeat.NONE
    return REPEAT_PRESETS.firstOrNull { it != Repeat.NONE && rule.equals("FREQ=${it.name}", ignoreCase = true) } ?: Repeat.CUSTOM
}

/** [rrule] without the prefix, `""` for none. */
fun normalizeRule(rrule: String?): String =
    rrule
        .orEmpty()
        .trim()
        .replaceFirst(Regex("^RRULE:", RegexOption.IGNORE_CASE), "")
        .trim()

/** The time fields of [form], as the API takes them. */
private fun timeFields(form: EventForm): Map<String, JsonElement> =
    if (form.allDay) {
        mapOf(
            "startDate" to JsonPrimitive(form.startDate.toString()),
            "endDate" to JsonPrimitive(form.endDate.plusDays(1).toString()),
        )
    } else {
        mapOf(
            "start" to JsonPrimitive(instant(form.startDate, form.startTime, form.zone)),
            "end" to JsonPrimitive(instant(form.endDate, form.endTime, form.zone)),
        )
    }

/**
 * The instant (`...Z`) at which clocks in [zone] show [date] [time]. A time skipped by a DST change moves forward by
 * the length of the gap, an ambiguous one takes the earlier offset (as `ZonedDateTime.of` does).
 */
internal fun instant(
    date: LocalDate,
    time: LocalTime,
    zone: ZoneId,
): String = ZonedDateTime.of(date, time, zone).toInstant().toString()

/** [attendees] as the API takes them. */
private fun attendeesJson(attendees: List<Attendee>): JsonArray =
    JsonArray(
        attendees.map { attendee ->
            JsonObject(
                buildMap {
                    put("email", JsonPrimitive(attendee.email))
                    attendee.name?.let { put("name", JsonPrimitive(it)) }
                    put("status", JsonPrimitive(attendee.status.name))
                },
            )
        },
    )

/**
 * The body creating the event of [form], with the client-generated [id] that makes a repeated create harmless. Guests
 * are only sent [withAttendees] (a local calendar: providers manage the guests of synced ones).
 */
fun createBody(
    form: EventForm,
    id: UUID,
    withAttendees: Boolean = false,
): JsonObject {
    val fields = mutableMapOf<String, JsonElement>()
    fields["id"] = JsonPrimitive(id.toString())
    fields["title"] = JsonPrimitive(form.title.trim())
    form.calendarId?.let { fields["calendarId"] = JsonPrimitive(it.toString()) }
    fields["allDay"] = JsonPrimitive(form.allDay)
    fields += timeFields(form)
    if (!form.allDay) fields["timeZone"] = JsonPrimitive(form.zone.id)
    form.rrule?.let { fields["rrule"] = JsonPrimitive(it) }
    form.location
        .trim()
        .takeIf { it.isNotEmpty() }
        ?.let { fields["location"] = JsonPrimitive(it) }
    if (form.description.isNotBlank()) fields["description"] = JsonPrimitive(form.description)
    fields["reminderMinutes"] = JsonArray(form.reminders.map(::JsonPrimitive))
    if (withAttendees && form.attendees.isNotEmpty()) fields["attendees"] = attendeesJson(form.attendees)
    return JsonObject(fields)
}

/**
 * The fields that differ between [initial] and [current]: only those are sent, so a change of one occurrence or of
 * the series touches nothing else. Time fields are sent together when any of them changed; `null` clears a field.
 * Reminders and guests replace the old ones when they changed; guests only [withAttendees] (a local calendar).
 */
fun patchBody(
    initial: EventForm,
    current: EventForm,
    withAttendees: Boolean = false,
): JsonObject {
    val fields = mutableMapOf<String, JsonElement>()
    if (current.title.trim() != initial.title.trim()) fields["title"] = JsonPrimitive(current.title.trim())
    if (current.calendarId !=
        initial.calendarId
    ) {
        fields["calendarId"] = current.calendarId?.let { JsonPrimitive(it.toString()) } ?: JsonNull
    }
    if (current.location.trim() != initial.location.trim()) fields["location"] = textOrNull(current.location.trim())
    if (current.description != initial.description) {
        fields["description"] = if (current.description.isBlank()) JsonNull else JsonPrimitive(current.description)
    }
    if (current.rrule != initial.rrule) fields["rrule"] = current.rrule?.let(::JsonPrimitive) ?: JsonNull
    if (current.reminders != initial.reminders) fields["reminderMinutes"] = JsonArray(current.reminders.map(::JsonPrimitive))
    if (withAttendees && current.attendees != initial.attendees) fields["attendees"] = attendeesJson(current.attendees)
    val timesChanged =
        current.allDay != initial.allDay ||
            current.startDate != initial.startDate ||
            current.endDate != initial.endDate ||
            (
                !current.allDay &&
                    (current.startTime != initial.startTime || current.endTime != initial.endTime || current.zone != initial.zone)
            )
    if (timesChanged) {
        if (current.allDay != initial.allDay) fields["allDay"] = JsonPrimitive(current.allDay)
        fields += timeFields(current)
        if (!current.allDay && (current.zone != initial.zone || initial.allDay)) fields["timeZone"] = JsonPrimitive(current.zone.id)
    }
    return JsonObject(fields)
}

private fun textOrNull(text: String): JsonElement = if (text.isEmpty()) JsonNull else JsonPrimitive(text)

/** The scopes a change can apply to: a new repeat rule or calendar is for the series, never one occurrence alone. */
fun scopesFor(patch: JsonObject): List<EditScope> =
    if ("rrule" in patch || "calendarId" in patch) {
        listOf(EditScope.FOLLOWING, EditScope.ALL)
    } else {
        EditScope.entries.toList()
    }
