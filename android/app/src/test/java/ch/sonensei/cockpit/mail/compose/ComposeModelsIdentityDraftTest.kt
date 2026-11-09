package ch.sonensei.cockpit.mail.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** The Draft compose mode survives configuration changes; an identity's label in the From picker. */
class ComposeModelsIdentityDraftTest {
    private val draftId = UUID.fromString("00000000-0000-7000-8000-000000000007")
    private val accountId = UUID.fromString("00000000-0000-7000-8000-000000000080")

    @Test
    fun `a draft compose is encoded with its draft id and read back`() {
        val mode = ComposeMode.Draft(draftId)

        assertEquals("draft:$draftId", mode.encode())
        assertEquals(mode, ComposeMode.parse(mode.encode()))
    }

    @Test
    fun `a draft compose without a readable id is not read back`() {
        assertNull(ComposeMode.parse("draft:"))
        assertNull(ComposeMode.parse("draft:not-a-uuid"))
    }

    @Test
    fun `an identity is labelled with name and address, or the address alone`() {
        fun identity(name: String) = MailIdentity(draftId, accountId, name, "support@example.org", isDefault = false)

        assertEquals("Support <support@example.org>", identity("Support").label)
        assertEquals("support@example.org", identity("").label)
        assertEquals("support@example.org", identity("  ").label)
        assertEquals("support@example.org", identity("support@example.org").label)
    }
}
