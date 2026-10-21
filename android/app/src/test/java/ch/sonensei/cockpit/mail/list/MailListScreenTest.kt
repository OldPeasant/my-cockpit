package ch.sonensei.cockpit.mail.list

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The list alone, driven by states the tests control: rows, swipe actions, the mailbox picker. */
@RunWith(AndroidJUnit4::class)
class MailListScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val home = MailAccount(id(81), "Home", "me@example.net")
    private val workInbox = MailFolder(id(90), work.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val workArchive = MailFolder(id(91), work.id, "Archives", "Archives", FolderRole.ARCHIVE, 0)
    private val projects = MailFolder(id(92), work.id, "Projects", "Projects", null, 0)
    private val accounts = listOf(MailAccountFolders(work, listOf(workInbox, workArchive, projects)), MailAccountFolders(home, emptyList()))

    private fun mail(
        n: Int,
        seen: Boolean = true,
    ) = MailSummary(
        id(n),
        work.id,
        "Anna Example",
        "Subject $n",
        "Snippet $n",
        Instant.parse("2026-10-21T07:00:00Z"),
        seen,
        flagged = false,
        hasAttachments = false,
    )

    private val calls = mutableListOf<String>()

    private fun show(state: MailListUiState) =
        composeRule.setContent {
            CockpitTheme {
                MailListContent(
                    state,
                    MailListCallbacks(
                        open = { calls += "open $it" },
                        select = { calls += "select $it" },
                        act = { id, action -> calls += "$action $id" },
                        loadMore = { calls += "more" },
                        retry = { calls += "retry" },
                    ),
                    clock = clock,
                )
            }
        }

    @Test
    fun `swiping a row to the right archives it, to the left deletes it`() {
        show(MailListUiState(accounts = accounts, messages = listOf(mail(1), mail(2)), loaded = true))

        composeRule.onNodeWithTag(MailListTags.row(id(1))).performTouchInput { swipeRight() }
        composeRule.onNodeWithTag(MailListTags.row(id(2))).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(listOf("ARCHIVE ${id(1)}", "DELETE ${id(2)}"), calls)
    }

    @Test
    fun `in the Archive folder a row cannot be archived again, only deleted`() {
        show(
            MailListUiState(
                mailbox = Mailbox.Folder(work.id, workArchive.id),
                accounts = accounts,
                messages = listOf(mail(1)),
                loaded = true,
            ),
        )

        composeRule.onNodeWithTag(MailListTags.row(id(1))).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), calls)

        composeRule.onNodeWithTag(MailListTags.row(id(1))).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(listOf("DELETE ${id(1)}"), calls)
    }

    @Test
    fun `the swipe actions are also accessibility actions`() {
        show(MailListUiState(accounts = accounts, messages = listOf(mail(1)), loaded = true))

        val actions =
            composeRule
                .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        assertEquals(listOf("Archive", "Delete"), actions.map { it.label })
        actions.forEach { it.action() }
        assertEquals(listOf("ARCHIVE ${id(1)}", "DELETE ${id(1)}"), calls)
    }

    @Test
    fun `a tapped row opens the mail, and unread mail is marked`() {
        show(MailListUiState(accounts = accounts, messages = listOf(mail(1, seen = false), mail(2)), loaded = true))

        composeRule.onNodeWithTag(MailListTags.row(id(1))).assertTextContains("Subject 1", substring = true)
        composeRule.onAllNodesWithTag(MailListTags.row(id(1))).assertCountEquals(1)
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Unread"))).assertExists()
        composeRule.onNodeWithTag(MailListTags.row(id(2))).performClick()
        assertEquals(listOf("open ${id(2)}"), calls)
    }

    @Test
    fun `the mailbox picker offers the unified inbox and each account's folders`() {
        show(MailListUiState(accounts = accounts, messages = listOf(mail(1)), loaded = true))

        composeRule.onNodeWithTag(MailListTags.MAILBOX).assertTextContains("All inboxes", substring = true).performClick()
        composeRule.onNodeWithTag(MailListTags.account(work.id)).assertTextContains("Work")
        composeRule.onNodeWithTag(MailListTags.account(home.id)).assertTextContains("Home")
        composeRule.onNodeWithTag(MailListTags.folder(workInbox.id)).assertTextContains("Inbox")
        composeRule.onNodeWithTag(MailListTags.folder(projects.id)).performClick()
        composeRule.onNodeWithTag(MailListTags.MAILBOX).performClick()
        composeRule.onNodeWithTag(MailListTags.UNIFIED).performClick()

        assertEquals(listOf("select ${Mailbox.Folder(work.id, projects.id)}", "select ${Mailbox.Unified}"), calls)
    }

    @Test
    fun `a selected folder is named by its role`() {
        show(MailListUiState(mailbox = Mailbox.Folder(work.id, workInbox.id), accounts = accounts, loaded = true))
        composeRule.onNodeWithTag(MailListTags.MAILBOX).assertTextContains("Inbox", substring = true)
        composeRule.onNodeWithTag(MailListTags.EMPTY).assertTextContains("No mail here.")
    }

    @Test
    fun `without accounts the empty list says how to add one`() {
        show(MailListUiState(loaded = true))
        composeRule.onNodeWithTag(MailListTags.EMPTY).assertTextContains("No mail account yet", substring = true)
    }

    @Test
    fun `a failed swipe action is reported with a retry`() {
        show(MailListUiState(accounts = accounts, messages = listOf(mail(1)), loaded = true, message = MailListMessage.DELETE_FAILED))
        composeRule.onNodeWithTag(MailListTags.MESSAGE).assertTextContains("The mail could not be deleted.")
        composeRule.onNodeWithTag(MailListTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }
}
