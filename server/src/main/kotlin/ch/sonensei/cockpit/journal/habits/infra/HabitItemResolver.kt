package ch.sonensei.cockpit.journal.habits.infra

import ch.sonensei.cockpit.core.items.domain.ItemResolver
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.journal.habits.domain.Habit
import ch.sonensei.cockpit.journal.habits.domain.HabitSchedule
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/**
 * Habits in generic views and reminders (SPEC.md §4.3): the name, the schedule as subtitle ("Daily", "3× per week",
 * "Mon, Wed, Fri"; archived ones say so), deep link `/habits/{id}`.
 */
@Component
class HabitItemResolver(
	private val habits: HabitRepository,
) : ItemResolver {
	override val type = ItemType.HABIT

	@Transactional(readOnly = true)
	override fun resolve(id: UUID): ResolvedItem? =
		habits.findById(id).orElse(null)?.let {
			ResolvedItem(
				title = it.name,
				subtitle = if (it.archived) "Archived habit" else describe(it),
				icon = "repeat",
				deepLink = "/habits/${it.id}",
			)
		}

	private fun describe(habit: Habit): String =
		when (habit.schedule) {
			HabitSchedule.DAILY -> "Daily"
			HabitSchedule.TIMES_PER_WEEK -> "${habit.timesPerWeek}× per week"
			HabitSchedule.WEEKDAYS -> habit.weekdays.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
		}
}
