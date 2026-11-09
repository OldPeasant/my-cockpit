package ch.sonensei.cockpit.calendar.agenda

import android.content.Context
import androidx.core.content.edit
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.models.TaskPageResponse
import ch.sonensei.cockpit.core.network.generated.models.TaskResponse
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.Response
import java.io.IOException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** An open task as the calendar's task overlay shows it (SPEC.md §6.3). */
data class OverlayTask(
    val id: UUID,
    val title: String,
    val dueDate: LocalDate? = null,
    /** `HH:mm` or `HH:mm:ss`, like the API. */
    val dueTime: String? = null,
    val scheduledDate: LocalDate? = null,
    val ticklerDate: LocalDate? = null,
    /** The task as the GTD lists know it: shown read-only by the detail screen while the server is unreachable. */
    val snapshot: GtdTask? = null,
    /** The day the server moved the task from Scheduled to Next on its scheduled date (its [scheduledDate] is then gone). */
    val releasedOn: LocalDate? = null,
)

/** Why a task shows on a day: it is due, scheduled (moves to Next), or comes back from a tickler. */
enum class TaskMarkKind { DUE, SCHEDULED, TICKLER }

/** A task on one day of the overlay; [time] only for a due task with a due time. */
data class TaskMark(
    val task: OverlayTask,
    val kind: TaskMarkKind,
    val date: LocalDate,
    val time: LocalTime? = null,
) {
    val key: String get() = "task-$kind-${task.id}"
}

/**
 * The overlay marks of [tasks] on the days [from, to): due date (at its time, if any), scheduled date and tickler date,
 * as they apply. A task the server released from Scheduled to Next on [today] was scheduled for today: it is marked
 * scheduled on today. Per day: timed due marks first by time, then the others by kind and title.
 */
fun taskMarks(
    tasks: List<OverlayTask>,
    from: LocalDate,
    to: LocalDate,
    today: LocalDate? = null,
): Map<LocalDate, List<TaskMark>> {
    fun inRange(date: LocalDate?) = date != null && date >= from && date < to
    val marks = mutableListOf<TaskMark>()
    for (task in tasks.distinctBy { it.id }) {
        if (inRange(task.dueDate)) marks += TaskMark(task, TaskMarkKind.DUE, task.dueDate!!, parseTime(task.dueTime))
        if (inRange(task.scheduledDate)) {
            marks += TaskMark(task, TaskMarkKind.SCHEDULED, task.scheduledDate!!)
        } else if (inRange(today) && task.releasedOn == today) {
            marks += TaskMark(task, TaskMarkKind.SCHEDULED, today!!)
        }
        if (inRange(task.ticklerDate)) marks += TaskMark(task, TaskMarkKind.TICKLER, task.ticklerDate!!)
    }
    return marks.groupBy { it.date }.mapValues { (_, day) -> day.sortedWith(markOrder) }.toSortedMap()
}

private val markOrder =
    compareBy<TaskMark>({ it.time == null }, { it.time }, { it.kind }, { it.task.title.lowercase() }, { it.task.id })

private fun parseTime(time: String?): LocalTime? =
    try {
        time?.let(LocalTime::parse)
    } catch (_: DateTimeParseException) {
        null
    }

/** Where the overlay's tasks come from; [None] for view models that show no overlay (tests). */
fun interface TaskOverlaySource {
    /**
     * The open tasks due, scheduled or in a tickler ending on a day of [from, to); when [today] is one of those days,
     * also the tasks released from Scheduled to Next today (scheduled for today).
     */
    suspend fun tasks(
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
    ): CalendarResult<List<OverlayTask>>

    companion object {
        val None = TaskOverlaySource { _, _, _ -> CalendarResult.Ok(emptyList()) }
    }
}

/**
 * The overlay's tasks from the server: due or tickler date in the range (the task list's `from`/`to` filter, which
 * also returns tasks still in a tickler), plus the Scheduled list filtered by its scheduled date, which that filter
 * does not cover. Every list read first releases the tasks scheduled for today to Next (clearing their scheduled
 * date), so when today is in the range those come from the `scheduled=today` filter, by their `releasedOn`.
 */
