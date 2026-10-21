package ch.sonensei.cockpit.mail.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Reading the server's mail answers: names, folder roles and depth, dates that may be missing or odd. */
class MailModelsTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun folder(
        path: String?,
        name: String? = path,
        delimiter: String? = "/",
        role: String? = null,
    ) = MailFolderDto("${id(90)}", "${id(80)}", name, path, delimiter, role)

    @Test
    fun `an account is named by its display name, else by its address`() {
        assertEquals("Work", MailAccountDto("${id(80)}", "Work", "me@example.org").toAccount().name)
        assertEquals("me@example.org", MailAccountDto("${id(80)}", "  ", "me@example.org").toAccount().name)
        val bare = MailAccountDto("${id(80)}").toAccount()
        assertEquals("", bare.name)
        assertEquals("", bare.address)
    }

    @Test
    fun `a folder's depth follows its path and delimiter`() {
        assertEquals(0, folder("INBOX").toFolder().depth)
        assertEquals(2, folder("INBOX/Projects/2026", name = "2026").toFolder().depth)
        // A dot is a literal delimiter, not a pattern.
        assertEquals(1, folder("INBOX.Sub", delimiter = ".").toFolder().depth)
        assertEquals(0, folder("INBOX/Sub", delimiter = null).toFolder().depth)
        assertEquals(0, folder("INBOX/Sub", delimiter = "").toFolder().depth)
    }

    @Test
    fun `a folder without name or path falls back to the other`() {
        val noPath = folder(path = null, name = "Projects").toFolder()
        assertEquals("Projects", noPath.path)
        assertEquals("Projects", noPath.name)
        assertEquals("Projects/2026", folder("Projects/2026", name = " ").toFolder().name)
    }

    @Test
    fun `known roles are read, unknown ones leave a normal folder`() {
        FolderRole.entries.forEach { assertEquals(it, folder("X", role = it.name).toFolder().role) }
        assertNull(folder("X", role = "JUNK").toFolder().role)
        assertNull(folder("X", role = "inbox").toFolder().role)
    }

    @Test
    fun `special folders come first in role order, the others by path ignoring case`() {
        fun f(
            n: Int,
            path: String,
            role: FolderRole? = null,
        ) = MailFolder(id(n), id(80), path, path, role, 0)
        val folders = listOf(f(1, "zeta"), f(2, "Trash", FolderRole.TRASH), f(3, "Alpha"), f(4, "INBOX", FolderRole.INBOX), f(5, "beta"))
        assertEquals(listOf(4, 2, 3, 5, 1).map(::id), folders.sortedWith(folderOrder).map { it.id })
    }

    @Test
    fun `a summary shows the first sender and prefers the received date`() {
        val dto =
            MailSummaryDto(
                id = "${id(1)}",
                from = listOf(MailAddressDto(null, "anna@example.org"), MailAddressDto("Ben Example", "ben@example.org")),
                sentAt = "2026-10-20T10:00:00+02:00",
                receivedAt = "2026-10-20T08:05:00Z",
            )
        val summary = dto.toSummary()
        assertEquals("anna@example.org", summary.sender)
        assertEquals(Instant.parse("2026-10-20T08:05:00Z"), summary.date)
        assertNull(summary.accountId)
        assertEquals("", summary.subject)
        assertEquals("", summary.snippet)
    }

    @Test
    fun `an unreadable received date falls back to the sent date, none leaves it empty`() {
        val fallback = MailSummaryDto(id = "${id(1)}", sentAt = "2026-10-20T10:00:00+02:00", receivedAt = "yesterday").toSummary()
        assertEquals(Instant.parse("2026-10-20T08:00:00Z"), fallback.date)
        assertNull(MailSummaryDto(id = "${id(1)}").toSummary().date)
        assertEquals("", MailSummaryDto(id = "${id(1)}").toSummary().sender)
    }

    @Test
    fun `dates are ISO-8601 with an offset, anything else is missing`() {
        assertEquals(Instant.parse("2026-10-21T06:00:00Z"), parseDate("2026-10-21T08:00:00+02:00"))
        assertNull(parseDate(null))
        assertNull(parseDate(""))
        assertNull(parseDate("2026-10-21"))
        assertNull(parseDate("not a date"))
    }

    @Test
    fun `an address shows its name, or the address when the name is blank`() {
        assertEquals("Anna Example", MailAddressDto("Anna Example", "anna@example.org").display())
        assertEquals("anna@example.org", MailAddressDto(" ", "anna@example.org").display())
        assertEquals("", MailAddressDto().display())
    }
}
