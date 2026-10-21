package ch.sonensei.cockpit.gtd.next

import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class NextFilterTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val home = id(80)
    private val phone = id(81)
    private val errands = id(82)
    private val bare = GtdTask(id(1), "Sort the photos", TaskList.NEXT, 0, 1)
    private val full =
        bare.copy(
            contextIds = listOf(home, phone),
            energy = Energy.MEDIUM,
            creativity = Creativity.MIXED,
            timeEstimateMin = 30,
        )

    @Test
    fun `no filter matches every task, also one without attributes`() {
        assertTrue(NextFilter().matches(bare))
        assertTrue(NextFilter().matches(full))
        assertEquals(0, NextFilter().activeCount)
    }

    @Test
    fun `a context filter matches any of the task's contexts`() {
        assertTrue(NextFilter(contextId = home).matches(full))
        assertTrue(NextFilter(contextId = phone).matches(full))
        assertFalse(NextFilter(contextId = errands).matches(full))
        assertFalse(NextFilter(contextId = home).matches(bare))
    }

    @Test
    fun `energy and creativity must match exactly and are not ranges`() {
        assertTrue(NextFilter(energy = Energy.MEDIUM).matches(full))
        assertFalse(NextFilter(energy = Energy.HIGH).matches(full))
        assertFalse(NextFilter(energy = Energy.LOW).matches(full))
        assertFalse(NextFilter(energy = Energy.MEDIUM).matches(bare))
        assertTrue(NextFilter(creativity = Creativity.MIXED).matches(full))
        assertFalse(NextFilter(creativity = Creativity.CREATIVE).matches(full))
        assertFalse(NextFilter(creativity = Creativity.MIXED).matches(bare))
    }

    @Test
    fun `the estimate filter is inclusive and needs an estimate`() {
        assertTrue(NextFilter(maxMinutes = 30).matches(full))
        assertTrue(NextFilter(maxMinutes = 60).matches(full))
        assertFalse(NextFilter(maxMinutes = 15).matches(full))
        assertFalse(NextFilter(maxMinutes = 120).matches(bare))
        assertTrue(NextFilter(maxMinutes = 5).matches(bare.copy(timeEstimateMin = 0)))
    }

    @Test
    fun `combined filters need every one to match`() {
        val all = NextFilter(contextId = phone, energy = Energy.MEDIUM, creativity = Creativity.MIXED, maxMinutes = 30)
        assertEquals(4, all.activeCount)
        assertTrue(all.matches(full))
        assertFalse(all.copy(maxMinutes = 15).matches(full))
        assertFalse(all.copy(contextId = errands).matches(full))
        assertFalse(all.copy(energy = Energy.LOW).matches(full))
        assertFalse(all.copy(creativity = Creativity.ROUTINE).matches(full))
    }

    @Test
    fun `the estimate quick picks are those of the spec`() {
        assertEquals(listOf(5, 15, 30, 60, 120), NextFilter.ESTIMATES)
    }
}
