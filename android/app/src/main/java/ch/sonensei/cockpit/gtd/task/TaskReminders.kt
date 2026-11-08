package ch.sonensei.cockpit.gtd.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.core.network.generated.apis.RemindersApi
import ch.sonensei.cockpit.core.network.generated.models.CreateReminderRequest
import ch.sonensei.cockpit.core.network.generated.models.ReminderResponse
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.reminders.ReminderSync
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.Response
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import ch.sonensei.cockpit.core.network.generated.apis.RemindersApi.ItemTypeListReminders as ListItemType

/** One reminder of a task. [firedAt] is set once the server fired it. */
data class TaskReminder(
    val id: UUID,
    val remindAt: Instant,
    val firedAt: Instant? = null,
)

/** A failed reminder call. */
enum class ReminderError { OFFLINE, FAILED, INVALID }

/** The reminders of a task on the server (`GET/POST/DELETE /api/v1/reminders`, itemType TASK). */
interface TaskReminderSource {
    suspend fun list(taskId: UUID): Result<List<TaskReminder>>

    suspend fun add(
        taskId: UUID,
        at: Instant,
    ): Result<TaskReminder>

    suspend fun remove(id: UUID): Result<Unit>
}

/** Thrown inside a failed [Result] of a [TaskReminderSource]. */
class ReminderCallFailure(
    val error: ReminderError,
) : Exception(error.name)

