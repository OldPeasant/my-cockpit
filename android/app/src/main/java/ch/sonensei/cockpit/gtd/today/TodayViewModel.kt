package ch.sonensei.cockpit.gtd.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.next.CompletionSnackbar
import ch.sonensei.cockpit.gtd.next.TaskCompleter
import ch.sonensei.cockpit.gtd.next.TaskCompletionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** The task sections of the Today view, in this order. */
enum class TodaySectionKind { OVERDUE, DUE, SCHEDULED }

data class TodaySection(
    val kind: TodaySectionKind,
    val tasks: List<GtdTask>,
)

data class TodayUiState(
    /** The sections that have tasks; a task shows in the first section it belongs to. */
    val sections: List<TodaySection> = emptyList(),
    val contexts: List<GtdContext> = emptyList(),
    /** When the shown lists were loaded (the older of the two); `null` while one was never loaded. */
    val refreshedAt: Instant? = null,
    /** The server is unreachable: the cached lists are shown read-only, completing is disabled. */
    val offline: Boolean = false,
    val snackbar: CompletionSnackbar? = null,
    val today: LocalDate = LocalDate.MIN,
) {
    val loading: Boolean get() = refreshedAt == null && sections.isEmpty() && !offline
}

/**
 * The Today view (SPEC.md §5.3): overdue tasks and tasks due today (the cached Today view), then the Next tasks
 * scheduled for today or earlier (the server moves due SCHEDULED tasks to NEXT), with completion and undo.
 */
@HiltViewModel
class TodayViewModel internal constructor(
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

    val uiState: StateFlow<TodayUiState> =
        combine(
            tasks.observe(TaskView.TODAY),
            tasks.observe(TaskView.NEXT),
            contexts.observe(),
            completer.state,
        ) { due, next, contextList, done ->
            val today = LocalDate.now(clock)
            val open = (due.data + next.data).filter { it.id !in done.hidden }.distinctBy { it.id }
            val kinds = open.groupBy { sectionOf(it, today) }
            TodayUiState(
                sections = TodaySectionKind.entries.mapNotNull { kind -> kinds[kind]?.let { TodaySection(kind, it) } },
                contexts = contextList.data,
                refreshedAt = if (due.refreshedAt == null || next.refreshedAt == null) null else minOf(due.refreshedAt, next.refreshedAt),
                offline = due.offline || next.offline,
                snackbar = done.snackbar,
                today = today,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TodayUiState(today = LocalDate.now(clock)))

    fun complete(task: GtdTask) {
        if (!uiState.value.offline) completer.complete(task)
    }

    fun undo(offer: CompletionSnackbar.Undo) = completer.undo(offer)

    fun dismiss(shown: CompletionSnackbar) = completer.dismiss(shown)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The section [task] belongs to on [today], or `null` when it is not for today (a Next task without a date). */
internal fun sectionOf(
    task: GtdTask,
    today: LocalDate,
): TodaySectionKind? {
    val dueDate = task.dueDate
    val scheduledDate = task.scheduledDate
    return when {
        dueDate != null && dueDate.isBefore(today) -> TodaySectionKind.OVERDUE
        dueDate == today -> TodaySectionKind.DUE
        scheduledDate != null && !scheduledDate.isAfter(today) -> TodaySectionKind.SCHEDULED
        else -> null
    }
}
