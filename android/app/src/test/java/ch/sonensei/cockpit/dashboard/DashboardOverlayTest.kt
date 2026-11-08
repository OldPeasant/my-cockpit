package ch.sonensei.cockpit.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** The item the dashboard shows on top of itself survives a configuration change as text. */
class DashboardOverlayTest {
    private val id = UUID.fromString("00000000-0000-7000-8000-000000000042")

    @Test
    fun `a note and a contact survive the round trip through their text`() {
        listOf(DashboardOverlay.Note(id), DashboardOverlay.Contact(id)).forEach {
            assertEquals(it, DashboardOverlay.parse(it.encode()))
        }
        assertEquals("note:$id", DashboardOverlay.Note(id).encode())
        assertEquals("contact:$id", DashboardOverlay.Contact(id).encode())
    }

    @Test
    fun `unreadable text opens nothing`() {
        listOf("", "note", "note:", "note:not-a-uuid", "event:$id", ":$id", "$id").forEach {
            assertNull(it, DashboardOverlay.parse(it))
        }
    }
}
