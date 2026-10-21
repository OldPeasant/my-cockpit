package ch.sonensei.cockpit.mail.list

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The list's other states: paging near the end, the account of each row in the unified inbox, markers and messages. */
@RunWith(AndroidJUnit4::class)
class MailListScreenStatesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val home = MailAccount(id(81), "Home", "me@example.net")
    private val trash = MailFolder(id(92), work.id, "Deleted Items", "Deleted Items", FolderRole.TRASH, 0)
    private val sub = MailFolder(id(93), work.id, "2026", "Projects/2026", null, 1)

    private fun mail(
        n: Int,
        account: MailAccount = work,
        flagged: Boolean = false,
        attachments: Boolean = false,
        date: Instant? = Instant.parse("2026-10-21T07:00:00Z"),
    ) = MailSummary(id(n), account.id, "Anna Example", "Subject $n", "", date, true, flagged, attachments)

    private val calls = mutableListOf<String>()

    private fun show(state: MailListUiState) =
        composeRule.setContent {
            CockpitTheme {
                MailListContent(
                    state,
                    MailListCallbacks(
                        loadMore = { calls += "more" },
                        retry = { calls += "retry" },
                        select = { calls += "select $it" },
                    ),
                    clock = clock,
                )
            }
        }

    @Test
    fun `a short list with more on the server asks for the next page`() {
        show(
            MailListUiState(
                accounts = listOf(MailAccountFolders(work, emptyList())),
                messages =
                    (1..3).map {
                        mail(it)
                    },
                nextCursor = "c1",
                loaded = true,
            ),
        )
        composeRule.waitForIdle()
        assertEquals(listOf("more"), calls)
    }

    @Test
    fun `without a next page nothing more is asked for`() {
        show(MailListUiState(accounts = listOf(MailAccountFolders(work, emptyList())), messages = (1..3).map { mail(it) }, loaded = true))
        composeRule.waitForIdle()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `near the end the next page is asked for again once a load ends`() {
        val shown =
            mutableStateOf(
                MailListUiState(
                    accounts = listOf(MailAccountFolders(work, emptyList())),
                    messages = (1..3).map { mail(it) },
                    nextCursor = "c1",
                    loaded = true,
                    loading = true,
                ),
            )
        composeRule.setContent {
            CockpitTheme {
                MailListContent(shown.value, MailListCallbacks(loadMore = { calls += "more" }), clock = clock)
            }
        }
        composeRule.waitForIdle()
        assertTrue(calls.isEmpty())

        // The refresh ends: still near the end, so the next page is asked for without scrolling.
        shown.value = shown.value.copy(loading = false)
        composeRule.waitForIdle()
        assertEquals(listOf("more"), calls)

        // That page failed and kept its cursor: the next attempt follows when it ends.
        shown.value = shown.value.copy(loadingMore = true)
        composeRule.waitForIdle()
        shown.value = shown.value.copy(loadingMore = false)
        composeRule.waitForIdle()
        assertEquals(listOf("more", "more"), calls)
    }

    @Test
    fun `a page being loaded shows below the list`() {
        show(
            MailListUiState(
                accounts = listOf(MailAccountFolders(work, emptyList())),
                messages = listOf(mail(1)),
                loaded = true,
                loadingMore = true,
            ),
        )
        composeRule.onNodeWithTag(MailListTags.LOADING_MORE).assertExists()
    }

    @Test
    fun `the unified inbox of several accounts names each row's account`() {
        val accounts = listOf(MailAccountFolders(work, emptyList()), MailAccountFolders(home, emptyList()))
        show(MailListUiState(accounts = accounts, messages = listOf(mail(1), mail(2, account = home)), loaded = true))

        composeRule.onNodeWithTag(MailListTags.row(id(1))).assertTextContains("Work")
        composeRule.onNodeWithTag(MailListTags.row(id(2))).assertTextContains("Home")
    }

    @Test
    fun `flagged mail and mail with attachments are marked`() {
        show(
            MailListUiState(
                accounts = listOf(MailAccountFolders(work, emptyList())),
                messages = listOf(mail(1, flagged = true), mail(2, attachments = true, date = null)),
                loaded = true,
            ),
        )
        composeRule.onNodeWithContentDescription("Flagged").assertExists()
        composeRule.onNodeWithContentDescription("Has attachments").assertExists()
    }

    @Test
    fun `a mail without a subject says so`() {
        show(
            MailListUiState(
                accounts = listOf(MailAccountFolders(work, emptyList())),
                messages = listOf(mail(1).copy(subject = " ")),
                loaded = true,
            ),
        )
        composeRule.onNodeWithTag(MailListTags.row(id(1))).assertTextContains("(no subject)")
    }

    @Test
    fun `special folders are named by role, subfolders by name`() {
        show(MailListUiState(accounts = listOf(MailAccountFolders(work, listOf(trash, sub))), loaded = true))
        composeRule.onNodeWithTag(MailListTags.MAILBOX).performClick()
        composeRule.onNodeWithTag(MailListTags.folder(trash.id)).assertTextContains("Trash")
        composeRule.onNodeWithTag(MailListTags.folder(sub.id)).assertTextContains("2026")
    }

    @Test
    fun `drafts, sent, spam and archive folders are named by role whatever the server calls them`() {
        val folders =
            listOf(
                MailFolder(id(94), work.id, "Entwürfe", "Entwürfe", FolderRole.DRAFTS, 0),
                MailFolder(id(95), work.id, "Gesendet", "Gesendet", FolderRole.SENT, 0),
                MailFolder(id(96), work.id, "Junk E-Mail", "Junk E-Mail", FolderRole.SPAM, 0),
                MailFolder(id(97), work.id, "Archives", "Archives", FolderRole.ARCHIVE, 0),
            )
        show(MailListUiState(accounts = listOf(MailAccountFolders(work, folders)), loaded = true))
        composeRule.onNodeWithTag(MailListTags.MAILBOX).performClick()

        listOf("Drafts", "Sent", "Spam", "Archive").zip(folders).forEach { (label, folder) ->
            composeRule.onNodeWithTag(MailListTags.folder(folder.id)).assertTextContains(label)
        }
    }

    @Test
    fun `an offline list says it may be outdated and offers a retry`() {
        show(MailListUiState(messages = listOf(mail(1)), loaded = true, message = MailListMessage.OFFLINE))
        composeRule.onNodeWithTag(MailListTags.MESSAGE).assertTextContains("Can't reach the server. The list may be outdated.")
        composeRule.onNodeWithTag(MailListTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `a failed load is reported`() {
        show(MailListUiState(loaded = false, message = MailListMessage.FAILED))
        composeRule.onNodeWithTag(MailListTags.MESSAGE).assertTextContains("The mail could not be loaded.")
    }

    @Test
    fun `a failed archive is reported`() {
        show(MailListUiState(messages = listOf(mail(1)), loaded = true, message = MailListMessage.ARCHIVE_FAILED))
        composeRule.onNodeWithTag(MailListTags.MESSAGE).assertTextContains("The mail could not be archived.")
    }
}
