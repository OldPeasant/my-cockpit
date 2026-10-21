package ch.sonensei.cockpit.gtd.inbox

import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** The pure rules of processing: which PATCH a clarification makes, and when a prompt may run. */
class InboxActionsTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val home = id(80)
    private val phone = id(81)
    private val today = LocalDate.of(2026, 10, 20)

    @Test
    fun `an unchanged clarification makes no patch`() {
        val before = Clarification(Energy.LOW, Creativity.MIXED, 30, listOf(home, phone))

        assertNull(before.copy().patchFrom(before))
    }

    @Test
    fun `reordering the same contexts makes no patch`() {
        val before = Clarification(contextIds = listOf(home, phone))

        assertNull(Clarification(contextIds = listOf(phone, home)).patchFrom(before))
    }

    @Test
    fun `removing the last context sends an empty list, not null`() {
        val patch = Clarification().patchFrom(Clarification(contextIds = listOf(home)))

        assertEquals(Json.parseToJsonElement("""{"contextIds":[]}"""), patch)
    }

    @Test
    fun `each changed attribute is sent, cleared ones as null`() {
        val before = Clarification(Energy.HIGH, Creativity.CREATIVE, 120, emptyList())
        val after = Clarification(null, Creativity.ROUTINE, null, listOf(home))

        assertEquals(
            Json.parseToJsonElement("""{"energy":null,"creativity":"ROUTINE","timeEstimateMin":null,"contextIds":["$home"]}"""),
            after.patchFrom(before),
        )
    }

    @Test
    fun `the clarification of a task is its own attributes`() {
        val task =
            GtdTask(
                id(1),
                "Call the plumber",
                TaskList.INBOX,
                0,
                1,
                energy = Energy.MEDIUM,
                creativity = Creativity.ROUTINE,
                timeEstimateMin = 5,
                contextIds = listOf(phone),
            )

        assertEquals(Clarification(Energy.MEDIUM, Creativity.ROUTINE, 5, listOf(phone)), Clarification.of(task))
    }

    @Test
    fun `the tickler needs a day after today`() {
        assertFalse(InboxPrompt(InboxActionKind.TICKLER, today.minusDays(1)).isValid(today))
        assertFalse(InboxPrompt(InboxActionKind.TICKLER, today).isValid(today))
        assertTrue(InboxPrompt(InboxActionKind.TICKLER, today.plusDays(1)).isValid(today))
    }

    @Test
    fun `to project needs an existing project or a non-blank new title`() {
        assertTrue(InboxPrompt(InboxActionKind.PROJECT, today, projectId = id(50)).isValid(today))
        assertTrue(InboxPrompt(InboxActionKind.PROJECT, today, text = "Kitchen").isValid(today))
        assertFalse(InboxPrompt(InboxActionKind.PROJECT, today, text = "").isValid(today))
        assertFalse(InboxPrompt(InboxActionKind.PROJECT, today, text = " \t ").isValid(today))
    }

    @Test
    fun `waiting and schedule always run`() {
        assertTrue(InboxPrompt(InboxActionKind.WAITING, today).isValid(today))
        assertTrue(InboxPrompt(InboxActionKind.SCHEDULE, today).isValid(today))
    }

    @Test
    fun `an existing project wins over a typed title`() {
        val prompt = InboxPrompt(InboxActionKind.PROJECT, today, text = "Kitchen", projectId = id(50))

        assertEquals(InboxAction.ToProject(ProjectChoice.Existing(id(50))), prompt.action())
    }

    @Test(expected = IllegalStateException::class)
    fun `a direct action has no prompt`() {
        InboxPrompt(InboxActionKind.DONE, today).action()
    }
}
