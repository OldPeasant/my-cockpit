package ch.sonensei.cockpit.gtd.next

import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.gtd.data.GtdConnectivity
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.data.body
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A task completed on this device, with the version the server gave it: undoing it sends that version. */
data class Completion(
    val taskId: UUID,
    val title: String,
    val version: Long,
)

/** Why completing or undoing did not go through. */
enum class CompletionMessage { OFFLINE, CONFLICT, FAILED }

sealed interface CompletionResult {
    data class Completed(
        val completion: Completion,
    ) : CompletionResult

    data object Reopened : CompletionResult

    data class NotDone(
        val message: CompletionMessage,
    ) : CompletionResult
}

/** Completes tasks and undoes that on the server, then reloads the cached Next and Today views. */
@Singleton
class TaskCompletionRepository
    @Inject
    constructor(
        private val api: TasksApi,
        private val tasks: TaskRepository,
        private val connectivity: GtdConnectivity,
    ) {
        suspend fun complete(task: GtdTask): CompletionResult {
            var version = task.version
            val result = connectivity.refreshing { version = body(api.completeTask(task.id, etag(task.version))).version }
            return outcome(result) { CompletionResult.Completed(Completion(task.id, task.title, version)) }
        }

        suspend fun undo(completion: Completion): CompletionResult {
            val result = connectivity.refreshing { body(api.uncompleteTask(completion.taskId, etag(completion.version))) }
            return outcome(result) { CompletionResult.Reopened }
        }

        private suspend fun outcome(
            result: RefreshResult,
            success: () -> CompletionResult,
        ): CompletionResult {
            // A refused request means the cached task is outdated: reloading shows its current state.
            if (result != RefreshResult.Offline) {
                tasks.refresh(TaskView.NEXT)
                tasks.refresh(TaskView.TODAY)
            }
            return when (result) {
                RefreshResult.Refreshed -> success()
                RefreshResult.Offline -> CompletionResult.NotDone(CompletionMessage.OFFLINE)
                is RefreshResult.Failed ->
                    CompletionResult.NotDone(if (result.status in CONFLICTS) CompletionMessage.CONFLICT else CompletionMessage.FAILED)
            }
        }

        private companion object {
            /** Not found (trashed elsewhere), conflict, precondition failed (changed elsewhere). */
            val CONFLICTS = setOf(404, 409, 412)
        }
    }

/** The `If-Match` value of [version] (the server's strong ETag). */
private fun etag(version: Long) = "\"$version\""

/** What the snackbar of a task list shows: the undo offer after a completion, or why an action failed. */
sealed interface CompletionSnackbar {
    data class Undo(
        val completion: Completion,
    ) : CompletionSnackbar

    data class Failed(
        val message: CompletionMessage,
    ) : CompletionSnackbar
}

data class CompletionState(
    /** Tasks being completed or reopened here: the lists hide them at once, before the server answers. */
    val pending: Set<UUID> = emptySet(),
    /**
     * Tasks completed here, with the version the completion gave them. A cached copy of an older version is the task
     * before the completion (a list not reloaded yet), so it stays hidden; a newer one was changed (e.g. reopened)
     * since, so it shows again.
     */
    val completed: Map<UUID, Long> = emptyMap(),
    val snackbar: CompletionSnackbar? = null,
) {
    /** The tasks the lists may hide, depending on the cached version (see [hides]). */
    val hidden: Set<UUID> get() = pending + completed.keys

    /** Whether the lists hide this cached copy of [task]. */
    fun hides(task: GtdTask): Boolean = task.id in pending || completed[task.id]?.let { task.version <= it } == true
}

/**
 * Completion with undo for a task list's view model: the task disappears at once, then the snackbar offers to undo
 * it; if the server refuses, the task comes back and the snackbar says why.
 */
class TaskCompleter(
    private val scope: CoroutineScope,
    private val repository: TaskCompletionRepository,
) {
    private val mutable = MutableStateFlow(CompletionState())
    val state: StateFlow<CompletionState> = mutable.asStateFlow()

    fun complete(task: GtdTask) {
        if (mutable.value.hides(task)) return
        mutable.update { it.copy(pending = it.pending + task.id, snackbar = null) }
        scope.launch {
            when (val result = repository.complete(task)) {
                is CompletionResult.Completed ->
                    mutable.update {
                        it.copy(
                            pending = it.pending - task.id,
                            completed = it.completed + (task.id to result.completion.version),
                            snackbar = CompletionSnackbar.Undo(result.completion),
                        )
                    }
                is CompletionResult.NotDone ->
                    mutable.update { it.copy(pending = it.pending - task.id, snackbar = CompletionSnackbar.Failed(result.message)) }
                CompletionResult.Reopened -> Unit
            }
        }
    }

    /**
     * Reopens the task of the undo [offer]. If the server refuses, the task shows again once the reloaded list holds a
     * newer version of it (reopened or changed elsewhere); while it is still completed, it stays hidden.
     */
    fun undo(offer: CompletionSnackbar.Undo) {
        dismiss(offer)
        val id = offer.completion.taskId
        mutable.update { it.copy(pending = it.pending + id) }
        scope.launch {
            when (val result = repository.undo(offer.completion)) {
                CompletionResult.Reopened -> mutable.update { it.copy(pending = it.pending - id, completed = it.completed - id) }
                is CompletionResult.NotDone ->
                    mutable.update { it.copy(pending = it.pending - id, snackbar = CompletionSnackbar.Failed(result.message)) }
                is CompletionResult.Completed -> Unit
            }
        }
    }

    /** Hides [shown] once the user dismissed it or it timed out; a newer snackbar stays. */
    fun dismiss(shown: CompletionSnackbar) = mutable.update { if (it.snackbar == shown) it.copy(snackbar = null) else it }
}
