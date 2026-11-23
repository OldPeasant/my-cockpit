package ch.sonensei.cockpit.gtd.next

import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** Tasks released from Scheduled today are "new today" in Next and come first. */
class NextNewTodayTest {
    private val today = LocalDate.of(2026, 11, 23)

    private fun task(
        n: Int,
        releasedOn: LocalDate? = null,
    ) = GtdTask(UUID.fromString("00000000-0000-7000-8000-%012d".format(n)), "Task $n", TaskList.NEXT, n, version = 1, releasedOn = releasedOn)

    @Test
    fun `only a Next task released today is new today`() {
        assertTrue(isNewToday(task(1, today), today))
        assertFalse(isNewToday(task(1, today.minusDays(1)), today))
        assertFalse(isNewToday(task(1), today))
        assertFalse(isNewToday(task(1, today).copy(list = TaskList.SCHEDULED), today))
    }

    @Test
    fun `new today come first, both groups keep their order`() {
        val tasks = listOf(task(1), task(2, today), task(3, today.minusDays(1)), task(4, today), task(5))
        assertEquals(listOf(2, 4, 1, 3, 5), newTodayFirst(tasks, today).map { it.position })
    }

    @Test
    fun `from the next day on they sort like any other task`() {
        val tasks = listOf(task(1), task(2, today), task(3))
        assertEquals(listOf(1, 2, 3), newTodayFirst(tasks, today.plusDays(1)).map { it.position })
    }
}
