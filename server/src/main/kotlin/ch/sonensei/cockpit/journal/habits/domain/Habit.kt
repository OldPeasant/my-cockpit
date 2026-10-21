package ch.sonensei.cockpit.journal.habits.domain

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** When a habit is due (SPEC.md §8.5). */
enum class HabitSchedule {
	/** Every day. */
	DAILY,

	/** On `timesPerWeek` days of each week, any days. */
	TIMES_PER_WEEK,

	/** On the given weekdays. */
	WEEKDAYS,
}

/**
 * A habit (SPEC.md §8.5). A day is done when its check reaches [targetCount]. [reminderTime] is a local time in the
 * owner's time zone: [HabitService] keeps one reminder for the next due day. [weekdays] is only set for
 * [HabitSchedule.WEEKDAYS], [timesPerWeek] only for [HabitSchedule.TIMES_PER_WEEK].
 */
@Entity
@Table(name = "habit")
class Habit(
	@Column(name = "name", nullable = false)
	var name: String,
	@Enumerated(EnumType.STRING)
	@Column(name = "schedule", nullable = false)
	var schedule: HabitSchedule = HabitSchedule.DAILY,
	id: UUID? = null,
) : BaseEntity(id) {
	@Column(name = "times_per_week")
	var timesPerWeek: Int? = null

	@Convert(converter = WeekdaysConverter::class)
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "weekdays", nullable = false)
	var weekdays: List<DayOfWeek> = emptyList()

	@Column(name = "target_count", nullable = false)
	var targetCount: Int = 1

	@Column(name = "reminder_time")
	var reminderTime: LocalTime? = null

	@Column(name = "archived", nullable = false)
	var archived: Boolean = false

	val rule: HabitRule get() = HabitRule(schedule, timesPerWeek, weekdays.toSet(), targetCount)
}

/** How often a habit was done on [date]; at least 1 (a count of 0 is no check). */
@Entity
@Table(name = "habit_check")
class HabitCheck(
	@Column(name = "habit_id", nullable = false, updatable = false)
	val habitId: UUID,
	@Column(name = "check_date", nullable = false, updatable = false)
	val date: LocalDate,
	@Column(name = "count", nullable = false)
	var count: Int,
	id: UUID? = null,
) : BaseEntity(id)

private val weekdaysJson: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

/** Weekdays as a JSON array of [DayOfWeek] names, in week order. */
@Converter
class WeekdaysConverter : AttributeConverter<List<DayOfWeek>, String> {
	override fun convertToDatabaseColumn(attribute: List<DayOfWeek>?): String =
		weekdaysJson.writeValueAsString(
			attribute
				.orEmpty()
				.distinct()
				.sorted()
				.map { it.name },
		)

	override fun convertToEntityAttribute(dbData: String?): List<DayOfWeek> =
		dbData?.let { weekdaysJson.readValue<List<String>>(it).map(DayOfWeek::valueOf) } ?: emptyList()
}
