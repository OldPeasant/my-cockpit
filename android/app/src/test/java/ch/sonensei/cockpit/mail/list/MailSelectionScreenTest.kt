package ch.sonensei.cockpit.mail.list

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

/** The list's multi-selection, its bulk actions and move dialog, and the undo snackbar, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class MailSelectionScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val work = MailAccount(id(80), "Ann Example", "me@example.org")
    private val home = MailAccount(id(81), "Ann Example", "me@example.net")
    private val workInbox = MailFolder(id(90), work.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val workArchive = MailFolder(id(91), work.id, "Archives", "Archives", FolderRole.ARCHIVE, 0)
    private val projects = MailFolder(id(92), work.id, "Projects", "Projects", null, 0)
    private val homeInbox = MailFolder(id(94), home.id, "INBOX", "INBOX", FolderRole.INBOX, 0)
    private val accounts =
        listOf(MailAccountFolders(work, listOf(workInbox, workArchive, projects)), MailAccountFolders(home, listOf(homeInbox)))

    private fun mail(
        n: Int,
        account: MailAccount = work,
        folder: MailFolder = workInbox,
        seen: Boolean = true,
        flagged: Boolean = false,
    ) = MailSummary(
        id(n),
        account.id,
        "Anna Example",
        "Subject $n",
        "Snippet $n",
        Instant.parse("2026-10-21T07:00:00Z"),
        seen,
        flagged,
        false,
        folder.id,
    )

    private val calls = mutableListOf<String>()

    private val callbacks =
        MailListCallbacks(
            open = { calls += "open $it" },
            act = { id, action -> calls += "$action $id" },
            compose = { calls += "compose" },
            selection =
                SelectionCallbacks(
                    toggle = { calls += "toggle $it" },
                    clear = { calls += "clear" },
                    act = { calls += "bulk $it" },
                    move = { calls += "move ${it.id}" },
                ),
            undo = { calls += "undo ${it.action}" },
            dismissUndo = { calls += "dismiss ${it.action}" },
        )

    private fun show(state: MailListUiState) =
        composeRule.setContent {
            CockpitTheme { MailListContent(state, callbacks, clock = clock) }
        }

    private fun selecting(
        vararg selected: Int,
        messages: List<MailSummary> = listOf(mail(1), mail(2), mail(3)),
        mailbox: Mailbox = Mailbox.Unified,
    ) = MailListUiState(
        mailbox = mailbox,
        accounts = accounts,
        messages = messages,
        loaded = true,
        selected = selected.map(::id).toSet(),
    )

    private fun action(action: MailListAction) = composeRule.onNodeWithTag(MailListTags.selectionAction(action))

    @Test
    fun `a long press on a row asks to select it, a tap still opens`() {
        show(selecting())

        composeRule.onNodeWithTag(MailListTags.row(id(2))).performTouchInput { longClick() }
        composeRule.onNodeWithTag(MailListTags.row(id(1))).performClick()

        assertEquals(listOf("toggle ${id(2)}", "open ${id(1)}"), calls)
        composeRule.onNodeWithTag(MailListTags.SELECTION_BAR).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.MAILBOX).assertIsDisplayed()
    }

    @Test
    fun `while selecting a tap or long press toggles a row instead of opening it`() {
        show(selecting(1))

        composeRule.onNodeWithTag(MailListTags.row(id(2))).performClick()
        composeRule.onNodeWithTag(MailListTags.row(id(1))).performClick()
        composeRule.onNodeWithTag(MailListTags.row(id(3))).performTouchInput { longClick() }

        assertEquals(listOf("toggle ${id(2)}", "toggle ${id(1)}", "toggle ${id(3)}"), calls)
    }

    @Test
    fun `selected rows are marked, and the bar replaces the mailbox picker and the compose button`() {
        show(selecting(1, 3))

        composeRule.onNodeWithTag(MailListTags.SELECTION_BAR).assertIsDisplayed()
        composeRule.onNodeWithTag(MailListTags.SELECTION_COUNT).assertTextEquals("2 selected")
        composeRule.onNodeWithTag(MailListTags.row(id(1))).assertIsSelected()
        composeRule.onNodeWithTag(MailListTags.MAILBOX).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.SEARCH_OPEN).assertDoesNotExist()
        composeRule.onNodeWithTag(MailListTags.COMPOSE).assertDoesNotExist()
    }

    @Test
    fun `one selected mail is counted in the singular wording`() {
        show(selecting(2))
        composeRule.onNodeWithTag(MailListTags.SELECTION_COUNT).assertTextEquals("1 selected")
    }

    @Test
    fun `the bulk buttons ask for archive, delete, mark read and flag`() {
        show(selecting(1, 2, messages = listOf(mail(1, seen = false), mail(2), mail(3))))

        action(MailListAction.ARCHIVE).performClick()
        action(MailListAction.DELETE).performClick()
        action(MailListAction.READ).performClick()
        action(MailListAction.FLAG).performClick()

        assertEquals(listOf("bulk ARCHIVE", "bulk DELETE", "bulk READ", "bulk FLAG"), calls)
        action(MailListAction.UNREAD).assertDoesNotExist()
        action(MailListAction.UNFLAG).assertDoesNotExist()
    }

    @Test
    fun `all read and all flagged offer mark unread and remove flag`() {
        show(selecting(1, 2, messages = listOf(mail(1, flagged = true), mail(2, flagged = true), mail(3))))

        action(MailListAction.UNREAD).performClick()
        action(MailListAction.UNFLAG).performClick()

        assertEquals(listOf("bulk UNREAD", "bulk UNFLAG"), calls)
        action(MailListAction.READ).assertDoesNotExist()
        action(MailListAction.FLAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Mark unread").assertExists()
        composeRule.onNodeWithContentDescription("Remove flag").assertExists()
    }

    @Test
    fun `a selection where only some are flagged offers flag`() {
        show(selecting(1, 2, messages = listOf(mail(1, flagged = true), mail(2), mail(3))))
        action(MailListAction.FLAG).assertExists()
    }

    @Test
    fun `in the Archive folder the selection offers no archive`() {
        show(selecting(1, messages = listOf(mail(1, folder = workArchive)), mailbox = Mailbox.Folder(work.id, workArchive.id)))
        action(MailListAction.ARCHIVE).assertDoesNotExist()
        action(MailListAction.DELETE).assertIsDisplayed()
    }

    @Test
    fun `spam is in the overflow menu`() {
        show(selecting(1))

        composeRule.onNodeWithTag(MailListTags.SELECTION_MORE).performClick()
        action(MailListAction.SPAM).assertTextContains("Move to spam").performClick()

        assertEquals(listOf("bulk SPAM"), calls)
        action(MailListAction.SPAM).assertDoesNotExist()
    }

    @Test
    fun `move opens a picker of the account's other folders and asks to move to the one tapped`() {
        show(selecting(1, 2))

        composeRule.onNodeWithTag(MailListTags.SELECTION_MORE).performClick()
        action(MailListAction.MOVE).assertIsEnabled().performClick()

        composeRule.onNodeWithTag(MailMoveTags.DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(MailMoveTags.folder(workArchive.id)).assertTextEquals("Archive")
        composeRule.onNodeWithTag(MailMoveTags.folder(workInbox.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(MailMoveTags.folder(homeInbox.id)).assertDoesNotExist()
        composeRule.onNodeWithTag(MailMoveTags.folder(projects.id)).assertTextEquals("Projects").performClick()

        assertEquals(listOf("move ${projects.id}"), calls)
        composeRule.onNodeWithTag(MailMoveTags.DIALOG).assertDoesNotExist()
    }

    @Test
    fun `the move picker can be cancelled without moving`() {
        show(selecting(1))

        composeRule.onNodeWithTag(MailListTags.SELECTION_MORE).performClick()
        action(MailListAction.MOVE).performClick()
        composeRule.onNodeWithTag(MailMoveTags.CANCEL).assertTextEquals("Cancel").performClick()

        composeRule.onNodeWithTag(MailMoveTags.DIALOG).assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `mails of two accounts cannot be moved`() {
        show(selecting(1, 5, messages = listOf(mail(1), mail(5, account = home, folder = homeInbox))))

        composeRule.onNodeWithTag(MailListTags.SELECTION_MORE).performClick()
        action(MailListAction.MOVE).assertIsNotEnabled().performClick()

        composeRule.onNodeWithTag(MailMoveTags.DIALOG).assertDoesNotExist()
        action(MailListAction.SPAM).assertIsEnabled()
    }

    @Test
    fun `the close button ends the selection`() {
        show(selecting(1))
        composeRule.onNodeWithContentDescription("End selection").performClick()
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `system back ends the selection`() {
        show(selecting(1, 2))

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun `system back is left to the screen when nothing is selected`() {
        show(selecting())

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `swipes and their accessibility actions rest while selecting`() {
        show(selecting(1))

        composeRule.onNodeWithTag(MailListTags.row(id(2))).performTouchInput { swipeRight() }
        composeRule.onNodeWithTag(MailListTags.row(id(3))).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(emptyList<String>(), calls.filter { it.startsWith("ARCHIVE") || it.startsWith("DELETE") })
        val actions =
            composeRule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
                .fetchSemanticsNodes()
                .flatMap { it.config[SemanticsActions.CustomActions] }
        assertEquals(emptyList<String>(), actions.map { it.label })
    }

    @Test
    fun `swipes work again once the selection ends`() {
        var state by mutableStateOf(selecting(1))
        composeRule.setContent { CockpitTheme { MailListContent(state, callbacks, clock = clock) } }

        state = selecting()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MailListTags.row(id(2))).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(listOf("DELETE ${id(2)}"), calls)
    }

    @Test
    fun `an action that can be undone shows a snackbar whose Undo asks to undo it`() {
        val undo = MailUndo(MailListAction.ARCHIVE, listOf(mail(1), mail(2)), Mailbox.Unified, id = 1)
        show(selecting(messages = listOf(mail(3))).copy(undo = undo))

        composeRule.onNodeWithText("2 mails archived").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("undo ARCHIVE"), calls)
    }

    @Test
    fun `an undo not taken is dismissed once the snackbar times out`() {
        val undo = MailUndo(MailListAction.FLAG, listOf(mail(1)), Mailbox.Unified, id = 1)
        show(selecting().copy(undo = undo))
        composeRule.onNodeWithText("1 mail flagged").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(UNDO_TIMEOUT_MS)
        composeRule.waitForIdle()

        assertEquals(listOf("dismiss FLAG"), calls)
        composeRule.onNodeWithText("1 mail flagged").assertDoesNotExist()
    }

    @Test
    fun `each undoable action has its own snackbar text`() {
        val texts =
            mapOf(
                MailListAction.DELETE to "2 mails deleted",
                MailListAction.SPAM to "2 mails moved to spam",
                MailListAction.MOVE to "2 mails moved",
                MailListAction.READ to "2 mails marked read",
                MailListAction.UNREAD to "2 mails marked unread",
                MailListAction.UNFLAG to "Flag removed from 2 mails",
            )
        var state by mutableStateOf(selecting())
        composeRule.setContent { CockpitTheme { MailListContent(state, callbacks, clock = clock) } }
        texts.entries.forEachIndexed { i, (action, text) ->
            state = selecting().copy(undo = MailUndo(action, listOf(mail(1), mail(2)), Mailbox.Unified, id = i.toLong()))
            composeRule.waitForIdle()
            composeRule.onNodeWithText(text).assertIsDisplayed()
        }
    }

    @Test
    fun `failed spam, moves, marks and undos are reported`() {
        mapOf(
            MailListMessage.SPAM_FAILED to "The mail could not be moved to spam.",
            MailListMessage.MOVE_FAILED to "The mail could not be moved.",
            MailListMessage.MARK_FAILED to "The mail could not be changed.",
            MailListMessage.UNDO_FAILED to "The action could not be undone.",
        ).let { messages ->
            var state by mutableStateOf(selecting())
            composeRule.setContent { CockpitTheme { MailListContent(state, callbacks, clock = clock) } }
            messages.forEach { (message, text) ->
                state = selecting().copy(message = message)
                composeRule.waitForIdle()
                composeRule.onNodeWithTag(MailListTags.MESSAGE).assertTextEquals(text)
            }
        }
    }

    private companion object {
        /** Longer than a long snackbar (the undo offer) is shown. */
        const val UNDO_TIMEOUT_MS = 15_000L
    }
}
