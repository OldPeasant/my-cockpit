package ch.sonensei.cockpit.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.gtd.data.MailRefreshTriggers
import ch.sonensei.cockpit.gtd.projects.ProjectOpenRequests
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

/** Why a widget shows no or outdated data, or why a layout change was not saved. */
enum class WidgetError { OFFLINE, FAILED }

/** One widget's state: [data] stays shown while it loads again, and also when a later load failed. */
data class WidgetState(
    val data: WidgetData? = null,
    val loading: Boolean = true,
    val error: WidgetError? = null,
)

/** What the screen opens for the view model, once, outside the dashboard's cards. */
sealed interface DashboardOpen {
    /** A tapped event, in the event editor; [calendars] tell its calendar (read-only, a birthday). */
    data class Event(
        val occurrence: Occurrence,
        val calendars: List<CalendarInfo>,
    ) : DashboardOpen

    /** Another screen (the Projects screen showing the requested project, the calendar when the event can't open). */
    data class Screen(
        val destination: CockpitDestination,
    ) : DashboardOpen
}

data class DashboardUiState(
    /** The shown widgets in their order: all of them until the layout is loaded (or when it cannot be). */
    val widgets: List<WidgetId> = WidgetId.entries,
    val states: Map<WidgetId, WidgetState> = emptyMap(),
    /** A pull to refresh is running. */
    val refreshing: Boolean = false,
    /** Every widget in its place, hidden ones too; [widgets] are its shown ones. */
    val layout: List<WidgetSlot> = DEFAULT_LAYOUT,
    /** The stored layout is known: only then can it be changed (else a save would overwrite it with the default). */
    val layoutLoaded: Boolean = false,
    /** The layout editor is shown. */
    val customizing: Boolean = false,
    /** The last layout change could not be saved (it was rolled back). */
    val layoutError: WidgetError? = null,
    /** Something the screen opens next, then reports with [DashboardViewModel.opened]. */
    val open: DashboardOpen? = null,
) {
    fun state(widget: WidgetId): WidgetState = states[widget] ?: WidgetState()
}

/**
 * The dashboard (SPEC.md §8.7): the layout stored in the user settings (shared with the web, changed with "Customize"),
 * then each shown widget loaded on its own, so one failing widget does not break the others.
 */
