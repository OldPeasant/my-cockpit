package ch.sonensei.cockpit.calendar.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Which days the overlay marks a task on, in which order, and the whole weeks of the month view. */
class TaskOverlayMarksTest {
    private val monday = LocalDate.parse("2026-10-19")
    private val sunday = monday.plusDays(6)
    private val nextMonday = monday.plusDays(7)

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun task(
        n: Int,
        title: String = "Task $n",
        due: LocalDate? = null,
        dueTime: String? = null,
        scheduled: LocalDate? = null,
        tickler: LocalDate? = null,
    ) = OverlayTask(id(n), title, due, dueTime, scheduled, tickler)

    @Test
    fun `due, scheduled and tickler dates each mark their day, only inside the half-open range`() {
        val tasks =
            listOf(
                task(1, due = monday),
                task(2, scheduled = monday.plusDays(2)),
                task(3, tickler = sunday),
                // Just outside: the day before the range and the (exclusive) end.
                task(4, due = monday.minusDays(1)),
                task(5, scheduled = nextMonday),
                task(6),
            )

        val marks = taskMarks(tasks, monday, nextMonday)

        assertEquals(listOf(monday, monday.plusDays(2), sunday), marks.keys.toList())
        assertEquals(listOf(TaskMarkKind.DUE to id(1)), marks.getValue(monday).map { it.kind to it.task.id })
        assertEquals(listOf(TaskMarkKind.SCHEDULED to id(2)), marks.getValue(monday.plusDays(2)).map { it.kind to it.task.id })
        assertEquals(listOf(TaskMarkKind.TICKLER to id(3)), marks.getValue(sunday).map { it.kind to it.task.id })
    }

    @Test
    fun `a task with several dates is marked once per date, on the same day once per kind`() {
        val both = task(1, due = monday, scheduled = monday, tickler = monday.plusDays(1))

        val marks = taskMarks(listOf(both, both.copy(title = "Duplicate")), monday, nextMonday)

        assertEquals(listOf(TaskMarkKind.DUE, TaskMarkKind.SCHEDULED), marks.getValue(monday).map { it.kind })
        assertEquals(listOf(TaskMarkKind.TICKLER), marks.getValue(monday.plusDays(1)).map { it.kind })
        // The same task twice (e.g. from two server calls) is marked once.
        assertTrue(marks.values.flatten().all { it.task.title == "Task 1" })
        assertEquals(
            setOf("task-DUE-${id(1)}", "task-SCHEDULED-${id(1)}", "task-TICKLER-${id(1)}"),
            marks.values
                .flatten()
                .map { it.key }
                .toSet(),
        )
    }

    @Test
    fun `per day timed due marks come first by time, then by kind and case-insensitive title`() {
        val tasks =
            listOf(
                task(1, "zebra", scheduled = monday),
                task(2, "Late", due = monday, dueTime = "16:00"),
                task(3, "beta", due = monday),
                task(4, "Alpha", due = monday),
                task(5, "Early", due = monday, dueTime = "08:30:15"),
                task(6, "apple", tickler = monday),
                task(7, "Aardvark", scheduled = monday),
            )

        val day = taskMarks(tasks, monday, nextMonday).getValue(monday)

        assertEquals(listOf("Early", "Late", "Alpha", "beta", "Aardvark", "zebra", "apple"), day.map { it.task.title })
        assertEquals(LocalTime.of(8, 30, 15), day[0].time)
        assertEquals(LocalTime.of(16, 0), day[1].time)
        assertNull(day[2].time)
    }

    @Test
    fun `an unreadable due time leaves the task as an untimed due mark`() {
        val day =
            taskMarks(
                listOf(task(1, "Odd", due = monday, dueTime = "25:99"), task(2, "Blank", due = monday, dueTime = "")),
                monday,
                nextMonday,
            ).getValue(monday)

        assertEquals(listOf("Blank", "Odd"), day.map { it.task.title })
        assertTrue(day.all { it.time == null && it.kind == TaskMarkKind.DUE })
    }

