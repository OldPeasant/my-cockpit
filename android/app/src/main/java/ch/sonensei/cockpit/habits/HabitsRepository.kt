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
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
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
    /** Whether [currentStreak] counts days or (for [HabitSchedule.TIMES_PER_WEEK]) weeks. */
    val streakInWeeks: Boolean,
    /** Done days / due days in the last 30 days; `null` without due days. */
    val completionLast30Days: Double?,
)

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
    )

@Module
@InstallIn(SingletonComponent::class)
object HabitsModule {
    @Provides
    fun habitsApi(retrofit: Retrofit): HabitsApi = retrofit.create(HabitsApi::class.java)
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
    ) {
        /** The active habits, by name (the server's order). */
        suspend fun list(): HabitsResult<List<Habit>> = call { body(api.listHabits(archived = false)).items.map { it.toHabit() } }

        /**
         * Checks [habit] off for its today ([done]: the full target count, the server's one-tap check) or removes
         * today's check; returns the habit with its new stats.
         */
        suspend fun check(
            habit: Habit,
            done: Boolean,
        ): HabitsResult<Habit> =
            call {
                body(api.setHabitCheck(habit.id, habit.today, HabitCheckRequest(if (done) habit.targetCount else 0))).toHabit()
            }

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

        private companion object {
            const val SERVER_ERROR = 500
        }
    }

/** A non-2xx answer; its message carries only the status. */
private class HabitsHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw HabitsHttpFailure(response.code())
    return body
}
