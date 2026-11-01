package ch.sonensei.cockpit.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why a widget shows no or outdated data. */
enum class WidgetError { OFFLINE, FAILED }

/** One widget's state: [data] stays shown while it loads again, and also when a later load failed. */
data class WidgetState(
    val data: WidgetData? = null,
    val loading: Boolean = true,
    val error: WidgetError? = null,
)

data class DashboardUiState(
    /** The shown widgets in their order: all of them until the layout is loaded (or when it cannot be). */
    val widgets: List<WidgetId> = WidgetId.entries,
    val states: Map<WidgetId, WidgetState> = emptyMap(),
    /** A pull to refresh is running. */
    val refreshing: Boolean = false,
) {
    fun state(widget: WidgetId): WidgetState = states[widget] ?: WidgetState()
}

/**
 * The dashboard (SPEC.md §8.7): the layout the web stores (read only here), then each shown widget loaded on its own,
 * so one failing widget does not break the others.
 */
@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val dashboard: DashboardRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(DashboardUiState())
        val uiState: StateFlow<DashboardUiState> = state.asStateFlow()

        private var loadJob: Job? = null

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
                    state.update { current ->
                        val widgets = layout ?: current.widgets
                        current.copy(
                            widgets = widgets,
                            states = widgets.associateWith { current.state(it).copy(loading = true) },
                        )
                    }
                    state.value.widgets
                        .map { widget -> launch { load(widget) } }
                        .forEach { it.join() }
                    state.update { it.copy(refreshing = false) }
                }
        }

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
