package ch.sonensei.cockpit.calendar.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.capture.queue.UuidV7
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject

/** Why the editor can't show or save the event. */
enum class EditorMessage {
    /** The event could not be loaded: no connection / the server refused. */
    LOAD_OFFLINE,
    LOAD_FAILED,

    /** Saving failed: no connection (nothing changed) / the server refused the change. */
    OFFLINE,
    FAILED,

    /** The event changed elsewhere (or at its provider) since it was opened: reload before changing it. */
    CONFLICT,
}

data class EventEditorUiState(
    /** The editor is shown (instead of the calendar views). */
    val open: Boolean = false,
    /** A new event (else an existing one is edited). */
    val creating: Boolean = false,
    val loading: Boolean = false,
    /** `null` while the event is loading (or its load failed). */
    val form: EventForm? = null,
    val calendars: List<CalendarInfo> = emptyList(),
    /** The edited occurrence belongs to a repeating series: a change asks which occurrences it is for. */
    val series: Boolean = false,
    /** A save was tried: the form's problems are shown. */
    val showErrors: Boolean = false,
    val saving: Boolean = false,
    /** The scope prompt is shown, offering these scopes. */
    val scopes: List<EditScope>? = null,
    val message: EditorMessage? = null,
) {
    /** The problems shown next to the fields. */
    val errors: Set<FormError> get() = if (showErrors) form?.errors.orEmpty() else emptySet()

    /** An existing event of a read-only calendar (e.g. an ICS feed): shown, not changed. */
    val readOnly: Boolean get() = !creating && calendars.firstOrNull { it.id == form?.calendarId }?.readOnly == true

    /**
     * The calendars the event can be saved to: any writable one for a new event. An existing event stays in its
     * calendar (a synced event can't move to another one, SPEC.md §6.2).
     */
    val targetCalendars: List<CalendarInfo>
        get() = if (creating) calendars.filterNot { it.readOnly } else calendars.filter { it.id == form?.calendarId }

    /** The repeat choices: the presets, and the custom rule a series already has. */
    val repeatChoices: List<Repeat> get() = if (form?.repeat == Repeat.CUSTOM) REPEAT_PRESETS + Repeat.CUSTOM else REPEAT_PRESETS

    val canSave: Boolean get() = form != null && !loading && !saving && !readOnly && message != EditorMessage.CONFLICT
}

/**
 * Creates a new event or edits an occurrence (SPEC.md §6.3). Only changed fields are sent. A change of a repeating
 * event asks THIS / FOLLOWING / ALL first (a new repeat rule only FOLLOWING / ALL). When the event changed elsewhere
 * since it was opened, the editor says so and offers to reload it (SPEC.md §6.2).
 *
 * One editor serves the Calendar screen; it survives configuration changes, so the open form does too.
 */
@HiltViewModel
class EventEditorViewModel internal constructor(
    private val repository: EventEditRepository,
    private val clock: Clock,
) : ViewModel() {
    @Inject
    constructor(repository: EventEditRepository) : this(repository, Clock.systemDefaultZone())

    private val state = MutableStateFlow(EventEditorUiState())
    val uiState: StateFlow<EventEditorUiState> = state.asStateFlow()

    /** The edited occurrence; `null` for a new event. */
    private var occurrence: Occurrence? = null

    /** The form as loaded: what a change is compared with. */
    private var initial: EventForm? = null

    /** The version of the event holding the occurrence, sent as `If-Match`. */
    private var version: Long = 0

    /** The id of the new event, kept across retries so a create that reached the server is not repeated. */
    private var newId: UUID? = null

    private var job: Job? = null

    /** Opens the form of a new event on [date] in one of [calendars]. */
    fun create(
        date: LocalDate,
        calendars: List<CalendarInfo>,
    ) {
        job?.cancel()
        occurrence = null
        newId = UuidV7.generate(clock.millis())
        val calendarId = calendars.firstOrNull { !it.readOnly && it.visible }?.id ?: calendars.firstOrNull { !it.readOnly }?.id
        val form = newForm(date, ZonedDateTime.now(clock), calendarId)
        initial = form
        state.value = EventEditorUiState(open = true, creating = true, form = form, calendars = calendars)
    }

    /** Opens the form of [occurrence] and loads its event. */
    fun edit(
        occurrence: Occurrence,
        calendars: List<CalendarInfo>,
    ) {
        this.occurrence = occurrence
        newId = null
        state.value = EventEditorUiState(open = true, calendars = calendars, series = occurrence.inSeries)
        load()
    }

    /** Loads the edited event again, e.g. after a conflict; unsaved changes are dropped. */
    fun reload() = load()

    fun update(change: (EventForm) -> EventForm) {
        if (!state.value.canSave) return
        state.update { s -> s.copy(form = s.form?.let(change)) }
    }

    /** Saves the form: creates the event, or changes it (asking for the scope first when it is a series). */
    fun save() {
        val current = state.value
        val form = current.form ?: return
        if (!current.canSave || current.scopes != null) return
        if (form.errors.isNotEmpty()) {
            state.update { it.copy(showErrors = true) }
            return
        }
        val edited = occurrence
        if (edited == null) {
            send { repository.create(createBody(form, newId ?: UuidV7.generate(clock.millis()).also { newId = it })) }
            return
        }
        val patch = patchBody(initial ?: form, form)
        if (patch.isEmpty()) {
            close()
        } else if (edited.inSeries) {
            state.update { it.copy(scopes = scopesFor(patch)) }
        } else {
            sendPatch(edited, null, patch)
        }
    }

    /** The scope prompt was answered with [scope]. */
    fun chooseScope(scope: EditScope) {
        val current = state.value
        val offered = current.scopes ?: return
        val edited = occurrence ?: return
        val form = current.form ?: return
        state.update { it.copy(scopes = null) }
        if (scope in offered) sendPatch(edited, scope, patchBody(initial ?: form, form))
    }

    /** The scope prompt was cancelled: nothing is saved, the form stays as it is. */
    fun dismissScope() = state.update { it.copy(scopes = null) }

    /** Closes the editor without saving. */
    fun close() {
        job?.cancel()
        occurrence = null
        initial = null
        newId = null
        state.value = EventEditorUiState()
    }

    private fun sendPatch(
        edited: Occurrence,
        scope: EditScope?,
        patch: JsonObject,
    ) = send { repository.update(edited, version, scope, patch) }

    private fun send(request: suspend () -> CalendarResult<EventDetails>) {
        state.update { it.copy(saving = true, message = null) }
        job =
            viewModelScope.launch {
                when (val result = request()) {
                    is CalendarResult.Ok -> close()
                    CalendarResult.Offline -> state.update { it.copy(saving = false, message = EditorMessage.OFFLINE) }
                    is CalendarResult.Failed ->
                        state.update {
                            it.copy(
                                saving = false,
                                message = if (result.status in CONFLICTS) EditorMessage.CONFLICT else EditorMessage.FAILED,
                            )
                        }
                }
            }
    }

    private fun load() {
        val edited = occurrence ?: return
        job?.cancel()
        state.update { it.copy(loading = true, form = null, message = null, showErrors = false, scopes = null) }
        job =
            viewModelScope.launch {
                val event = repository.event(edited.eventId)
                val master =
                    when {
                        event !is CalendarResult.Ok -> null
                        edited.seriesId != null && edited.seriesId != event.value.id -> repository.event(edited.seriesId)
                        else -> null
                    }
                val failure = listOf(event, master).firstOrNull { it != null && it !is CalendarResult.Ok }
                if (event !is CalendarResult.Ok || failure != null) {
                    state.update {
                        it.copy(
                            loading = false,
                            message = if (failure == CalendarResult.Offline) EditorMessage.LOAD_OFFLINE else EditorMessage.LOAD_FAILED,
                        )
                    }
                    return@launch
                }
                val form = editForm(edited, event.value, (master as? CalendarResult.Ok)?.value, clock.zone)
                version = event.value.version
                initial = form
                state.update { it.copy(loading = false, form = form) }
            }
    }

    private companion object {
        /** Changed concurrently or at the provider (409), or a stale `If-Match` (412). */
        val CONFLICTS = setOf(409, 412)
    }
}

