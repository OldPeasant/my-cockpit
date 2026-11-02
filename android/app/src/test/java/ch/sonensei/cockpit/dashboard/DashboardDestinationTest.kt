package ch.sonensei.cockpit.dashboard

import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Where a tap on a Dashboard card goes, given the card's data: the Inbox card goes to Mail when only mail waits. */
class DashboardDestinationTest {
    private fun inbox(
        tasks: Count,
        unreadMail: Count,
    ) = WidgetData.Inbox(tasks, unreadMail)

    @Test
    fun `an empty Inbox with unread mail opens Mail`() {
        assertEquals(
            CockpitDestination.Mail,
            WidgetId.INBOX.destinationFor(inbox(Count(0, more = false), Count(26, more = true))),
        )
    }

    @Test
    fun `an empty Inbox with exactly one unread mail opens Mail`() {
        assertEquals(
            CockpitDestination.Mail,
            WidgetId.INBOX.destinationFor(inbox(Count(0, more = false), Count(1, more = false))),
        )
    }

    @Test
    fun `an empty Inbox without unread mail opens the GTD Inbox`() {
        assertEquals(
            CockpitDestination.Inbox,
            WidgetId.INBOX.destinationFor(inbox(Count(0, more = false), Count(0, more = false))),
        )
    }

    @Test
    fun `an Inbox with tasks opens the GTD Inbox, with or without unread mail`() {
        assertEquals(
            CockpitDestination.Inbox,
            WidgetId.INBOX.destinationFor(inbox(Count(1, more = false), Count(26, more = true))),
        )
        assertEquals(
            CockpitDestination.Inbox,
            WidgetId.INBOX.destinationFor(inbox(Count(50, more = true), Count(3, more = false))),
        )
        assertEquals(
            CockpitDestination.Inbox,
            WidgetId.INBOX.destinationFor(inbox(Count(4, more = false), Count(0, more = false))),
        )
    }

    @Test
    fun `an Inbox not loaded yet or whose load failed without data opens the GTD Inbox`() {
        // WidgetState.data is null while the first load runs and when it failed before any data arrived.
        assertEquals(CockpitDestination.Inbox, WidgetId.INBOX.destinationFor(null))
        assertEquals(CockpitDestination.Inbox, WidgetId.INBOX.destinationFor(WidgetState().data))
        assertEquals(
            CockpitDestination.Inbox,
            WidgetId.INBOX.destinationFor(WidgetState(loading = false, error = WidgetError.FAILED).data),
        )
        assertEquals(
            CockpitDestination.Inbox,
            WidgetId.INBOX.destinationFor(WidgetState(loading = false, error = WidgetError.OFFLINE).data),
        )
    }

    @Test
    fun `a failed refresh follows the data it kept`() {
        // A failed or offline refresh keeps the last loaded data in WidgetState; the card follows what it shows.
        val mailOnly = inbox(Count(0, more = false), Count(26, more = true))
        WidgetError.entries.forEach { error ->
            val stale = WidgetState(mailOnly, loading = false, error = error)
            assertEquals(error.name, CockpitDestination.Mail, WidgetId.INBOX.destinationFor(stale.data))
            val staleTasks = WidgetState(inbox(Count(2, more = false), Count(26, more = true)), loading = false, error = error)
            assertEquals(error.name, CockpitDestination.Inbox, WidgetId.INBOX.destinationFor(staleTasks.data))
        }
    }

    @Test
    fun `the other cards keep their destination, loading or loaded`() {
        val loaded =
            mapOf(
                WidgetId.TODAY to (CockpitDestination.Today to WidgetData.Today(emptyList(), emptyList(), 0, 0)),
                WidgetId.UPCOMING to (CockpitDestination.Calendar to WidgetData.Upcoming(emptyList())),
                WidgetId.WAITING_OVERDUE to (CockpitDestination.Waiting to WidgetData.WaitingOverdue(emptyList())),
                WidgetId.STALLED_PROJECTS to (CockpitDestination.Projects to WidgetData.StalledProjects(emptyList())),
                WidgetId.WEEKLY_REVIEW to (CockpitDestination.Review to WidgetData.WeeklyReview(null, null)),
                WidgetId.READ_LATER to (CockpitDestination.Bookmarks to WidgetData.ReadLater(emptyList(), more = false)),
                WidgetId.PINNED_NOTES to (CockpitDestination.Notes to WidgetData.PinnedNotes(emptyList(), more = false)),
            )
        assertEquals(WidgetId.entries.toSet() - WidgetId.INBOX, loaded.keys)
        loaded.forEach { (widget, expected) ->
            val (destination, data) = expected
            assertEquals(widget.name, destination, widget.destination)
            assertEquals(widget.name, destination, widget.destinationFor(null))
            assertEquals(widget.name, destination, widget.destinationFor(data))
        }
    }

    @Test
    fun `only the Inbox card redirects to Mail, even if another card got Inbox data`() {
        val mailOnly = inbox(Count(0, more = false), Count(26, more = true))
        (WidgetId.entries - WidgetId.INBOX).forEach { widget ->
            assertEquals(widget.name, widget.destination, widget.destinationFor(mailOnly))
        }
    }

    @Test
    fun `the Inbox card keeps the Inbox icon, not the Mail one`() {
        // WidgetCard draws widget.destination.icon, which does not depend on the data.
        assertEquals(CockpitDestination.Inbox, WidgetId.INBOX.destination)
        assertEquals(CockpitDestination.Inbox.icon, WidgetId.INBOX.destination.icon)
        assertNotEquals(CockpitDestination.Mail.icon, WidgetId.INBOX.destination.icon)
    }
}
