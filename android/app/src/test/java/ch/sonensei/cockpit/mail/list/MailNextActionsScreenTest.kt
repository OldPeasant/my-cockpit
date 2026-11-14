package ch.sonensei.cockpit.mail.list

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The selection's "Next action & archive" menu item and the "N next actions created" snackbar, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class MailNextActionsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val work = MailAccount(id(80), "Ann Example", "me@example.org")
    private val workInbox = MailFolder(id(90), work.id, "INBOX", "INBOX", FolderRole.INBOX, 0)

    private fun mail(n: Int) =
        MailSummary(
            id(n),
            work.id,
            "Anna Example",
            "Subject $n",
            "Snippet $n",
            Instant.parse("2026-10-21T07:00:00Z"),
            true,
            false,
            false,
            workInbox.id,
        )

    private val calls = mutableListOf<String>()

    private val callbacks =
        MailListCallbacks(
            selection = SelectionCallbacks(nextActions = { calls += "next actions" }, act = { calls += "bulk $it" }),
            openNext = { calls += "open next" },
            dismissNextActions = { calls += "dismiss ${it.created}" },
        )

    private fun state(
        vararg selected: Int,
        nextActions: MailNextActionsDone? = null,
        making: Boolean = false,
    ) = MailListUiState(
        mailbox = Mailbox.Unified,
        accounts = listOf(MailAccountFolders(work, listOf(workInbox))),
        messages = listOf(mail(1), mail(2), mail(3)),
        loaded = true,
        selected = selected.map(::id).toSet(),
        nextActions = nextActions,
        makingNextActions = making,
    )

    private fun show(state: MailListUiState) = composeRule.setContent { CockpitTheme { MailListContent(state, callbacks, clock = clock) } }

    @Test
    fun `the selection menu offers next action and archive first, and asks for it`() {
        show(state(1, 2))

        composeRule.onNodeWithTag(MailListTags.SELECTION_MORE).performClick()
        composeRule
            .onNodeWithTag(MailListTags.SELECTION_NEXT_ACTIONS)
            .assertTextContains("Next action & archive")
            .assertIsEnabled()
            .performClick()

        assertEquals(listOf("next actions"), calls)
        // The menu closed.
        composeRule.onNodeWithTag(MailListTags.SELECTION_NEXT_ACTIONS).assertDoesNotExist()
    }

    @Test
    fun `while the next actions are made the menu item waits`() {
        show(state(1, making = true))

        composeRule.onNodeWithTag(MailListTags.SELECTION_MORE).performClick()
        composeRule.onNodeWithTag(MailListTags.SELECTION_NEXT_ACTIONS).assertIsNotEnabled().performClick()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `at 320dp the selection bar still fits, with the action in its menu`() {
        show(state(1, 2))

        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val more = composeRule.onNodeWithTag(MailListTags.SELECTION_MORE).assertIsDisplayed()
        assertTrue(more.getUnclippedBoundsInRoot().right <= root.right)
        more.performClick()
        val item = composeRule.onNodeWithTag(MailListTags.SELECTION_NEXT_ACTIONS).assertIsDisplayed()
        val minimum = with(composeRule.density) { 48.dp.toPx() } - 0.5f
        assertTrue(item.fetchSemanticsNode().touchBoundsInRoot.height >= minimum)
    }

    @Test
    fun `the snackbar counts the next actions and its action opens Next Actions`() {
        show(state(nextActions = MailNextActionsDone(2, id = 1)))

        composeRule.onNodeWithText("2 next actions created").assertIsDisplayed()
        composeRule.onNodeWithText("Open Next Actions").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("dismiss 2", "open next"), calls)
        composeRule.onNodeWithText("2 next actions created").assertDoesNotExist()
    }

    @Test
    fun `one next action is counted in the singular`() {
        show(state(nextActions = MailNextActionsDone(1, id = 1)))
        composeRule.onNodeWithText("1 next action created").assertIsDisplayed()
    }

    @Test
    fun `the snackbar names each mail not archived and stays until closed`() {
        show(state(nextActions = MailNextActionsDone(3, listOf("Subject 2", "Subject 3"), id = 1)))

        composeRule
            .onNodeWithText("3 next actions created", substring = true)
            .assertTextEquals("3 next actions created\n“Subject 2” could not be archived.\n“Subject 3” could not be archived.")
        composeRule.mainClock.advanceTimeBy(LONG_SNACKBAR_MS)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("3 next actions created", substring = true).assertIsDisplayed()
        assertEquals(emptyList<String>(), calls)

        composeRule.onNodeWithContentDescription("Dismiss").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("dismiss 3"), calls)
    }

    @Test
    fun `a snackbar without failures goes away by itself without opening Next Actions`() {
        show(state(nextActions = MailNextActionsDone(2, id = 1)))
        composeRule.onNodeWithText("2 next actions created").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(LONG_SNACKBAR_MS)
        composeRule.waitForIdle()

        assertEquals(listOf("dismiss 2"), calls)
    }

    @Test
    fun `a failed call is reported`() {
        show(state().copy(message = MailListMessage.NEXT_ACTIONS_FAILED))
        composeRule
            .onNodeWithTag(MailListTags.MESSAGE)
            .assertTextEquals("The next actions could not be created; the mails stay where they are.")
    }

    private companion object {
        /** Longer than a long snackbar is shown. */
        const val LONG_SNACKBAR_MS = 15_000L
    }
}
