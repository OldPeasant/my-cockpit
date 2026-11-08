package ch.sonensei.cockpit.habits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** What keeps the create/edit form from saving, and its starting values for an edit. */
class HabitDraftTest {
    private val valid = HabitDraft(name = "Read")

    private fun habit(
        schedule: HabitSchedule = HabitSchedule.DAILY,
        timesPerWeek: Int? = null,
        weekdays: List<DayOfWeek> = emptyList(),
    ) = Habit(
        UUID.fromString("00000000-0000-7000-8000-000000000001"),
        "Stretch",
        schedule,
        timesPerWeek,
        weekdays,
        4,
        LocalDate.of(2026, 10, 22),
        1,
        true,
        false,
        0,
        false,
        null,
        reminderTime = LocalTime.of(6, 45),
    )

    @Test
    fun `a named daily habit can be saved`() {
        assertNull(valid.problem)
        assertNull(HabitDraft().copy(name = "x").problem)
    }

    @Test
    fun `a blank name is a problem`() {
        assertEquals(HabitDraftProblem.NAME, HabitDraft().problem)
        assertEquals(HabitDraftProblem.NAME, valid.copy(name = "   ").problem)
    }

    @Test
    fun `the name may have 200 characters after trimming, not 201`() {
        assertNull(valid.copy(name = "a".repeat(200)).problem)
        assertNull(valid.copy(name = "  " + "a".repeat(200) + "  ").problem)
        assertEquals(HabitDraftProblem.NAME_TOO_LONG, valid.copy(name = "a".repeat(201)).problem)
    }

    @Test
    fun `the weekday schedule needs at least one day, other schedules ignore the days`() {
        assertEquals(HabitDraftProblem.WEEKDAYS, valid.copy(schedule = HabitSchedule.WEEKDAYS, weekdays = emptySet()).problem)
        assertNull(valid.copy(schedule = HabitSchedule.WEEKDAYS, weekdays = setOf(DayOfWeek.SUNDAY)).problem)
        assertNull(valid.copy(schedule = HabitSchedule.DAILY, weekdays = emptySet()).problem)
    }

    @Test
    fun `days a week must be between 1 and 7 for the per-week schedule only`() {
        val perWeek = valid.copy(schedule = HabitSchedule.TIMES_PER_WEEK)
        assertEquals(HabitDraftProblem.TIMES_PER_WEEK, perWeek.copy(timesPerWeek = 0).problem)
        assertNull(perWeek.copy(timesPerWeek = 1).problem)
        assertNull(perWeek.copy(timesPerWeek = 7).problem)
        assertEquals(HabitDraftProblem.TIMES_PER_WEEK, perWeek.copy(timesPerWeek = 8).problem)
        assertNull(valid.copy(timesPerWeek = 8).problem)
    }

    @Test
    fun `times a day must be between 1 and 1000`() {
        assertEquals(HabitDraftProblem.TARGET, valid.copy(targetCount = 0).problem)
        assertNull(valid.copy(targetCount = 1).problem)
        assertNull(valid.copy(targetCount = 1000).problem)
        assertEquals(HabitDraftProblem.TARGET, valid.copy(targetCount = 1001).problem)
    }

    @Test
    fun `the name problem comes first when several fields are wrong`() {
        val draft = HabitDraft(name = "", schedule = HabitSchedule.WEEKDAYS, weekdays = emptySet(), targetCount = 0)
        assertEquals(HabitDraftProblem.NAME, draft.problem)
        assertEquals(HabitDraftProblem.WEEKDAYS, draft.copy(name = "Read").problem)
    }

    @Test
    fun `an edit starts with the habit's values`() {
        val draft = HabitDraft.of(habit(HabitSchedule.WEEKDAYS, weekdays = listOf(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY)))

        assertEquals("Stretch", draft.name)
        assertEquals(HabitSchedule.WEEKDAYS, draft.schedule)
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY), draft.weekdays)
        assertEquals(4, draft.targetCount)
        assertEquals(LocalTime.of(6, 45), draft.reminderTime)
        assertNull(draft.problem)
    }

    @Test
    fun `an edit keeps the days a week of a per-week habit`() {
        assertEquals(5, HabitDraft.of(habit(HabitSchedule.TIMES_PER_WEEK, timesPerWeek = 5)).timesPerWeek)
    }

    @Test
    fun `an edit of a daily habit offers the default days for a switch to another schedule`() {
        val draft = HabitDraft.of(habit())

        assertEquals(HabitDraft().timesPerWeek, draft.timesPerWeek)
        assertEquals(HabitDraft().weekdays, draft.weekdays)
        assertNull(draft.copy(schedule = HabitSchedule.WEEKDAYS).problem)
        assertNull(draft.copy(schedule = HabitSchedule.TIMES_PER_WEEK).problem)
    }
}
