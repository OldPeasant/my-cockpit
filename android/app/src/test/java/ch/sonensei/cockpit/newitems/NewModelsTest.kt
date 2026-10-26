package ch.sonensei.cockpit.newitems

import ch.sonensei.cockpit.newitems.NewTestData.mail
import ch.sonensei.cockpit.newitems.NewTestData.task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.UUID

class NewModelsTest {
    private val plumber = task("Call the plumber", "2026-10-20T08:00:00Z", 1)
    private val tiles = task("Buy tiles", "2026-10-20T10:00:00Z", 2)
    private val invoice = mail("Invoice", "2026-10-20T09:00:00Z", 3)
    private val party = mail("Party", "2026-10-20T11:00:00Z", 4)

    private fun keys(rows: List<NewRow>) = rows.map { it.key }

    @Test
    fun mergesTasksAndMailsNewestFirst() {
        val rows = mergeNew(listOf(plumber, tiles), listOf(party, invoice), moreMail = false)

        assertEquals(
            listOf("mail:${party.id}", "task:${tiles.id}", "mail:${invoice.id}", "task:${plumber.id}"),
            keys(rows),
        )
    }

    @Test
    fun inboxFilterShowsOnlyTasks() {
        val rows = mergeNew(listOf(plumber, tiles), listOf(party, invoice), moreMail = true, filter = NewFilter.INBOX)

        assertEquals(listOf("task:${tiles.id}", "task:${plumber.id}"), keys(rows))
    }

    @Test
    fun mailFilterShowsOnlyMails() {
        val rows = mergeNew(listOf(plumber, tiles), listOf(party, invoice), moreMail = false, filter = NewFilter.MAIL)

        assertEquals(listOf("mail:${party.id}", "mail:${invoice.id}"), keys(rows))
    }

    @Test
    fun tasksOlderThanTheLoadedMailWaitForTheNextPage() {
        val rows = mergeNew(listOf(plumber, tiles), listOf(party, invoice), moreMail = true)

        // The plumber task (08:00) is older than the oldest loaded mail (09:00): it would land above older mail.
        assertEquals(listOf("mail:${party.id}", "task:${tiles.id}", "mail:${invoice.id}"), keys(rows))
    }

    @Test
    fun rowsWithoutTimeComeLast() {
        val undated = mail("No date", null, 5)
        val old = task("Old", "2020-01-01T00:00:00Z", 6).copy(id = UUID.fromString("00000000-0000-4000-8000-000000000006"))

        val rows = mergeNew(listOf(old, tiles), listOf(undated), moreMail = false)

        assertEquals("task:${tiles.id}", rows.first().key)
        assertEquals(setOf("mail:${undated.id}", "task:${old.id}"), keys(rows).drop(1).toSet())
    }

    @Test
    fun createdAtReadsTheUuidV7Timestamp() {
        assertEquals(Instant.parse("2026-10-20T10:00:00Z"), createdAt(tiles.id))
        assertNull(createdAt(UUID.fromString("00000000-0000-4000-8000-000000000001")))
    }
}
