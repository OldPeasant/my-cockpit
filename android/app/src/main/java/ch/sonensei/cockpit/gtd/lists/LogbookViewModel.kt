package ch.sonensei.cockpit.gtd.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** The done tasks of one day, newest first. */
data class LogbookDay(
    val date: LocalDate,
    val tasks: List<LoggedTask>,
)

data class LogbookUiState(
    /** The loaded done tasks, newest completion first. */
    val tasks: List<LoggedTask> = emptyList(),
    val loading: Boolean = true,
    /** Why the last (re)load did not work; with nothing loaded yet the screen offers a retry instead of the list. */
    val error: LoadError? = null,
    /** Something was loaded once (only before that [error] replaces the list). */
    val loaded: Boolean = false,
    /** The server has more (older) pages. */
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
    /** Why the last "load more" did not work; scrolling to the end again (or its retry) tries again. */
    val moreError: LoadError? = null,
    /** Project titles by id, for the rows. */
    val projects: Map<UUID, String> = emptyMap(),
    val contexts: Map<UUID, GtdContext> = emptyMap(),
    /** Tasks being reopened: their buttons are disabled. */
    val busy: Set<UUID> = emptySet(),
    /** The task opened in the detail dialog. */
    val opened: LoggedTask? = null,
    val action: ActionSnackbar? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    val today: LocalDate = LocalDate.MIN,
) {
    /** Reopening needs the server: while the last load failed, the list is shown read-only. */
    val readOnly: Boolean get() = error != null

    /** [tasks] grouped by their local completion day, newest day first. */
    val days: List<LogbookDay> get() = groupByDay(tasks, zone)
}

/** Groups [tasks] (newest first) by the day in [zone] they were completed on, keeping their order. */
internal fun groupByDay(
    tasks: List<LoggedTask>,
    zone: ZoneId,
): List<LogbookDay> =
    tasks
        .groupBy { it.completedAt.atZone(zone).toLocalDate() }
        .map { (date, done) -> LogbookDay(date, done) }

/**
 * The Logbook: done tasks newest first, page by page as the user scrolls, with "Reopen". It reloads on task changes
 * (keeping as many tasks as were loaded), so it stays current also when tasks are completed elsewhere.
 */
@HiltViewModel
class LogbookViewModel internal constructor(
    private val repository: LogbookRepository,
    projects: ProjectRepository,
    contexts: ContextRepository,
    private val clock: Clock,
) : ViewModel() {
    @Inject
    constructor(
        repository: LogbookRepository,
        projects: ProjectRepository,
        contexts: ContextRepository,
    ) : this(repository, projects, contexts, Clock.systemDefaultZone())

    private val state = MutableStateFlow(LogbookUiState(zone = clock.zone, today = LocalDate.now(clock)))
    private val reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var cursor: String? = null

    /** Pages shown now: a reload loads as many again, so it does not cut the list the user scrolled through. */
    private var pagesLoaded = 0
    private var moreJob: Job? = null

    val uiState: StateFlow<LogbookUiState> =
        combine(
            state,
            projects.observe().map { cached -> cached.data.associate { it.id to it.title } }.onStart { emit(emptyMap()) },
            contexts.observe().map { cached -> cached.data.associateBy { it.id } }.onStart { emit(emptyMap()) },
        ) { current, projectTitles, contextsById -> current.copy(projects = projectTitles, contexts = contextsById) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), state.value)

    init {
        viewModelScope.launch {
            merge(repository.changes(), reloads).collectLatest { reload() }
        }
    }

    fun retry() {
        reloads.tryEmit(Unit)
    }

    /** Loads the next (older) page, if there is one and no load is running. */
    fun loadMore() {
        val current = state.value
        val next = cursor
        if (next == null || current.loading || current.loadingMore || moreJob?.isActive == true) return
        state.update { it.copy(loadingMore = true, moreError = null) }
        moreJob =
            viewModelScope.launch {
                when (val load = repository.page(next)) {
                    is LogbookLoad.Loaded -> {
                        cursor = load.page.nextCursor
                        pagesLoaded++
                        state.update {
                            val known = it.tasks.mapTo(HashSet()) { done -> done.task.id }
                            it.copy(
                                tasks = it.tasks + load.page.tasks.filterNot { done -> done.task.id in known },
                                canLoadMore = cursor != null,
                                loadingMore = false,
                            )
                        }
                    }
                    is LogbookLoad.NotLoaded -> state.update { it.copy(loadingMore = false, moreError = load.error) }
                }
            }
    }

    fun open(task: LoggedTask) = state.update { it.copy(opened = task) }

    fun close() = state.update { it.copy(opened = null) }

    /** Uncompletes [task]: once the server agrees it leaves the Logbook; a refusal is reported and the list reloads. */
    fun reopen(task: LoggedTask) {
        val current = state.value
        if (current.readOnly || task.task.id in current.busy) return
        state.update { it.copy(busy = it.busy + task.task.id) }
        viewModelScope.launch {
            val outcome = repository.reopen(task)
            state.update {
                val gone = outcome == ActionOutcome.DONE
                it.copy(
                    tasks = if (gone) it.tasks.filterNot { done -> done.task.id == task.task.id } else it.tasks,
                    busy = it.busy - task.task.id,
                    opened = if (gone && it.opened?.task?.id == task.task.id) null else it.opened,
                    action = ActionSnackbar(ActionSnackbar.Kind.REOPENED, task.task.title, outcome),
                )
            }
            if (outcome == ActionOutcome.DONE || outcome == ActionOutcome.CONFLICT) reloads.tryEmit(Unit)
        }
    }

    fun dismissAction(shown: ActionSnackbar) = state.update { if (it.action == shown) it.copy(action = null) else it }

    /** Loads the Logbook from its first page again, as many pages as were shown. A failed reload keeps what was shown. */
    private suspend fun reload() {
        moreJob?.cancel()
        val wanted = maxOf(1, pagesLoaded)
        state.update {
            it.copy(
                loading = !it.loaded,
                error = if (it.loaded) it.error else null,
                loadingMore = false,
                today = LocalDate.now(clock),
            )
        }
        val tasks = mutableListOf<LoggedTask>()
        var next: String? = null
        var pages = 0
        do {
            when (val load = repository.page(next)) {
                is LogbookLoad.Loaded -> {
                    val known = tasks.mapTo(HashSet()) { it.task.id }
                    tasks += load.page.tasks.filterNot { it.task.id in known }
                    next = load.page.nextCursor
                }
                is LogbookLoad.NotLoaded -> {
                    state.update { it.copy(loading = false, error = load.error) }
                    return
                }
            }
            pages++
        } while (next != null && pages < wanted)
        cursor = next
        pagesLoaded = pages
        state.update {
            it.copy(
                tasks = tasks,
                loading = false,
                loaded = true,
                error = null,
                canLoadMore = next != null,
                moreError = null,
                opened = it.opened?.let { opened -> tasks.firstOrNull { done -> done.task.id == opened.task.id } },
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
