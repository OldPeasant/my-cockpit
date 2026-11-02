package ch.sonensei.cockpit.gtd.task

import androidx.compose.runtime.staticCompositionLocalOf
import ch.sonensei.cockpit.gtd.data.GtdTask
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import java.util.UUID

/**
 * Opens the task detail screen of a task; [snapshot] is the list's copy, shown read-only if the server is unreachable,
 * and [completedAt] when the list knows the task is completed (the Logbook).
 */
fun interface TaskOpener {
    fun open(
        id: UUID,
        snapshot: GtdTask?,
        completedAt: Instant?,
    )

    /** Opens a task of a list that does not know about completion (only open tasks are listed). */
    fun open(
        id: UUID,
        snapshot: GtdTask?,
    ) = open(id, snapshot, null)
}

/**
 * How task rows open the detail screen, provided by the shell. `null` (outside the shell: screen tests, previews) leaves
 * the rows as they were, without a tap target.
 */
val LocalTaskOpener = staticCompositionLocalOf<TaskOpener?> { null }

/** The navigation route of the task detail screen. */
object TaskRoutes {
    const val ARG_ID = "id"
    const val DETAIL = "task/{$ARG_ID}"

    fun detail(id: UUID) = "task/$id"

    fun isDetail(route: String?) = route == DETAIL
}

/**
 * The list copies of the tasks opened last, so the detail screen can show a task read-only while the server is
 * unreachable, also from lists that are not cached (Waiting, Someday, a project, the Logbook).
 */
object TaskSnapshots {
    private const val MAX = 50
    private val snapshots = LinkedHashMap<UUID, CachedTask>()

    @Synchronized
    fun put(
        task: GtdTask,
        completedAt: Instant? = null,
    ) {
        snapshots.remove(task.id)
        snapshots[task.id] = CachedTask(task, loadedAt = null, completedAt = completedAt)
        if (snapshots.size > MAX) snapshots.remove(snapshots.keys.first())
    }

    @Synchronized
    fun get(id: UUID): GtdTask? = snapshots[id]?.task

    /** The copy of [id] with its completion time, if one was kept. */
    @Synchronized
    fun cached(id: UUID): CachedTask? = snapshots[id]

    @Synchronized
    fun clear() = snapshots.clear()
}

/**
 * Tells the live (uncached) task lists that a task was changed on the detail screen, so a list the user returns to
 * reloads at once, also when the server's change stream is not connected.
 */
object TaskEdits {
    private val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val changes: Flow<Unit> = signals.asSharedFlow()

    fun changed() {
        signals.tryEmit(Unit)
    }
}