    @Test
    fun `only due marks get a time, and an empty or inverted range has no marks`() {
        val scheduledWithTime = task(1, scheduled = monday, dueTime = "09:00")

        assertNull(taskMarks(listOf(scheduledWithTime), monday, nextMonday).getValue(monday).single().time)
        assertTrue(taskMarks(emptyList(), monday, nextMonday).isEmpty())
        assertTrue(taskMarks(listOf(task(2, due = monday)), monday, monday).isEmpty())
        assertTrue(taskMarks(listOf(task(2, due = monday)), nextMonday, monday).isEmpty())
    }

    @Test
    fun `the month grid is whole weeks from the week of the 1st to the week of the last day`() {
        // October 2026 starts on a Thursday and ends on a Saturday.
        val october = monthGrid(LocalDate.parse("2026-10-21"), DayOfWeek.MONDAY)
        assertEquals(LocalDate.parse("2026-09-28"), october.first())
        assertEquals(LocalDate.parse("2026-11-01"), october.last())
        assertEquals(35, october.size)

        val octoberSunday = monthGrid(LocalDate.parse("2026-10-01"), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.parse("2026-09-27"), octoberSunday.first())
        assertEquals(LocalDate.parse("2026-10-31"), octoberSunday.last())
    }

    @Test
    fun `the month grid has 4 to 6 weeks and consecutive days`() {
        // February 2027 starts on a Monday and has 28 days: exactly 4 weeks.
        val february = monthGrid(LocalDate.parse("2027-02-14"), DayOfWeek.MONDAY)
        assertEquals(LocalDate.parse("2027-02-01") to LocalDate.parse("2027-02-28"), february.first() to february.last())
        assertEquals(28, february.size)

        // August 2026 starts on a Saturday and has 31 days: 6 weeks.
        val august = monthGrid(LocalDate.parse("2026-08-31"), DayOfWeek.MONDAY)
        assertEquals(LocalDate.parse("2026-07-27") to LocalDate.parse("2026-09-06"), august.first() to august.last())
        assertEquals(42, august.size)

        for (firstDay in DayOfWeek.entries) {
            for (month in 1..12) {
                val grid = monthGrid(LocalDate.of(2026, month, 15), firstDay)
                assertTrue("$month/$firstDay: ${grid.size}", grid.size in listOf(28, 35, 42))
                assertEquals(firstDay, grid.first().dayOfWeek)
                assertTrue(grid.zipWithNext().all { (a, b) -> b == a.plusDays(1) })
                assertTrue(LocalDate.of(2026, month, 1) in grid)
                assertTrue(LocalDate.of(2026, month, 1).withDayOfMonth(LocalDate.of(2026, month, 1).lengthOfMonth()) in grid)
            }
        }
    }

    @Test
    fun `a task released to Next today is scheduled on today, only when today is shown`() {
        val today = monday.plusDays(2)
        val released = OverlayTask(id(1), "Released", releasedOn = today)
        val releasedEarlier = OverlayTask(id(2), "Earlier", releasedOn = monday)
        // Still scheduled (not yet released): its scheduled date wins, no second mark.
        val both = OverlayTask(id(3), "Both", scheduledDate = sunday, releasedOn = today)

        val marks = taskMarks(listOf(released, releasedEarlier, both), monday, nextMonday, today)

        assertEquals(listOf(today, sunday), marks.keys.toList())
        assertEquals(listOf(id(1)), marks.getValue(today).map { it.task.id })
        assertEquals(TaskMarkKind.SCHEDULED, marks.getValue(today).single().kind)
        assertEquals(listOf(id(3)), marks.getValue(sunday).map { it.task.id })
        // Today outside the range, or not known: no mark.
        assertTrue(taskMarks(listOf(released), nextMonday, nextMonday.plusDays(7), today).isEmpty())
        assertTrue(taskMarks(listOf(released), monday, nextMonday).isEmpty())
    }
}
