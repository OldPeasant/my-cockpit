package ch.sonensei.cockpit.habits

import ch.sonensei.cockpit.core.network.generated.apis.HabitsApi
import ch.sonensei.cockpit.core.network.generated.models.HabitCheckRequest
import ch.sonensei.cockpit.core.network.generated.models.HabitResponse
import ch.sonensei.cockpit.core.network.generated.models.HabitStatsResponse
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.IOException
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** How a habit is due (SPEC.md §8.5). */
enum class HabitSchedule { DAILY, TIMES_PER_WEEK, WEEKDAYS }

/** A habit with its state on the owner's today, as the server computed it. */
data class Habit(
    val id: UUID,
    val name: String,
    val schedule: HabitSchedule,
    /** Days per week, only for [HabitSchedule.TIMES_PER_WEEK]. */
    val timesPerWeek: Int?,
    /** The due weekdays, in week order, only for [HabitSchedule.WEEKDAYS]. */
    val weekdays: List<DayOfWeek>,
    /** Count that makes a day done. */
    val targetCount: Int,
    /** The server's today (in the owner's time zone): the day a check-off goes to. */
    val today: LocalDate,
    val todayCount: Int,
    val dueToday: Boolean,
    val doneToday: Boolean,
    val currentStreak: Int,
    /** Whether [currentStreak] and [longestStreak] count days or (for [HabitSchedule.TIMES_PER_WEEK]) weeks. */
    val streakInWeeks: Boolean,
    /** Done days / due days in the last 30 days; `null` without due days. */
    val completionLast30Days: Double?,
    /** The best streak so far, in the unit of [currentStreak]. */
    val longestStreak: Int = 0,
    /** Local time of the daily reminder on due days; `null`: no reminder. */
    val reminderTime: LocalTime? = null,
    val archived: Boolean = false,
    /** Sent as `If-Match` with a change. */
    val version: Long = 0,
) {
    /** The count one more tap sets: one more check until the target is reached, then back to none (as on the web). */
    val nextCount: Int get() = if (todayCount >= targetCount) 0 else todayCount + 1
}

/** What keeps a [HabitDraft] from being saved. */
enum class HabitDraftProblem { NAME, NAME_TOO_LONG, WEEKDAYS, TIMES_PER_WEEK, TARGET }

/** The editable fields of a habit, as the create/edit form holds them. */
data class HabitDraft(
    val name: String = "",
    val schedule: HabitSchedule = HabitSchedule.DAILY,
    /** Kept while another schedule is picked, so switching back does not lose it. */
    val timesPerWeek: Int = DEFAULT_TIMES_PER_WEEK,
    val weekdays: Set<DayOfWeek> = DEFAULT_WEEKDAYS,
    val targetCount: Int = 1,
    val reminderTime: LocalTime? = null,
) {
    /** Why the draft cannot be saved, or `null` when it can (the server's limits). */
    val problem: HabitDraftProblem?
        get() =
            when {
                name.isBlank() -> HabitDraftProblem.NAME
                name.trim().length > MAX_NAME -> HabitDraftProblem.NAME_TOO_LONG
                schedule == HabitSchedule.WEEKDAYS && weekdays.isEmpty() -> HabitDraftProblem.WEEKDAYS
                schedule == HabitSchedule.TIMES_PER_WEEK && timesPerWeek !in 1..DAYS_PER_WEEK -> HabitDraftProblem.TIMES_PER_WEEK
                targetCount !in 1..MAX_TARGET -> HabitDraftProblem.TARGET
                else -> null
            }

    /**
     * The request body: every editable field, so an edit sets them all. Only the fields of the schedule are sent (the
     * server clears the others); no reminder is sent as `null`, which removes it.
     */
    internal fun toJson(): JsonObject {
        val fields =
            mutableMapOf<String, JsonElement>(
                "name" to JsonPrimitive(name.trim()),
                "schedule" to JsonPrimitive(schedule.name),
                "targetCount" to JsonPrimitive(targetCount),
                "reminderTime" to (reminderTime?.let { JsonPrimitive(it.format(HH_MM)) } ?: JsonNull),
            )
        when (schedule) {
            HabitSchedule.TIMES_PER_WEEK -> fields["timesPerWeek"] = JsonPrimitive(timesPerWeek)
            HabitSchedule.WEEKDAYS -> fields["weekdays"] = JsonArray(weekdays.sorted().map { JsonPrimitive(it.name) })
            HabitSchedule.DAILY -> Unit
        }
        return JsonObject(fields)
    }

    companion object {
        const val MAX_NAME = 200
        const val MAX_TARGET = 1000
        const val DAYS_PER_WEEK = 7
        private const val DEFAULT_TIMES_PER_WEEK = 3
        private val DEFAULT_WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

        /** The form's starting values for editing [habit]. */
        fun of(habit: Habit) =
            HabitDraft(
                name = habit.name,
                schedule = habit.schedule,
                timesPerWeek = habit.timesPerWeek ?: DEFAULT_TIMES_PER_WEEK,
                weekdays = habit.weekdays.toSet().ifEmpty { DEFAULT_WEEKDAYS },
                targetCount = habit.targetCount,
                reminderTime = habit.reminderTime,
            )
    }
}

