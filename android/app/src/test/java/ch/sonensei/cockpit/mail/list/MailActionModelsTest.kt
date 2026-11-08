package ch.sonensei.cockpit.mail.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** What each mail action does to a listed mail, and which actions can be undone by their inverse. */
class MailActionModelsTest {
    private val mail =
        MailSummary(
            UUID.fromString("00000000-0000-7000-8000-000000000001"),
            null,
            "Anna Example",
            "Subject",
            "",
            Instant.EPOCH,
            seen = false,
            flagged = false,
            hasAttachments = false,
        )

    @Test
    fun `archive, delete, spam and move take the mail out of its folder, the marks do not`() {
        assertEquals(
            setOf(MailListAction.ARCHIVE, MailListAction.DELETE, MailListAction.SPAM, MailListAction.MOVE),
            MailListAction.entries.filter { it.removes }.toSet(),
        )
    }

    @Test
    fun `each mark is undone by its opposite, the moving actions have none`() {
        assertEquals(MailListAction.UNREAD, MailListAction.READ.inverse)
        assertEquals(MailListAction.READ, MailListAction.UNREAD.inverse)
        assertEquals(MailListAction.UNFLAG, MailListAction.FLAG.inverse)
        assertEquals(MailListAction.FLAG, MailListAction.UNFLAG.inverse)
        MailListAction.entries.filter { it.removes }.forEach { assertNull("$it", it.inverse) }
    }

    @Test
    fun `a mark changes only its own field, the other actions leave the mail as it is`() {
        assertEquals(mail.copy(seen = true), MailListAction.READ.applyTo(mail))
        assertEquals(mail, MailListAction.UNREAD.applyTo(mail.copy(seen = true)))
        assertEquals(mail.copy(flagged = true), MailListAction.FLAG.applyTo(mail))
        assertEquals(mail, MailListAction.UNFLAG.applyTo(mail.copy(flagged = true)))
        MailListAction.entries.filter { it.removes }.forEach { assertEquals("$it", mail, it.applyTo(mail)) }
    }

    @Test
    fun `the wire names are the server's action names`() {
        assertEquals(MailListAction.entries.map { it.name }, MailListAction.entries.map { it.wire })
    }

    @Test
    fun `a list entry reads its folder, an unreadable folder id is unknown`() {
        val dto =
            MailSummaryDto(
                id = "00000000-0000-7000-8000-000000000001",
                folderId = "00000000-0000-7000-8000-000000000090",
            )
        assertEquals(UUID.fromString("00000000-0000-7000-8000-000000000090"), dto.toSummary().folderId)
        assertNull(dto.copy(folderId = "not-a-uuid").toSummary().folderId)
        assertNull(dto.copy(folderId = null).toSummary().folderId)
    }
}
