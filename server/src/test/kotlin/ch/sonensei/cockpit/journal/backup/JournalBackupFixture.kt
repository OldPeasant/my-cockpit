package ch.sonensei.cockpit.journal.backup

import ch.sonensei.cockpit.backup.BackupFixture
import ch.sonensei.cockpit.journal.entries.domain.JournalEntryContent
import ch.sonensei.cockpit.journal.entries.domain.JournalEntryService
import ch.sonensei.cockpit.journal.habits.domain.Change
import ch.sonensei.cockpit.journal.habits.domain.HabitChanges
import ch.sonensei.cockpit.journal.habits.domain.HabitSchedule
import ch.sonensei.cockpit.journal.habits.domain.HabitService
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Journal entries with images, a mood, tags (unicode, differing spellings) and inline links, an empty entry without
 * mood, a long entry and one in the Trash; habits of every schedule (one with a reminder, one archived, one without
 * checks) and checks with counts above 1.
 */
class JournalBackupFixture(
	private val journal: JournalEntryService,
	private val habits: HabitService,
) : BackupFixture {
	override val contributor = JournalBackupContributor.ID

	override fun seed() {
		val today = journal.today()

		val run =
			habits
				.create(
					HabitChanges(
						name = Change("Run 5 km «outside»"),
						schedule = Change(HabitSchedule.TIMES_PER_WEEK),
						timesPerWeek = Change(3),
						reminderTime = Change(LocalTime.of(7, 30)),
					),
				).habit
		val water =
			habits.create(HabitChanges(name = Change("Drink water, 8 glasses"), targetCount = Change(8))).habit
		habits.create(
			HabitChanges(
				name = Change("Piano"),
				schedule = Change(HabitSchedule.WEEKDAYS),
				weekdays = Change(listOf(DayOfWeek.SATURDAY, DayOfWeek.MONDAY)),
				archived = Change(true),
			),
		)
		habits.setCheck(run.id, today.minusDays(2), 1)
		habits.setCheck(run.id, today, 1)
		habits.setCheck(water.id, today.minusDays(1), 8)
		habits.setCheck(water.id, today, 3)

		val image = journal.addImage("sunrise over the lake.png", "image/png", PNG.inputStream())
		journal.put(
			today,
			JournalEntryContent(
				bodyMd = "# Good day\n\n![Sunrise](cockpit-file:${image.id})\n\nRan, see [[habit:${run.id}]]\n",
				mood = 4,
				tags = listOf("Travel", "café ☕"),
			),
		) {}
		journal.put(today.minusDays(1), JournalEntryContent()) {}
		journal.put(
			today.minusYears(1),
			JournalEntryContent(bodyMd = "A long day. ".repeat(2_000), mood = 1, tags = listOf("travel")),
		) {}
		journal.put(today.minusDays(3), JournalEntryContent(bodyMd = "to forget", mood = 2)) {}
		journal.delete(today.minusDays(3)) {}
	}

	companion object {
		/** The smallest PNG header; the content is never decoded. */
		private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
	}
}