internal fun HabitResponse.toHabit() =
    Habit(
        id = id,
        name = name,
        schedule = HabitSchedule.valueOf(schedule.value),
        timesPerWeek = timesPerWeek,
        weekdays = weekdays.map { DayOfWeek.valueOf(it.value) },
        targetCount = targetCount,
        today = stats.today,
        todayCount = stats.todayCount,
        dueToday = stats.dueToday,
        doneToday = stats.doneToday,
        currentStreak = stats.currentStreak,
        streakInWeeks = stats.streakUnit == HabitStatsResponse.StreakUnit.WEEKS,
        completionLast30Days = stats.completionRateLast30Days,
        longestStreak = stats.longestStreak,
        reminderTime = reminderTime?.let { LocalTime.parse(it) },
        archived = archived,
        version = version,
    )

@Module
@InstallIn(SingletonComponent::class)
object HabitsModule {
    @Provides
    fun habitsApi(retrofit: Retrofit): HabitsApi = retrofit.create(HabitsApi::class.java)

    @Provides
    fun habitsWriteApi(retrofit: Retrofit): HabitsWriteApi = retrofit.create(HabitsWriteApi::class.java)
}

/**
 * Creates and changes habits with exactly the given fields: the generated client sends every unset field as `null`,
 * which the server takes as "clear it" (a PATCH changes the fields it names).
 */
interface HabitsWriteApi {
    @POST("api/v1/habits")
    suspend fun createHabit(
        @Body body: JsonObject,
    ): Response<HabitResponse>

    @PATCH("api/v1/habits/{id}")
    suspend fun updateHabit(
        @Path("id") id: UUID,
        @Body patch: JsonObject,
        @Header("If-Match") ifMatch: String,
    ): Response<HabitResponse>
}

/** The outcome of a call. */
sealed interface HabitsResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : HabitsResult<T>

    /** No connection (or the server is down): nothing changed. */
    data object Offline : HabitsResult<Nothing>

    /** The server refused it; [status] is `null` for an unreadable answer. */
    data class Failed(
        val status: Int?,
    ) : HabitsResult<Nothing>
}

/** Habits on the server: no local cache, the screen loads what it shows. */
@Singleton
class HabitsRepository
    @Inject
    constructor(
        private val api: HabitsApi,
        private val writeApi: HabitsWriteApi,
    ) {
        /** The active (or the [archived]) habits, by name (the server's order). */
        suspend fun list(archived: Boolean = false): HabitsResult<List<Habit>> =
            call { body(api.listHabits(archived = archived)).items.map { it.toHabit() } }

        /**
         * One tap on [habit]: one more check for its today until the target is reached, then back to none
         * ([Habit.nextCount]); returns the habit with its new stats.
         */
        suspend fun check(habit: Habit): HabitsResult<Habit> =
            call { body(api.setHabitCheck(habit.id, habit.today, HabitCheckRequest(habit.nextCount))).toHabit() }

        /** Creates a habit from a valid [draft]. */
        suspend fun create(draft: HabitDraft): HabitsResult<Habit> = call { body(writeApi.createHabit(draft.toJson())).toHabit() }

        /** Sets the editable fields of [habit] to a valid [draft]; a status in [CONFLICTS] means it changed elsewhere. */
        suspend fun update(
            habit: Habit,
            draft: HabitDraft,
        ): HabitsResult<Habit> = patch(habit, draft.toJson())

        /** Archives [habit], or moves it back to the active habits. */
        suspend fun setArchived(
            habit: Habit,
            archived: Boolean,
        ): HabitsResult<Habit> = patch(habit, JsonObject(mapOf("archived" to JsonPrimitive(archived))))

        private suspend fun patch(
            habit: Habit,
            fields: Map<String, JsonElement>,
        ): HabitsResult<Habit> = call { body(writeApi.updateHabit(habit.id, JsonObject(fields), etag(habit.version))).toHabit() }

        private suspend fun <T> call(block: suspend () -> T): HabitsResult<T> =
            try {
                HabitsResult.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                HabitsResult.Offline
            } catch (e: HabitsHttpFailure) {
                if (e.status >= SERVER_ERROR) HabitsResult.Offline else HabitsResult.Failed(e.status)
            } catch (_: SerializationException) {
                HabitsResult.Failed(null)
            } catch (_: IllegalArgumentException) {
                HabitsResult.Failed(null)
            } catch (_: DateTimeException) {
                HabitsResult.Failed(null)
            }

        companion object {
            /** The habit changed (or was deleted) elsewhere since it was loaded. */
            val CONFLICTS = setOf(404, 409, 412)
            private const val SERVER_ERROR = 500
        }
    }

private fun etag(version: Long) = "\"$version\""

/** A non-2xx answer; its message carries only the status. */
private class HabitsHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw HabitsHttpFailure(response.code())
    return body
}