/** How long a new event lasts at first. */
private const val NEW_EVENT_HOURS = 1L

/** Where a new event on another day than today starts. */
private val DEFAULT_START: LocalTime = LocalTime.of(9, 0)

/** The form of a new event on [date]: today from the next full hour, other days from 9:00, one hour long. */
internal fun newForm(
    date: LocalDate,
    now: ZonedDateTime,
    calendarId: UUID?,
): EventForm {
    val start =
        if (date == now.toLocalDate()) {
            now.toLocalDateTime().truncatedTo(ChronoUnit.HOURS).plusHours(1)
        } else {
            date.atTime(DEFAULT_START)
        }
    val end = start.plusHours(NEW_EVENT_HOURS)
    return EventForm(
        calendarId = calendarId,
        startDate = start.toLocalDate(),
        startTime = start.toLocalTime(),
        endDate = end.toLocalDate(),
        endTime = end.toLocalTime(),
        zone = now.zone,
    )
}

/**
 * The form of [occurrence]: the data of [event] (the event holding it), the repeat rule of [master] (its series, when
 * that is another event) and the times of the occurrence (a generated one has no times of its own).
 */
internal fun editForm(
    occurrence: Occurrence,
    event: EventDetails,
    master: EventDetails?,
    defaultZone: ZoneId,
): EventForm {
    val zone = (event.timeZone ?: master?.timeZone)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: defaultZone
    val fromOccurrence = occurrence.generated
    val allDay = if (fromOccurrence) occurrence.allDay else event.allDay
    val rule = normalizeRule(master?.rrule ?: event.rrule)
    val base =
        EventForm(
            title = event.title,
            calendarId = event.calendarId,
            allDay = allDay,
            startDate = LocalDate.MIN,
            startTime = DEFAULT_START,
            endDate = LocalDate.MIN,
            endTime = DEFAULT_START.plusHours(NEW_EVENT_HOURS),
            zone = zone,
            repeat = repeatOf(rule),
            rule = rule,
            location = event.location.orEmpty(),
            description = event.description.orEmpty(),
        )
    if (allDay) {
        val first = (if (fromOccurrence) occurrence.startDate else event.startDate) ?: occurrence.startDate ?: LocalDate.now(zone)
        val after = (if (fromOccurrence) occurrence.endDate else event.endDate) ?: first.plusDays(1)
        return base.copy(startDate = first, endDate = maxOf(after.minusDays(1), first))
    }
    val start = ((if (fromOccurrence) occurrence.start else event.start) ?: occurrence.start)?.atZone(zone) ?: ZonedDateTime.now(zone)
    val end = ((if (fromOccurrence) occurrence.end else event.end) ?: occurrence.end)?.atZone(zone) ?: start
    return base.copy(
        startDate = start.toLocalDate(),
        startTime = start.toLocalTime().truncatedTo(ChronoUnit.MINUTES),
        endDate = end.toLocalDate(),
        endTime = end.toLocalTime().truncatedTo(ChronoUnit.MINUTES),
    )
}
