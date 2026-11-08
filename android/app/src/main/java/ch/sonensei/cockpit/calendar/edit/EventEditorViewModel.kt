package ch.sonensei.cockpit.calendar.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.capture.queue.UuidV7
import ch.sonensei.cockpit.core.settings.UserSettings
import ch.sonensei.cockpit.core.settings.UserSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /**
     * Saving failed: no answer from the server (no connection, a server error, or the answer was lost: the change may
     * have arrived; a retry is harmless) / the server refused the change.
     */
    OFFLINE,
    FAILED,

    /** A delete was refused by the server (for another reason than a conflict or a missing event). */
    DELETE_FAILED,

    /** The event changed elsewhere (or at its provider) since it was opened: reload before changing it. */
    CONFLICT,

    /**
     * An answer to an invitation was refused by the server (not invited, organizer, changed at the provider,
     * not synced yet, or read-only); a reload and another try may help.
     */
    RESPOND_FAILED,

    /** The edited occurrence no longer exists (its series changed or it was removed elsewhere): no reload can show it. */
    GONE,
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
    /** The scope prompt (when shown) is for a delete, not a change. */
    val deleting: Boolean = false,
    /** The "Delete this event?" confirmation of a single event is shown. */
    val confirmingDelete: Boolean = false,
    val message: EditorMessage? = null,
    /** The calendars whose guests are edited here (the local ones); `null` while not known. */
    val localCalendars: Set<UUID>? = null,
    /** The guests of the event as saved: invitations go out for the saved version. */
    val savedAttendees: List<String> = emptyList(),
    /** What is typed into the "add guest" field. */
    val guestQuery: String = "",
    /** Contacts matching [guestQuery], not yet invited. */
    val suggestions: List<Attendee> = emptyList(),
    /** The typed guest is not an email address. */
    val guestInvalid: Boolean = false,
    /** The invitation prompt, when shown. */
    val invitation: InvitationUiState? = null,
    /** The last invitation mail sent from the editor. */
    val invitationSent: InvitationSent? = null,
    /** "Send invitations" was asked with unsaved changes. */
    val saveBeforeInviting: Boolean = false,
    /** The linked items of an existing event; `null` for a new one. */
    val links: LinksUiState? = null,
    /** The calendars whose invitations are answered at their provider (Google, Microsoft); `null` while not known. */
    val invitationCalendars: Set<UUID>? = null,
    /** The user's own addresses (of the mail accounts), lower case: their attendee carries the user's answer. */
    val ownAddresses: Set<String> = emptySet(),
    /** The answer to the invitation being sent, if one is. */
    val responding: AttendeeStatus? = null,
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

    /**
     * The repeat choices: the presets, and the custom rule the series was loaded with (still offered after a preset was
     * picked, so it can be chosen again).
     */
    val repeatChoices: List<Repeat>
        get() = if (form != null && repeatOf(form.rule) == Repeat.CUSTOM) REPEAT_PRESETS + Repeat.CUSTOM else REPEAT_PRESETS

    val canSave: Boolean
        get() =
            form != null &&
                !loading &&
                !saving &&
                responding == null &&
                !readOnly &&
                invitation == null &&
                message != EditorMessage.CONFLICT &&
                message != EditorMessage.GONE

    /** Guests are changed in local calendars only: providers own the guests of synced ones. */
    val attendeesEditable: Boolean
        get() = canSave && form?.calendarId?.let { localCalendars?.contains(it) } == true

    /** Invitations can be sent for a saved event of a local calendar that has guests (SPEC.md §6.2). */
    val invitable: Boolean get() = !creating && attendeesEditable && savedAttendees.isNotEmpty()

    /**
     * An existing event of a writable calendar can be deleted, as it can be saved: not while a save or delete runs, nor
     * after a conflict (reload first, its version is stale) or once it is gone.
     */
    val canDelete: Boolean get() = !creating && canSave

    /**
     * The invitation to an existing event with guests in a writable Google or Microsoft calendar can be answered
     * (SPEC.md §6.2); the server refuses when the user is not invited or organizes it.
     */
    val respondable: Boolean
        get() =
            !creating &&
                form != null &&
                form.attendees.isNotEmpty() &&
                invitationCalendars?.contains(form.calendarId) == true

    /** An answer can be sent now: not while a load, save or another answer runs, nor after a conflict. */
    val canRespond: Boolean
        get() =
            respondable &&
                !loading &&
                !saving &&
                responding == null &&
                invitation == null &&
                message != EditorMessage.CONFLICT &&
                message != EditorMessage.GONE

    /** The user's current answer: the status of the guest with one of [ownAddresses]; `null` when none matches. */
    val ownStatus: AttendeeStatus?
        get() = form?.attendees?.firstOrNull { it.email.lowercase() in ownAddresses }?.status
}