@Singleton
class TaskReminderRepository
    @Inject
    constructor(
        private val api: RemindersApi,
        private val sync: ReminderSync,
    ) : TaskReminderSource {
        override suspend fun list(taskId: UUID): Result<List<TaskReminder>> =
            call({ api.listReminders(ListItemType.TASK, taskId) }) { body ->
                body
                    ?.items
                    .orEmpty()
                    .mapNotNull { it.toReminder() }
                    .sortedBy { it.remindAt }
            }

        override suspend fun add(
            taskId: UUID,
            at: Instant,
        ): Result<TaskReminder> {
            val request =
                CreateReminderRequest(
                    itemId = taskId,
                    itemType = CreateReminderRequest.ItemType.TASK,
                    remindAt = at.atOffset(ZoneOffset.UTC),
                    channel = CreateReminderRequest.Channel.NOTIFICATION,
                )
            return call({ api.createReminder(request) }) { it?.toReminder() ?: error("empty body") }
                .also { if (it.isSuccess) sync.sync() }
        }

        override suspend fun remove(id: UUID): Result<Unit> =
            // A reminder already gone counts as removed.
            call({ api.deleteReminder(id) }, okStatus = HTTP_NOT_FOUND) { }
                .also { if (it.isSuccess) sync.sync() }

        private suspend fun <B, T> call(
            request: suspend () -> Response<B>,
            okStatus: Int? = null,
            read: (B?) -> T,
        ): Result<T> =
            try {
                val response = request()
                val code = response.code()
                when {
                    response.isSuccessful || code == okStatus -> Result.success(read(response.body()))
                    code == HTTP_BAD_REQUEST || code == HTTP_UNPROCESSABLE ->
                        Result.failure(ReminderCallFailure(ReminderError.INVALID))
                    else -> Result.failure(ReminderCallFailure(ReminderError.FAILED))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                Result.failure(ReminderCallFailure(ReminderError.OFFLINE))
            } catch (_: Exception) {
                // An unreadable body. Not logged: it may carry user data.
                Result.failure(ReminderCallFailure(ReminderError.FAILED))
            }

        private companion object {
            const val HTTP_BAD_REQUEST = 400
            const val HTTP_NOT_FOUND = 404
            const val HTTP_UNPROCESSABLE = 422
        }
    }

private fun ReminderResponse.toReminder(): TaskReminder? = remindAt?.let { TaskReminder(id, it.toInstant(), firedAt?.toInstant()) }

@Module
@InstallIn(SingletonComponent::class)
abstract class TaskReminderBindings {
    @Binds
    abstract fun taskReminders(repository: TaskReminderRepository): TaskReminderSource
}

/** The quick choices for a new reminder, relative to the task's dates. */
enum class ReminderPreset {
    AT_DUE,
    HOUR_BEFORE_DUE,
    DAY_BEFORE_DUE,
    AT_SCHEDULED,
}

/** The task dates the presets are relative to. */
data class ReminderAnchors(
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val scheduledDate: LocalDate? = null,
)

/** A date without a time reminds at this time of day. */
val DEFAULT_REMINDER_TIME: LocalTime = LocalTime.of(9, 0)

/** When [preset] reminds for a task with [anchors], or `null` when the task lacks the date it needs. */
fun ReminderPreset.at(
    anchors: ReminderAnchors,
    zone: ZoneId,
): Instant? {
    val due = anchors.dueDate?.atTime(anchors.dueTime ?: DEFAULT_REMINDER_TIME)?.atZone(zone)
    return when (this) {
        ReminderPreset.AT_DUE -> due
        ReminderPreset.HOUR_BEFORE_DUE -> if (anchors.dueTime != null) due?.minusHours(1) else null
        ReminderPreset.DAY_BEFORE_DUE -> due?.minusDays(1)
        ReminderPreset.AT_SCHEDULED -> anchors.scheduledDate?.atTime(DEFAULT_REMINDER_TIME)?.atZone(zone)
    }?.toInstant()
}

/** The presets offered now: the ones the task has a date for and that are still in the future. */
fun availablePresets(
    anchors: ReminderAnchors,
    zone: ZoneId,
    now: Instant,
): List<Pair<ReminderPreset, Instant>> =
    ReminderPreset.entries.mapNotNull { preset -> preset.at(anchors, zone)?.takeIf { it.isAfter(now) }?.let { preset to it } }

data class TaskRemindersUiState(
    val loading: Boolean = false,
    val reminders: List<TaskReminder> = emptyList(),
    val busy: Boolean = false,
    val error: ReminderError? = null,
    /** Set when the user picked a time that has passed. */
    val pastTime: Boolean = false,
)

/** The reminders section of the task detail screen. */
@HiltViewModel
class TaskRemindersViewModel
    @Inject
    constructor(
        private val source: TaskReminderSource,
    ) : ViewModel() {
        private val state = MutableStateFlow(TaskRemindersUiState())
        val uiState: StateFlow<TaskRemindersUiState> = state.asStateFlow()

        /** The clock, replaceable in tests. */
        internal var now: () -> Instant = Instant::now

        private var taskId: UUID? = null

        fun open(id: UUID) {
            if (taskId == id) return
            taskId = id
            state.value = TaskRemindersUiState(loading = true)
            reload()
        }

        fun retry() = reload()

        private fun reload() {
            val id = taskId ?: return
            viewModelScope.launch {
                state.update { it.copy(loading = true) }
                val result = source.list(id)
                state.update { current ->
                    result.fold(
                        { current.copy(loading = false, reminders = it, error = null) },
                        { current.copy(loading = false, error = it.reminderError()) },
                    )
                }
            }
        }

        fun add(at: Instant) {
            val id = taskId ?: return
            if (state.value.busy) return
            if (!at.isAfter(now())) {
                state.update { it.copy(pastTime = true) }
                return
            }
            state.update { it.copy(busy = true, error = null, pastTime = false) }
            viewModelScope.launch {
                val result = source.add(id, at)
                state.update { current ->
                    result.fold(
                        { added ->
                            current.copy(busy = false, reminders = (current.reminders + added).sortedBy { it.remindAt })
                        },
                        { current.copy(busy = false, error = it.reminderError()) },
                    )
                }
            }
        }

        fun remove(reminder: TaskReminder) {
            if (state.value.busy) return
            state.update { it.copy(busy = true, error = null, pastTime = false) }
            viewModelScope.launch {
                val result = source.remove(reminder.id)
                state.update { current ->
                    result.fold(
                        { current.copy(busy = false, reminders = current.reminders.filterNot { it.id == reminder.id }) },
                        { current.copy(busy = false, error = it.reminderError()) },
                    )
                }
            }
        }

        fun dismissError() = state.update { it.copy(error = null, pastTime = false) }
    }

/** The dates of this task the reminder presets use; an unreadable due time counts as none. */
fun GtdTask.reminderAnchors(): ReminderAnchors =
    ReminderAnchors(
        dueDate = dueDate,
        dueTime = dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
        scheduledDate = scheduledDate,
    )

private fun Throwable.reminderError(): ReminderError = (this as? ReminderCallFailure)?.error ?: ReminderError.FAILED