@Singleton
class TaskOverlayRepository
    @Inject
    constructor(
        private val api: TasksApi,
    ) : TaskOverlaySource {
        override suspend fun tasks(
            from: LocalDate,
            to: LocalDate,
            today: LocalDate,
        ): CalendarResult<List<OverlayTask>> =
            load {
                val found = mutableListOf<TaskResponse>()
                var partStart = from
                while (partStart < to) {
                    val partEnd = minOf(partStart.plusDays(MAX_RANGE_DAYS), to)
                    // The API's `to` is inclusive.
                    pages { cursor -> api.listTasks(from = partStart, to = partEnd.minusDays(1), cursor = cursor, limit = PAGE_SIZE) }
                        .let(found::addAll)
                    partStart = partEnd
                }
                pages { cursor -> api.listTasks(list = TasksApi.ListListTasks.SCHEDULED, cursor = cursor, limit = PAGE_SIZE) }
                    .filter { task -> task.scheduledDate?.let { it >= from && it < to } == true }
                    .let(found::addAll)
                if (today >= from && today < to) {
                    pages { cursor -> api.listTasks(scheduled = TasksApi.ScheduledListTasks.TODAY, cursor = cursor, limit = PAGE_SIZE) }
                        .filter { it.releasedOn == today }
                        .let(found::addAll)
                }
                found.filter { it.completedAt == null }.distinctBy { it.id }.map { it.toOverlayTask() }
            }

        private suspend fun pages(fetch: suspend (String?) -> Response<TaskPageResponse>): List<TaskResponse> {
            val all = mutableListOf<TaskResponse>()
            var cursor: String? = null
            var pagesRead = 0
            do {
                val response = fetch(cursor)
                val page = response.body()
                if (!response.isSuccessful || page == null) throw OverlayHttpFailure(response.code())
                all += page.items
                cursor = page.nextCursor
                pagesRead++
            } while (cursor != null && pagesRead < MAX_PAGES)
            return all
        }

        private suspend fun <T> load(block: suspend () -> T): CalendarResult<T> =
            try {
                CalendarResult.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                CalendarResult.Offline
            } catch (e: OverlayHttpFailure) {
                if (e.status >= SERVER_ERROR) CalendarResult.Offline else CalendarResult.Failed(e.status)
            } catch (_: SerializationException) {
                CalendarResult.Failed(null)
            } catch (_: IllegalArgumentException) {
                CalendarResult.Failed(null)
            } catch (_: DateTimeException) {
                CalendarResult.Failed(null)
            }

        private companion object {
            const val SERVER_ERROR = 500

            /** The task list serves at most a year per call. */
            const val MAX_RANGE_DAYS = 366L
            const val PAGE_SIZE = 200

            /** A safety stop: more open tasks than this in one range are not shown. */
            const val MAX_PAGES = 20
        }
    }

/** Whether the task overlay is on, remembered on the device (like the web remembers it per browser). */
interface TaskOverlaySetting {
    var enabled: Boolean

    /** Kept only as long as the object (tests, previews). */
    class InMemory(
        override var enabled: Boolean = false,
    ) : TaskOverlaySetting
}

@Singleton
class SharedPreferencesTaskOverlaySetting
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : TaskOverlaySetting {
        private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

        override var enabled: Boolean
            get() = preferences.getBoolean(KEY, false)
            set(value) = preferences.edit { putBoolean(KEY, value) }

        private companion object {
            const val PREFERENCES = "calendar_view"
            const val KEY = "task_overlay"
        }
    }

/** A non-2xx answer; its message carries only the status, never the body. */
private class OverlayHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

internal fun TaskResponse.toOverlayTask() =
    OverlayTask(
        id = id,
        title = title,
        dueDate = dueDate,
        dueTime = dueTime,
        scheduledDate = scheduledDate,
        ticklerDate = ticklerDate,
        releasedOn = releasedOn,
        snapshot =
            GtdTask(
                id = id,
                title = title,
                list = TaskList.valueOf(list.name),
                position = position,
                version = version,
                projectId = projectId,
                contextIds = contextIds,
                dueDate = dueDate,
                dueTime = dueTime,
                scheduledDate = scheduledDate,
                releasedOn = releasedOn,
                energy = energy?.let { Energy.valueOf(it.name) },
                creativity = creativity?.let { Creativity.valueOf(it.name) },
                timeEstimateMin = timeEstimateMin,
                waitingForText = waitingForText,
                waitingSince = waitingSince,
                notesMd = notesMd,
            ),
    )