@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val dashboard: DashboardRepository,
        private val projectRequests: ProjectOpenRequests = ProjectOpenRequests(),
        private val mailChanges: MailRefreshTriggers = MailRefreshTriggers.NONE,
    ) : ViewModel() {
        private val state = MutableStateFlow(DashboardUiState())
        val uiState: StateFlow<DashboardUiState> = state.asStateFlow()

        private var loadJob: Job? = null
        private var mailJob: Job? = null

        /** A mail change arrived while a load ran: the Inbox widget reloads once more when it ends. */
        private var mailPending = false

        /** Layout saves run one after the other, each applying its change to the layout stored by then. */
        private val saving = Mutex()

        /**
         * Layout changes not stored yet, in order: the shown layout is them applied on top of [stored] (as on the web),
         * so a refused change drops out without taking the later ones with it, and a refresh meanwhile keeps them.
         */
        private val pending = mutableListOf<LayoutEdit>()

        /** Counts layout changes and finished saves: a layout read that started before one of them is outdated. */
        private var generation = 0

        /** The layout last confirmed by the server, which pending changes apply to. */
        private var stored: List<WidgetSlot>? = null

        /**
         * Loads the layout and every shown widget again; a load still running is dropped. [pulled] shows the pull to
         * refresh indicator until all widgets answered. A layout that cannot be loaded keeps the one shown.
         */
        fun refresh(pulled: Boolean = false) {
            loadJob?.cancel()
            mailJob?.cancel()
            mailPending = false
            loadJob =
                viewModelScope.launch {
                    state.update { it.copy(refreshing = pulled) }
                    val readFrom = generation
                    val layout = (dashboard.layout() as? DashboardResult.Ok)?.value
                    // A change made or saved while it was read is newer than what the read returned.
                    val fresh = layout?.takeIf { readFrom == generation && pending.isEmpty() }
                    if (fresh != null) stored = fresh
                    state.update { shown ->
                        val next = fresh?.let { shown.withLayout(it) } ?: shown
                        next.copy(
                            layoutLoaded = shown.layoutLoaded || layout != null,
                            states = next.widgets.associateWith { shown.state(it).copy(loading = true) },
                        )
                    }
                    state.value.widgets
                        .map { widget -> launch { load(widget) } }
                        .forEach { it.join() }
                    state.update { it.copy(refreshing = false) }
                    // A mail change during the load may have come after the server answered.
                    if (mailPending) reloadInbox()
                }
        }

        /**
         * Reloads the Inbox widget (its unread-mail count) on every mail change on the server while collected; the
         * screen collects it only while shown. A change arriving while a load runs is not loaded twice at once: the
         * widget reloads once more when that load ends. A failed reload keeps the widget as shown (no loading state, no
         * error).
         */
        suspend fun followMailChanges() =
            mailChanges.changes().collect {
                if (loadJob?.isActive == true || mailJob?.isActive == true) {
                    mailPending = true
                    return@collect
                }
                reloadInbox()
            }

        /** Reloads the Inbox widget quietly while it is shown, once more as long as a mail change arrived meanwhile. */
        private fun reloadInbox() {
            mailPending = false
            if (WidgetId.INBOX !in state.value.widgets) return
            mailJob =
                viewModelScope.launch {
                    do {
                        mailPending = false
                        val result = dashboard.load(WidgetId.INBOX) as? DashboardResult.Ok ?: continue
                        state.update { it.copy(states = it.states + (WidgetId.INBOX to WidgetState(result.value, loading = false))) }
                    } while (mailPending)
                }
        }

        /** Shows or hides the layout editor. */
        fun customize(on: Boolean) = state.update { it.copy(customizing = on && it.layoutLoaded, layoutError = null) }

        /** Shows or hides [widget]; a newly shown widget loads. */
        fun setVisible(
            widget: WidgetId,
            visible: Boolean,
        ) = change { it.withVisible(widget, visible) }

        /** Moves [widget] to [index] of the layout (hidden widgets count too). */
        fun move(
            widget: WidgetId,
            index: Int,
        ) = change { it.moved(widget, index) }

        /** Back to the default layout: every widget, shown, in the default order. */
        fun reset() = change { DEFAULT_LAYOUT }

        /** Opens [event] in the event editor; without its calendars (offline) the calendar opens instead. */
        fun openEvent(event: DashboardEvent) {
            val occurrence = event.occurrence ?: return open(DashboardOpen.Screen(CockpitDestination.Calendar))
            viewModelScope.launch {
                val calendars = (dashboard.calendars() as? DashboardResult.Ok)?.value
                open(calendars?.let { DashboardOpen.Event(occurrence, it) } ?: DashboardOpen.Screen(CockpitDestination.Calendar))
            }
        }

        /** Opens the project [id] in the Projects screen. */
        fun openProject(id: UUID) {
            projectRequests.request(id)
            open(DashboardOpen.Screen(CockpitDestination.Projects))
        }

        /** The screen carried out [open]. */
        fun opened(open: DashboardOpen) = state.update { if (it.open == open) it.copy(open = null) else it }

        private fun open(open: DashboardOpen) = state.update { it.copy(open = open) }

        private fun change(edit: (List<WidgetSlot>) -> List<WidgetSlot>) {
            if (!state.value.layoutLoaded) return
            val change = LayoutEdit(edit)
            val before = state.value.widgets.toSet()
            pending += change
            generation++
            state.update { it.withLayout(edit(it.layout)).copy(layoutError = null) }
            val after = state.value.widgets
            after.filter { it !in before }.forEach { widget ->
                state.update { it.copy(states = it.states + (widget to it.state(widget).copy(loading = true))) }
                viewModelScope.launch { load(widget) }
            }
            viewModelScope.launch {
                saving.withLock {
                    val base = stored ?: state.value.layout
                    val result = dashboard.saveLayout(edit(base))
                    pending -= change
                    generation++
                    if (result is DashboardResult.Ok) stored = result.value
                    val error = result.layoutError()
                    state.update { current ->
                        val shown = stored?.let { pending.fold(it) { layout, later -> later.apply(layout) } }
                        (shown?.let { current.withLayout(it) } ?: current).copy(layoutError = error ?: current.layoutError)
                    }
                }
            }
        }

        private fun DashboardResult<*>.layoutError(): WidgetError? =
            when (this) {
                is DashboardResult.Ok -> null
                DashboardResult.Offline -> WidgetError.OFFLINE
                DashboardResult.Failed -> WidgetError.FAILED
            }

        private fun DashboardUiState.withLayout(layout: List<WidgetSlot>) = copy(layout = layout, widgets = layout.shown())

        private suspend fun load(widget: WidgetId) {
            val result = dashboard.load(widget)
            state.update { current ->
                val before = current.state(widget)
                val after =
                    when (result) {
                        is DashboardResult.Ok -> WidgetState(result.value, loading = false, error = null)
                        DashboardResult.Offline -> before.copy(loading = false, error = WidgetError.OFFLINE)
                        DashboardResult.Failed -> before.copy(loading = false, error = WidgetError.FAILED)
                    }
                current.copy(states = current.states + (widget to after))
            }
        }
    }

/** One layout change, applied to whatever layout is stored when its turn to be saved comes. */
private class LayoutEdit(
    val apply: (List<WidgetSlot>) -> List<WidgetSlot>,
)