/** The answers an invitation can get. */
val INVITATION_RESPONSES = listOf(AttendeeStatus.ACCEPTED, AttendeeStatus.TENTATIVE, AttendeeStatus.DECLINED)

/**
 * Creates a new event or edits an occurrence (SPEC.md §6.3). Only changed fields are sent. A change of a repeating
 * event asks THIS / FOLLOWING / ALL first (a new repeat rule only FOLLOWING / ALL). When the event changed elsewhere
 * since it was opened, the editor says so and offers to reload it (SPEC.md §6.2). An existing event can be deleted:
 * a single one after a confirmation, an occurrence of a series with the same scope choice as a change.
 *
 * One editor serves the Calendar screen; it survives configuration changes, so the open form does too.
 */
@HiltViewModel
class EventEditorViewModel internal constructor(
    private val repository: EventEditRepository,
    private val clock: Clock,
    private val settings: StateFlow<UserSettings?> = MutableStateFlow(null),
    /** Guests' calendars, suggestions, invitations and links; `null` leaves them out (no guests are edited then). */
    private val extras: EventExtrasRepository? = null,
) : ViewModel() {
    @Inject
    constructor(repository: EventEditRepository, settings: UserSettingsRepository, extras: EventExtrasRepository) :
        this(repository, Clock.systemDefaultZone(), settings.settings, extras)

    /** The zone of new and edited events: the synced time zone, the device's while it is not known. */
    private val zone: ZoneId get() = settings.value?.timeZone ?: clock.zone

    private val state = MutableStateFlow(EventEditorUiState())
    val uiState: StateFlow<EventEditorUiState> = state.asStateFlow()

    /** The edited occurrence (as last read from the server); `null` for a new event. */
    private var occurrence: Occurrence? = null

    /** The form as loaded: what a change is compared with. */
    private var initial: EventForm? = null

    /** The version of the event holding the occurrence, sent as `If-Match`. */
    private var version: Long = 0

    /**
     * The id of the new event, kept across retries so a create that reached the server is not repeated. Only a create
     * refused with 409 gets a new one: the id is unused or belongs to an event in the Trash, which a retry can't revive.
     */
    private var newId: UUID? = null

    private var job: Job? = null

    /** Load the local calendars, the links, suggestions, or send invitations: beside [job]. */
    private var sourcesJob: Job? = null
    private var respondJob: Job? = null
    private var linksJob: Job? = null

    /** The pending links load: a newer load cancels it, so an older answer never overrides a newer one. */
    private var loadLinksJob: Job? = null
    private var suggestJob: Job? = null
    private var inviteJob: Job? = null

    /** Opens the form of a new event on [date] in one of [calendars]. */
    fun create(
        date: LocalDate,
        calendars: List<CalendarInfo>,
    ) {
        job?.cancel()
        occurrence = null
        newId = UuidV7.generate(clock.millis())
        val calendarId = calendars.firstOrNull { !it.readOnly && it.visible }?.id ?: calendars.firstOrNull { !it.readOnly }?.id
        val form = newForm(date, ZonedDateTime.now(clock.withZone(zone)), calendarId)
        initial = form
        state.value = EventEditorUiState(open = true, creating = true, form = form, calendars = calendars)
        loadSources()
    }

    /** Opens the form of [occurrence] and loads its event; a birthday has none, so it is not opened (see [Occurrence.contactId]). */
    fun edit(
        occurrence: Occurrence,
        calendars: List<CalendarInfo>,
    ) {
        if (occurrence.contactId(calendars) != null) return
        this.occurrence = occurrence
        newId = null
        state.value = EventEditorUiState(open = true, calendars = calendars, series = occurrence.inSeries)
        loadSources()
        load()
    }

    /**
     * Loads the edited event again, e.g. after a conflict; unsaved changes are dropped. The invitation calendars and
     * own addresses are fetched again too, so a retry after a failed first load shows the answer section.
     */
    fun reload() {
        if (occurrence == null) return
        loadSources()
        load()
    }

    fun update(change: (EventForm) -> EventForm) {
        if (!state.value.canSave) return
        state.update { s -> s.copy(form = s.form?.let(change)) }
    }

    /** Saves the form: creates the event, or changes it (asking for the scope first when it is a series). */
    fun save() {
        val current = state.value
        val form = current.form ?: return
        if (!current.canSave || current.scopes != null || current.confirmingDelete) return
        if (form.errors.isNotEmpty()) {
            state.update { it.copy(showErrors = true) }
            return
        }
        val edited = occurrence
        val withAttendees = current.attendeesEditable
        if (edited == null) {
            val id = newId ?: UuidV7.generate(clock.millis()).also { newId = it }
            send(onOk = { saved(it, form, withAttendees) }) { repository.create(createBody(form, id, withAttendees)) }
            return
        }
        val patch = patchBody(initial ?: form, form, withAttendees)
        if (patch.isEmpty()) {
            close()
        } else if (edited.inSeries) {
            state.update { it.copy(scopes = scopesFor(patch)) }
        } else {
            sendPatch(edited, null, patch, form, withAttendees)
        }
    }

    /** The scope prompt was answered with [scope]. */
    fun chooseScope(scope: EditScope) {
        val current = state.value
        val offered = current.scopes ?: return
        val edited = occurrence ?: return
        val form = current.form ?: return
        state.update { it.copy(scopes = null, deleting = false) }
        if (scope !in offered) return
        if (current.deleting) {
            sendDelete(edited, scope)
        } else {
            val withAttendees = current.attendeesEditable
            sendPatch(edited, scope, patchBody(initial ?: form, form, withAttendees), form, withAttendees)
        }
    }

    /** The scope prompt was cancelled: nothing is saved or deleted, the form stays as it is. */
    fun dismissScope() = state.update { it.copy(scopes = null, deleting = false) }

    /**
     * Deletes the edited event: a single event asks for a confirmation first, an occurrence of a series which
     * occurrences to delete (any scope: a delete has no new rule restricting it).
     */
    fun delete() {
        val current = state.value
        val edited = occurrence ?: return
        if (!current.canDelete || current.scopes != null || current.confirmingDelete) return
        if (edited.inSeries) {
            state.update { it.copy(scopes = EditScope.entries.toList(), deleting = true) }
        } else {
            state.update { it.copy(confirmingDelete = true) }
        }
    }

    /** The delete of a single event was confirmed. */
    fun confirmDelete() {
        if (!state.value.confirmingDelete) return
        state.update { it.copy(confirmingDelete = false) }
        val edited = occurrence ?: return
        if (state.value.canDelete) sendDelete(edited, null)
    }

    /** The delete confirmation was cancelled: nothing is deleted. */
    fun dismissDelete() = state.update { it.copy(confirmingDelete = false) }

    /**
     * Closes the editor without saving. Ignored while a save runs: the server may commit the change even if its answer
     * never arrives here, so the editor waits for it (and keeps the new event's id for a retry).
     */
    fun close() {
        if (state.value.saving) return
        reset()
    }

    private fun reset() {
        job?.cancel()
        listOf(sourcesJob, respondJob, linksJob, loadLinksJob, suggestJob, inviteJob).forEach { it?.cancel() }
        occurrence = null
        initial = null
        newId = null
        state.value = EventEditorUiState()
    }

    private fun sendPatch(
        edited: Occurrence,
        scope: EditScope?,
        patch: JsonObject,
        form: EventForm,
        withAttendees: Boolean,
    ) = send(onOk = { saved(it, form, withAttendees) }) { repository.update(edited, version, scope, patch) }

    /**
     * A create or change was saved: when the event has guests in a local calendar (or guests were removed), the editor
     * offers to tell them by mail (iTIP REQUEST / CANCEL, as on the web); otherwise it closes.
     */
    private fun saved(
        result: Any?,
        form: EventForm,
        withAttendees: Boolean,
    ) {
        val event = result as? EventDetails
        val guests = form.attendees.map { it.email }
        val removed =
            initial
                ?.attendees
                .orEmpty()
                .map { it.email }
                .filterNot { old -> guests.any { it.equals(old, ignoreCase = true) } }
        if (event == null || extras == null || !withAttendees || (guests.isEmpty() && removed.isEmpty())) {
            reset()
            return
        }
        state.update {
            it.copy(
                saving = false,
                invitation = InvitationUiState(event.id, event.title, guests, removed, afterSave = true),
            )
        }
        loadAccounts()
    }

    private fun sendDelete(
        edited: Occurrence,
        scope: EditScope?,
    ) = send(EditorMessage.DELETE_FAILED) { repository.delete(edited, version, scope) }

    /**
     * Sends a create, change or delete; on success the editor closes (the calendar views reload when they come back,
     * which also updates their cache). On failure nothing changes here: the form stays, with the reason shown.
     */
    private fun send(
        failed: EditorMessage = EditorMessage.FAILED,
        onOk: (Any?) -> Unit = { reset() },
        request: suspend () -> CalendarResult<*>,
    ) {
        val creating = occurrence == null
        state.update { it.copy(saving = true, message = null) }
        job =
            viewModelScope.launch {
                when (val result = request()) {
                    is CalendarResult.Ok -> onOk(result.value)
                    CalendarResult.Offline -> state.update { it.copy(saving = false, message = EditorMessage.OFFLINE) }
                    is CalendarResult.Failed -> {
                        // A refused create (409: the calendar is read-only, or the id belongs to an event in the
                        // Trash) means no live event has this id, so a retry with another id can't create a duplicate.
                        if (creating && result.status == CREATE_REFUSED) newId = UuidV7.generate(clock.millis())
                        state.update {
                            it.copy(
                                saving = false,
                                message =
                                    when {
                                        // Nothing to reload for a new event: the form stays editable for a retry.
                                        creating -> failed
                                        result.status in CONFLICTS -> EditorMessage.CONFLICT
                                        result.status == NOT_FOUND -> EditorMessage.GONE
                                        else -> failed
                                    },
                            )
                        }
                    }
                }
            }
    }

    /**
     * Loads the event holding the edited occurrence. A generated occurrence is read again first: the view may show it
     * out of date (the series moved, or the occurrence became an override or was removed elsewhere), and its times
     * come from it, not from the event.
     */
    private fun load() {
        val shown = occurrence ?: return
        job?.cancel()
        respondJob?.cancel()
        state.update {
            it.copy(
                loading = true,
                responding = null,
                form = null,
                message = null,
                showErrors = false,
                scopes = null,
                deleting = false,
                confirmingDelete = false,
            )
        }
        job =
            viewModelScope.launch {
                val edited =
                    if (shown.generated) {
                        val fresh = repository.reread(shown)
                        if (fresh !is CalendarResult.Ok) {
                            state.update { it.copy(loading = false, message = loadFailure(fresh)) }
                            return@launch
                        }
                        fresh.value ?: run {
                            state.update { it.copy(loading = false, message = EditorMessage.GONE) }
                            return@launch
                        }
                    } else {
                        shown
                    }
                // Saved changes go to the occurrence as it is now.
                occurrence = edited
                val event = repository.event(edited.eventId)
                val master =
                    when {
                        event !is CalendarResult.Ok -> null
                        edited.seriesId != null && edited.seriesId != event.value.id -> repository.event(edited.seriesId)
                        else -> null
                    }
                val failure = listOf(event, master).firstOrNull { it != null && it !is CalendarResult.Ok }
                if (event !is CalendarResult.Ok || failure != null) {
                    state.update { it.copy(loading = false, message = loadFailure(failure)) }
                    return@launch
                }
                val form = editForm(edited, event.value, (master as? CalendarResult.Ok)?.value, zone)
                version = event.value.version
                initial = form
                state.update { it.copy(loading = false, form = form, savedAttendees = form.attendees.map { a -> a.email }) }
                loadLinks()
            }
    }

    // --- Guests

    /** The "add guest" field changed: suggests matching contacts (after a short pause, from two characters). */
    fun setGuestQuery(text: String) {
        state.update { it.copy(guestQuery = text, guestInvalid = false) }
        suggestJob?.cancel()
        val query = text.trim()
        val source = extras
        if (source == null || query.length < MIN_SUGGEST_LENGTH || !state.value.attendeesEditable) {
            state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob =
            viewModelScope.launch {
                delay(SUGGEST_DELAY_MS)
                val found = (source.suggestions(query) as? CalendarResult.Ok)?.value.orEmpty()
                state.update { s ->
                    val invited =
                        s.form
                            ?.attendees
                            .orEmpty()
                            .map { it.email.lowercase() }
                            .toSet()
                    s.copy(suggestions = found.filterNot { it.email.lowercase() in invited })
                }
            }
    }

    /** Adds [suggestion], or the typed address when it is one. */
    fun addGuest(suggestion: Attendee? = null) {
        val current = state.value
        if (!current.attendeesEditable) return
        val email = suggestion?.email ?: current.guestQuery.trim()
        if (suggestion == null && !isEmailAddress(email)) {
            state.update { it.copy(guestInvalid = email.isNotEmpty()) }
            return
        }
        suggestJob?.cancel()
        state.update { s ->
            s.copy(
                form = s.form?.withAttendee(email, suggestion?.name),
                guestQuery = "",
                suggestions = emptyList(),
                guestInvalid = false,
            )
        }
    }

    fun removeGuest(email: String) {
        if (!state.value.attendeesEditable) return
        state.update { s -> s.copy(form = s.form?.withoutAttendee(email)) }
    }

    // --- Invitations

    /** Opens the invitation prompt for the saved event; unsaved changes must be saved first. */
    fun invite() {
        val current = state.value
        val edited = occurrence ?: return
        val form = current.form ?: return
        val saved = initial ?: return
        if (!current.invitable) return
        if (patchBody(saved, form, true).isNotEmpty()) {
            state.update { it.copy(saveBeforeInviting = true) }
            return
        }
        state.update {
            it.copy(
                saveBeforeInviting = false,
                invitationSent = null,
                invitation = InvitationUiState(edited.eventId, saved.title, current.savedAttendees, emptyList(), afterSave = false),
            )
        }
        loadAccounts()
    }

    /** Changes the prompt's choices (not while it sends). */
    fun updateInvitation(change: (InvitationUiState) -> InvitationUiState) {
        state.update { s -> s.copy(invitation = s.invitation?.let { if (it.sending) it else change(it) }) }
    }

    /** Loads the mail accounts of the invitation prompt (again, after a failure). */
    fun loadAccounts() {
        val source = extras ?: return
        if (state.value.invitation == null) return
        updateInvitation { it.copy(accounts = null, loadFailed = false) }
        inviteJob?.cancel()
        inviteJob =
            viewModelScope.launch {
                val result = source.mailAccounts()
                updateInvitation {
                    if (result is CalendarResult.Ok) {
                        it.copy(accounts = result.value, accountId = it.accountId ?: result.value.firstOrNull()?.id)
                    } else {
                        it.copy(loadFailed = true)
                    }
                }
            }
    }

    /** Sends the prompt's mail(s); after a save the editor then closes, otherwise the prompt does. */
    fun sendInvitation() {
        val source = extras ?: return
        val prompt = state.value.invitation ?: return
        val accountId = prompt.accountId ?: return
        if (!prompt.canSend) return
        state.update { s -> s.copy(invitation = prompt.copy(sending = true, failed = false)) }
        inviteJob =
            viewModelScope.launch {
                var count = 0
                var ok = true
                if (prompt.recipients.isNotEmpty()) {
                    val result = source.sendInvitations(prompt.eventId, prompt.method, accountId)
                    if (result is CalendarResult.Ok) count += result.value else ok = false
                }
                if (ok && prompt.tellRemoved && prompt.removed.isNotEmpty()) {
                    val result = source.sendInvitations(prompt.eventId, InvitationMethod.CANCEL, accountId, prompt.removed)
                    if (result is CalendarResult.Ok) count += result.value else ok = false
                }
                when {
                    !ok -> state.update { s -> s.copy(invitation = s.invitation?.copy(sending = false, failed = true)) }
                    prompt.afterSave -> reset()
                    else -> state.update { it.copy(invitation = null, invitationSent = InvitationSent(prompt.method, count)) }
                }
            }
    }

    /** The prompt was dismissed without sending; after a save the editor closes (the event is saved). */
    fun dismissInvitation() {
        val prompt = state.value.invitation ?: return
        if (prompt.sending) return
        inviteJob?.cancel()
        if (prompt.afterSave) reset() else state.update { it.copy(invitation = null) }
    }

    // --- Answering invitations

    /**
     * Answers the invitation to the edited event with [answer] (one of [INVITATION_RESPONSES]) at the provider. On
     * success the guests show the new status and the event's new version is kept (unsaved changes of the form stay).
     */
    fun respond(answer: AttendeeStatus) {
        val current = state.value
        val edited = occurrence ?: return
        if (!current.canRespond || answer !in INVITATION_RESPONSES) return
        state.update { it.copy(responding = answer, message = null) }
        respondJob =
            viewModelScope.launch {
                when (val result = repository.respond(edited.eventId, version, answer)) {
                    is CalendarResult.Ok -> {
                        val event = result.value
                        version = event.version
                        initial = initial?.copy(attendees = event.attendees)
                        state.update {
                            it.copy(
                                responding = null,
                                form = it.form?.copy(attendees = event.attendees),
                                savedAttendees = event.attendees.map { a -> a.email },
                            )
                        }
                    }
                    CalendarResult.Offline -> state.update { it.copy(responding = null, message = EditorMessage.OFFLINE) }
                    is CalendarResult.Failed ->
                        state.update {
                            it.copy(
                                responding = null,
                                message =
                                    when (result.status) {
                                        STALE -> EditorMessage.CONFLICT
                                        NOT_FOUND -> EditorMessage.GONE
                                        else -> EditorMessage.RESPOND_FAILED
                                    },
                            )
                        }
                }
            }
    }

    // --- Linked items

    /** Creates an Inbox task or a note titled with the event and its date, linked to the event. */
    fun createLinked(kind: LinkedKind) {
        val source = extras ?: return
        val links = state.value.links ?: return
        val edited = occurrence ?: return
        val saved = initial ?: return
        if (links.creating != null) return
        val title = "${saved.title.trim()} (${saved.startDate})"
        state.update { s -> s.copy(links = links.copy(creating = kind, result = null)) }
        linksJob =
            viewModelScope.launch {
                val result =
                    when (kind) {
                        LinkedKind.TASK -> source.createTask(title, edited.eventId)
                        LinkedKind.NOTE -> source.createNote(title, edited.eventId)
                    }
                state.update { s -> s.copy(links = s.links?.copy(creating = null, result = LinkedResult(kind, result))) }
                // Also after a failed link: the panel shows what the server has.
                if (result !is LinkedCreateResult.Failed) loadLinks()
            }
    }

    /** Loads the linked items of the edited event (again, after a failure). */
    fun loadLinks() {
        val source = extras ?: return
        val edited = occurrence ?: return
        loadLinksJob?.cancel()
        state.update { s -> s.copy(links = (s.links ?: LinksUiState()).copy(loading = true, failed = false)) }
        loadLinksJob =
            viewModelScope.launch {
                val result = source.links(edited.eventId)
                state.update { s ->
                    val links = s.links ?: LinksUiState()
                    s.copy(
                        links =
                            if (result is CalendarResult.Ok) {
                                links.copy(loading = false, items = result.value)
                            } else {
                                links.copy(loading = false, failed = true)
                            },
                    )
                }
            }
    }

    private fun loadSources() {
        val source = extras ?: return
        sourcesJob?.cancel()
        sourcesJob =
            viewModelScope.launch {
                launch {
                    val result = source.localCalendars()
                    if (result is CalendarResult.Ok) state.update { it.copy(localCalendars = result.value) }
                }
                if (state.value.creating) return@launch
                launch {
                    val result = source.invitationCalendars()
                    if (result is CalendarResult.Ok) state.update { it.copy(invitationCalendars = result.value) }
                }
                launch {
                    val result = source.ownAddresses()
                    if (result is CalendarResult.Ok) state.update { it.copy(ownAddresses = result.value) }
                }
            }
    }

    private fun loadFailure(result: CalendarResult<*>?) =
        if (result == CalendarResult.Offline) EditorMessage.LOAD_OFFLINE else EditorMessage.LOAD_FAILED

    private companion object {
        /** Changed concurrently or at the provider (409), or a stale `If-Match` (412). */
        val CONFLICTS = setOf(409, 412)

        /**
         * A stale `If-Match` (412). A 409 to an answer is a refusal instead (not invited, organizer, changed at
         * the provider, not synced yet, or read-only); a reload and another try may help.
         */
        const val STALE = 412

        /** The event or the occurrence no longer exists (or the event is in the Trash). */
        const val NOT_FOUND = 404

        /** A create refused: the calendar is read-only, or the id belongs to an event in the Trash. */
        const val CREATE_REFUSED = 409

        const val MIN_SUGGEST_LENGTH = 2
        const val SUGGEST_DELAY_MS = 250L
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
            reminders = event.reminderMinutes.distinct().sorted(),
            attendees = event.attendees,
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
