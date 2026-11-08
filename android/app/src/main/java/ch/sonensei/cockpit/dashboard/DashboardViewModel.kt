package ch.sonensei.cockpit.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.Occurrence
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
    ) : ViewModel() {
        private val state = MutableStateFlow(DashboardUiState())
        val uiState: StateFlow<DashboardUiState> = state.asStateFlow()

        private var loadJob: Job? = null

        /** Layout saves run one after the other, each sending the layout shown when it starts. */
        private val saving = Mutex()

        /** Layout changes not stored yet: a refresh meanwhile keeps the shown layout instead of the stored one. */
        private var unsaved = 0

        /** The layout last confirmed by the server, which a failed save returns to. */
        private var stored: List<WidgetSlot>? = null

        /**
         * Loads the layout and every shown widget again; a load still running is dropped. [pulled] shows the pull to
         * refresh indicator until all widgets answered. A layout that cannot be loaded keeps the one shown.
         */
        fun refresh(pulled: Boolean = false) {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    state.update { it.copy(refreshing = pulled) }
                    val layout = (dashboard.layout() as? DashboardResult.Ok)?.value
                    if (layout != null && unsaved == 0) stored = layout
                    state.update { current ->
                        val next = if (layout != null && unsaved == 0) current.withLayout(layout) else current
                        next.copy(
                            layoutLoaded = current.layoutLoaded || layout != null,
                            states = next.widgets.associateWith { current.state(it).copy(loading = true) },
                        )
                    }
                    state.value.widgets
                        .map { widget -> launch { load(widget) } }
                        .forEach { it.join() }
                    state.update { it.copy(refreshing = false) }
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
                open(
                    if (calendars !=
                        null
                    ) {
                        DashboardOpen.Event(occurrence, calendars)
                    } else {
                        DashboardOpen.Screen(CockpitDestination.Calendar)
                    },
                )
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
            val before = state.value.widgets.toSet()
            state.update { it.withLayout(edit(it.layout)).copy(layoutError = null) }
            val after = state.value.widgets
            after.filter { it !in before }.forEach { widget ->
                state.update { it.copy(states = it.states + (widget to it.state(widget).copy(loading = true))) }
                viewModelScope.launch { load(widget) }
            }
            unsaved++
            viewModelScope.launch {
                saving.withLock {
                    val result = dashboard.saveLayout(state.value.layout)
                    unsaved--
                    when (result) {
                        is DashboardResult.Ok -> {
                            stored = result.value
                            // A later change waits to be saved: keep showing it.
                            if (unsaved == 0) state.update { it.withLayout(result.value) }
                        }
                        else -> {
                            val error = if (result == DashboardResult.Offline) WidgetError.OFFLINE else WidgetError.FAILED
                            state.update { current -> (stored?.let { current.withLayout(it) } ?: current).copy(layoutError = error) }
                        }
                    }
                }
            }
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
