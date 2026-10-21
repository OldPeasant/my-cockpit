package ch.sonensei.cockpit.journal.habits.api

import ch.sonensei.cockpit.journal.habits.domain.Change
import ch.sonensei.cockpit.journal.habits.domain.HabitChanges
import ch.sonensei.cockpit.journal.habits.domain.HabitCheck
import ch.sonensei.cockpit.journal.habits.domain.HabitSchedule
import ch.sonensei.cockpit.journal.habits.domain.HabitStats
import ch.sonensei.cockpit.journal.habits.domain.HabitView
import ch.sonensei.cockpit.journal.habits.domain.StreakUnit
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Schema(description = "A habit with its state today. `version` is also sent as the ETag.")
data class HabitResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val name: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val schedule: HabitSchedule,
	@get:Schema(description = "Days per week, 1-7; only for TIMES_PER_WEEK") val timesPerWeek: Int?,
	@get:Schema(description = "The due weekdays, in week order; only for WEEKDAYS (else empty)", requiredMode = RequiredMode.REQUIRED)
	val weekdays: List<DayOfWeek>,
	@get:Schema(description = "Count that makes a day done", requiredMode = RequiredMode.REQUIRED) val targetCount: Int,
	@get:Schema(
		description = "Local time (owner's time zone) of the daily reminder on due days, `HH:mm:ss`",
		type = "string",
		format = "time",
		example = "07:30:00",
	)
	val reminderTime: LocalTime?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val archived: Boolean,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val stats: HabitStatsResponse,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(view: HabitView) =
			with(view.habit) {
				HabitResponse(
					id = id,
					name = name,
					schedule = schedule,
					timesPerWeek = timesPerWeek,
					weekdays = weekdays,
					targetCount = targetCount,
					reminderTime = reminderTime,
					archived = archived,
					stats = HabitStatsResponse.of(view.stats),
					createdAt = checkNotNull(createdAt),
					updatedAt = checkNotNull(updatedAt),
					version = version,
				)
			}
	}
}

@Schema(
	description =
		"A habit's state on the owner's today. Streaks count due days done in a row (TIMES_PER_WEEK: weeks with at least " +
			"`timesPerWeek` done days, from the owner's week start); today (this week) does not break a streak while it is " +
			"still open. The 30-day completion covers the last 30 days up to today (today once done), not before the habit existed.",
)
data class HabitStatsResponse(
	@get:Schema(description = "The owner's today", requiredMode = RequiredMode.REQUIRED) val today: LocalDate,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val todayCount: Int,
	@get:Schema(
		description = "Whether the habit is due today (TIMES_PER_WEEK: while this week's quota was not reached before today)",
		requiredMode = RequiredMode.REQUIRED,
	)
	val dueToday: Boolean,
	@get:Schema(description = "todayCount reached targetCount", requiredMode = RequiredMode.REQUIRED) val doneToday: Boolean,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val currentStreak: Int,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val longestStreak: Int,
	@get:Schema(description = "WEEKS for TIMES_PER_WEEK, else DAYS", requiredMode = RequiredMode.REQUIRED) val streakUnit: StreakUnit,
	@get:Schema(description = "Done days in the last 30 days", requiredMode = RequiredMode.REQUIRED) val doneLast30Days: Int,
	@get:Schema(
		description = "Due days in the last 30 days (TIMES_PER_WEEK: timesPerWeek per 7 days, rounded)",
		requiredMode = RequiredMode.REQUIRED,
	)
	val dueLast30Days: Int,
	@get:Schema(description = "doneLast30Days / dueLast30Days, at most 1; `null` without due days", minimum = "0", maximum = "1")
	val completionRateLast30Days: Double?,
) {
	companion object {
		fun of(stats: HabitStats) =
			with(stats) {
				HabitStatsResponse(
					today,
					todayCount,
					dueToday,
					doneToday,
					currentStreak,
					longestStreak,
					streakUnit,
					doneLast30Days,
					dueLast30Days,
					completionRateLast30Days,
				)
			}
	}
}

@Schema(description = "Habits, by name")
data class HabitListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<HabitResponse>,
)

@Schema(description = "How often a habit was done on a date")
data class HabitCheckResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val date: LocalDate,
	@get:Schema(description = "At least 1", requiredMode = RequiredMode.REQUIRED) val count: Int,
) {
	companion object {
		fun of(check: HabitCheck) = HabitCheckResponse(check.date, check.count)
	}
}

@Schema(description = "Checks of a habit, by date; days without a check are not listed")
data class HabitCheckListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<HabitCheckResponse>,
)

@Schema(description = "Sets the count of a day; 0 removes the check")
data class HabitCheckRequest(
	@get:Schema(minimum = "0", maximum = "1000", requiredMode = RequiredMode.REQUIRED) val count: Int? = null,
)

/** Body of create and PATCH: on PATCH only the fields sent change, `null` clears one (or resets it to its default). */
@Schema(
	description =
		"A habit's editable fields. `name` is required on create; `schedule` defaults to DAILY, `targetCount` to 1. " +
			"TIMES_PER_WEEK needs `timesPerWeek`, WEEKDAYS needs `weekdays`; fields that do not fit the schedule are cleared.",
)
data class HabitRequest(
	@get:Schema(maxLength = 200) val name: String? = null,
	val schedule: HabitSchedule? = null,
	@get:Schema(minimum = "1", maximum = "7") val timesPerWeek: Int? = null,
	val weekdays: List<DayOfWeek>? = null,
	@get:Schema(minimum = "1", maximum = "1000") val targetCount: Int? = null,
	@get:Schema(
		description = "Local time of the reminder on due days (`HH:mm` or `HH:mm:ss`, seconds are dropped); `null`: no reminder",
		type = "string",
		format = "time",
		example = "07:30",
	)
	val reminderTime: LocalTime? = null,
	val archived: Boolean? = null,
) {
	/** The changes of the fields in [sent] (all of them for a create). */
	fun toChanges(sent: Set<String>): HabitChanges {
		fun <V> change(
			name: String,
			value: V,
		): Change<V>? = if (name in sent) Change(value) else null
		return HabitChanges(
			name = change("name", name),
			schedule = change("schedule", schedule),
			timesPerWeek = change("timesPerWeek", timesPerWeek),
			weekdays = change("weekdays", weekdays.orEmpty()),
			targetCount = change("targetCount", targetCount),
			reminderTime = change("reminderTime", reminderTime),
			archived = change("archived", archived),
		)
	}

	companion object {
		val FIELDS = setOf("name", "schedule", "timesPerWeek", "weekdays", "targetCount", "reminderTime", "archived")
	}
}
