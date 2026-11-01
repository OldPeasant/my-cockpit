package ch.sonensei.cockpit.gtd.lists

import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.gtd.data.ChangeTypes
import ch.sonensei.cockpit.gtd.data.GtdConnectivity
import ch.sonensei.cockpit.gtd.data.GtdRefreshTriggers
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.body
import ch.sonensei.cockpit.gtd.next.Completion
import ch.sonensei.cockpit.gtd.next.CompletionMessage
import ch.sonensei.cockpit.gtd.next.CompletionResult
import ch.sonensei.cockpit.gtd.next.TaskCompletionRepository
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A done task of the Logbook and when it was completed. */
data class LoggedTask(
    val task: GtdTask,
    val completedAt: Instant,
)

/** One page of the Logbook, newest first; [nextCursor] is `null` on the last page. */
data class LogbookPage(
    val tasks: List<LoggedTask>,
    val nextCursor: String?,
)

/** The outcome of loading a Logbook page. */
sealed interface LogbookLoad {
    data class Loaded(
        val page: LogbookPage,
    ) : LogbookLoad

    data class NotLoaded(
        val error: LoadError,
    ) : LogbookLoad
}

/**
 * The Logbook (SPEC.md §5): done tasks, loaded page by page from the server while shown (not cached), and reopening
 * them (`POST /tasks/{id}/uncomplete`).
 */
@Singleton
class LogbookRepository
    @Inject
    constructor(
        private val api: TasksApi,
        private val connectivity: GtdConnectivity,
        private val triggers: GtdRefreshTriggers,
        private val completion: TaskCompletionRepository,
    ) {
        /** Emits at once and on every task change while collected: the Logbook should be reloaded. */
        fun changes(): Flow<Unit> = triggers.on(ChangeTypes.TASK)

        /** The page after [cursor] (the first page for `null`), newest completion first. */
        suspend fun page(cursor: String?): LogbookLoad {
            var page: LogbookPage? = null
            val result =
                connectivity.refreshing {
                    val body = body(api.listTasks(completed = true, cursor = cursor, limit = LOGBOOK_PAGE_SIZE))
                    page =
                        LogbookPage(
                            // An open task (reopened between the pages) has no completion time: it is not in the Logbook.
                            tasks = body.items.mapNotNull { item -> item.completedAt?.let { LoggedTask(item.toTask(), it.toInstant()) } },
                            nextCursor = body.nextCursor,
                        )
                }
            return when (result) {
                RefreshResult.Refreshed -> LogbookLoad.Loaded(checkNotNull(page))
                RefreshResult.Offline -> LogbookLoad.NotLoaded(LoadError.OFFLINE)
                is RefreshResult.Failed -> LogbookLoad.NotLoaded(LoadError.FAILED)
            }
        }

        /** Reopens [entry] (it leaves the Logbook and is back on its list); the cached Next and Today views reload. */
        suspend fun reopen(entry: LoggedTask): ActionOutcome =
            when (val result = completion.undo(Completion(entry.task.id, entry.task.title, entry.task.version))) {
                CompletionResult.Reopened, is CompletionResult.Completed -> ActionOutcome.DONE
                is CompletionResult.NotDone ->
                    when (result.message) {
                        CompletionMessage.OFFLINE -> ActionOutcome.OFFLINE
                        CompletionMessage.CONFLICT -> ActionOutcome.CONFLICT
                        CompletionMessage.FAILED -> ActionOutcome.FAILED
                    }
            }
    }

/** Tasks per Logbook page. */
internal const val LOGBOOK_PAGE_SIZE = 50
