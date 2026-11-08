package ch.sonensei.cockpit.gtd.lists

import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** How a live list is split into sections: runs of consecutive tasks with the same key. */
class ListSectionTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val day = LocalDate.of(2026, 10, 20)

    private fun task(
        n: Int,
        date: LocalDate?,
    ) = GtdTask(id(n), "Task $n", TaskList.SCHEDULED, n, 1, scheduledDate = date)

    private fun keys(groups: List<Pair<Any?, List<GtdTask>>>) = groups.map { it.first }

    private fun titles(groups: List<Pair<Any?, List<GtdTask>>>) = groups.map { (_, tasks) -> tasks.map { it.title } }

    @Test
    fun `an empty list has no sections`() {
        assertEquals(emptyList<Pair<Any?, List<GtdTask>>>(), emptyList<GtdTask>().groupConsecutiveBy { it.scheduledDate })
    }

    @Test
    fun `a single task is one section`() {
        val groups = listOf(task(1, day)).groupConsecutiveBy { it.scheduledDate }
        assertEquals(listOf<Any?>(day), keys(groups))
        assertEquals(listOf(listOf("Task 1")), titles(groups))
    }

    @Test
    fun `consecutive tasks with the same key share a section, in order`() {
        val tasks = listOf(task(1, day), task(2, day), task(3, day.plusDays(1)), task(4, null), task(5, null))
        val groups = tasks.groupConsecutiveBy { it.scheduledDate }
        assertEquals(listOf(day, day.plusDays(1), null), keys(groups))
        assertEquals(listOf(listOf("Task 1", "Task 2"), listOf("Task 3"), listOf("Task 4", "Task 5")), titles(groups))
    }

    @Test
    fun `a null key is a section of its own, also first`() {
        val groups = listOf(task(1, null), task(2, day)).groupConsecutiveBy { it.scheduledDate }
        assertEquals(listOf(null, day), keys(groups))
    }

    @Test
    fun `equal keys that are not adjacent start a new section, the order keeps sections together`() {
        val groups = listOf(task(1, day), task(2, null), task(3, day)).groupConsecutiveBy { it.scheduledDate }
        assertEquals(listOf(day, null, day), keys(groups))
        assertEquals(listOf(listOf("Task 1"), listOf("Task 2"), listOf("Task 3")), titles(groups))
    }

    @Test
    fun `sorted by scheduled date, every date is exactly one section`() {
        val tasks = listOf(task(1, day.plusDays(2)), task(2, null), task(3, day), task(4, day.plusDays(2)), task(5, day))
        val groups = byScheduledDate(tasks).groupConsecutiveBy { it.scheduledDate }
        assertEquals(listOf(day, day.plusDays(2), null), keys(groups))
        assertEquals(listOf(listOf("Task 3", "Task 5"), listOf("Task 1", "Task 4"), listOf("Task 2")), titles(groups))
        assertEquals(tasks.size, groups.sumOf { it.second.size })
    }
}
