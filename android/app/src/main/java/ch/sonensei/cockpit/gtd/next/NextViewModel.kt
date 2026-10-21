package ch.sonensei.cockpit.gtd.next

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/**
 * The filters of the Next Actions view (SPEC.md §5.3), applied to the cached Next list so they also work offline. Like
 * the server's filters, a set filter only matches tasks that have the attribute (e.g. [maxMinutes] needs an estimate).
 */
data class NextFilter(
    val contextId: UUID? = null,
    val energy: Energy? = null,
    val creativity: Creativity? = null,
    /** Only tasks estimated at most this many minutes. */
    val maxMinutes: Int? = null,
) {
    val activeCount: Int get() = listOfNotNull(contextId, energy, creativity, maxMinutes).size

    fun matches(task: GtdTask): Boolean =
        (contextId == null || contextId in task.contextIds) &&
            (energy == null || task.energy == energy) &&
            (creativity == null || task.creativity == creativity) &&
            (maxMinutes == null || (task.timeEstimateMin != null && task.timeEstimateMin <= maxMinutes))

    companion object {
        /** The estimate quick picks (SPEC.md §5.1). */
        val ESTIMATES = listOf(5, 15, 30, 60, 120)
    }
}

data class NextUiState(
    /** The Next tasks that match [filter], in list order. */
    val tasks: List<GtdTask> = emptyList(),
    /** How many open Next tasks there are without the filter. */
    val total: Int = 0,
    val filter: NextFilter = NextFilter(),
    val contexts: List<GtdContext> = emptyList(),
    val refreshedAt: Instant? = null,
    /** The server is unreachable: the cached list is shown read-only, completing is disabled. */
    val offline: Boolean = false,
    val snackbar: CompletionSnackbar? = null,
    val today: LocalDate = LocalDate.MIN,
) {
    val loading: Boolean get() = refreshedAt == null && total == 0 && !offline
}

/** The Next Actions view: the cached Next list, filtered, with completion and undo. */
@HiltViewModel
class NextViewModel internal constructor(
    tasks: TaskRepository,
    contexts: ContextRepository,
    completion: TaskCompletionRepository,
    private val clock: Clock,
) : ViewModel() {
    @Inject
    constructor(
        tasks: TaskRepository,
        contexts: ContextRepository,
        completion: TaskCompletionRepository,
    ) : this(tasks, contexts, completion, Clock.systemDefaultZone())

    private val completer = TaskCompleter(viewModelScope, completion)
    private val filter = MutableStateFlow(NextFilter())

    val uiState: StateFlow<NextUiState> =
        combine(tasks.observe(TaskView.NEXT), contexts.observe(), filter, completer.state) { next, contextList, f, done ->
            val open = next.data.filter { it.id !in done.hidden }
            // A context deleted elsewhere can no longer be chosen or cleared, so its filter is dropped.
            val shown = if (f.contextId != null && contextList.data.none { it.id == f.contextId }) f.copy(contextId = null) else f
            NextUiState(
                tasks = open.filter(shown::matches),
                total = open.size,
                filter = shown,
                contexts = contextList.data,
                refreshedAt = next.refreshedAt,
                offline = next.offline,
                snackbar = done.snackbar,
                today = LocalDate.now(clock),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NextUiState(today = LocalDate.now(clock)))

    /** Chooses [id] as the context filter, or clears it when it already is. */
    fun toggleContext(id: UUID) = filter.update { it.copy(contextId = if (it.contextId == id) null else id) }

    fun toggleEnergy(energy: Energy) = filter.update { it.copy(energy = if (it.energy == energy) null else energy) }

    fun toggleCreativity(creativity: Creativity) =
        filter.update {
            it.copy(
                creativity =
                    if (it.creativity ==
                        creativity
                    ) {
                        null
                    } else {
                        creativity
                    },
            )
        }

    fun toggleMaxMinutes(minutes: Int) = filter.update { it.copy(maxMinutes = if (it.maxMinutes == minutes) null else minutes) }

    fun clearFilter() = filter.update { NextFilter() }

    fun complete(task: GtdTask) {
        if (!uiState.value.offline) completer.complete(task)
    }

    fun undo(offer: CompletionSnackbar.Undo) = completer.undo(offer)

    fun dismiss(shown: CompletionSnackbar) = completer.dismiss(shown)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
