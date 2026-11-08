package ch.sonensei.cockpit.reminders

import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A task reminder opens the task's detail screen on top of Next; other items open only their screen. */
class OpenItemRequestTaskTest {
    @Test
    fun `a task reminder names the task to open on top of Next`() {
        val request = OpenItemRequest("TASK", itemId(1).toString())

        assertEquals(itemId(1), request.taskId)
        assertEquals(CockpitDestination.Next, request.destination)
    }

    @Test
    fun `a task reminder with an unreadable id opens only Next`() {
        assertNull(OpenItemRequest("TASK", "not-a-uuid").taskId)
        assertNull(OpenItemRequest("TASK", "").taskId)
    }

    @Test
    fun `reminders on other items open no task`() {
        listOf("NOTE", "PROJECT", "HABIT", "EVENT", "task").forEach { type ->
            assertNull(type, OpenItemRequest(type, itemId(2).toString()).taskId)
        }
    }
}
