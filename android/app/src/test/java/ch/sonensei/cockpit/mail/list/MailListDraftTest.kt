package ch.sonensei.cockpit.mail.list

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Which listed or found mail lies in a Drafts folder, so that opening it continues the draft in compose. */
class MailListDraftTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val inbox = MailFolder(id(90), work.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val drafts = MailFolder(id(91), work.id, "Drafts", "Drafts", FolderRole.DRAFTS, 0)
    private val plain = MailFolder(id(92), work.id, "Drafts old", "Drafts old", null, 0)

    private fun mail(
        n: Int,
        folder: MailFolder?,
    ) = MailSummary(id(n), work.id, "Anna Example", "Subject $n", "", Instant.EPOCH, true, false, false, folder?.id)

    private val state =
        MailListUiState(
            accounts = listOf(MailAccountFolders(work, listOf(inbox, drafts, plain))),
            messages = listOf(mail(1, inbox), mail(2, drafts), mail(3, plain), mail(4, null)),
        )

    @Test
    fun `a listed mail of a Drafts folder is a draft, others are not`() {
        assertTrue(state.isDraft(id(2)))
        assertFalse(state.isDraft(id(1)))
        // Only the folder role counts, not the name.
        assertFalse(state.isDraft(id(3)))
        assertFalse(state.isDraft(id(4)))
    }

    @Test
    fun `an unknown mail is not a draft`() {
        assertFalse(state.isDraft(id(99)))
    }

    @Test
    fun `a mail whose folder is not known any more is not a draft`() {
        assertFalse(state.copy(accounts = emptyList()).isDraft(id(2)))
    }

    @Test
    fun `a found mail of a Drafts folder is a draft too`() {
        val searching =
            state.copy(
                messages = emptyList(),
                search = MailSearchState(Mailbox.Unified, query = "plan", results = listOf(mail(5, drafts), mail(6, inbox))),
            )

        assertTrue(searching.isDraft(id(5)))
        assertFalse(searching.isDraft(id(6)))
    }
}
