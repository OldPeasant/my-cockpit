package ch.sonensei.cockpit.newitems

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.task.LocalTaskOpener
import ch.sonensei.cockpit.gtd.task.TaskOpener
import ch.sonensei.cockpit.mail.list.MailNextAction
import ch.sonensei.cockpit.mail.list.MailPage
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.mail.list.Mailbox
import ch.sonensei.cockpit.mail.read.MailReadRepository
import ch.sonensei.cockpit.newitems.NewTestData.mail
import ch.sonensei.cockpit.newitems.NewTestData.task
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The New view's "Next action & archive" row button, its notices, and the route opening the created task. */
@RunWith(AndroidJUnit4::class)
class NewNextActionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val at = Instant.parse("2026-10-20T12:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)
    private val plumber = task("Call the plumber", "2026-10-20T08:00:00Z", 1)
    private val invoice = mail("Invoice", "2026-10-20T09:00:00Z", 3)
    private val loaded =
        NewUiState(rows = listOf(NewRow.Mail(invoice), NewRow.Task(plumber)), inboxRefreshedAt = at, mailLoadedAt = at)
    private val taskId: UUID = UUID.fromString("00000000-0000-7000-9000-000000000003")

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(loaded)

    private fun show(initial: NewUiState) {
        state = initial
        val callbacks =
            NewCallbacks(
                toTask = { calls += "toTask $it" },
                nextAction = { calls += "nextAction $it" },
                dismissNotice = { calls += "dismiss" },
            )
        composeRule.setContent { CockpitTheme { NewContent(state, callbacks, clock = clock) } }
    }

    @Test
    fun aMailRowOffersNextActionAndArchiveBesideToTask() {
        show(loaded)

        composeRule
            .onNodeWithTag(NewTags.nextAction(invoice.id))
            .assertTextEquals("→ Next action & archive")
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag(NewTags.toTask(invoice.id)).performClick()

        assertEquals(listOf("nextAction ${invoice.id}", "toTask ${invoice.id}"), calls)
    }

    @Test
    fun theActionWaitsOfflineAndWhileTheMailIsConverted() {
        show(loaded.copy(mailOffline = true))
        composeRule.onNodeWithTag(NewTags.nextAction(invoice.id)).assertIsNotEnabled()

        state = loaded.copy(converting = setOf(invoice.id))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NewTags.nextAction(invoice.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(NewTags.toTask(invoice.id)).assertIsNotEnabled()
    }

    @Test
    fun aFailedCreationAndAMailNotArchivedAreReported() {
        show(loaded.copy(notice = NewNotice.NextActionFailed))
        composeRule.onNodeWithTag(NewTags.NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText("The next action could not be created; the mail stays where it is.").assertIsDisplayed()

        state = loaded.copy(notice = NewNotice.NotArchived("Invoice", "NO_ROLE_FOLDER"))
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText("Next action created, but “Invoice” could not be archived. This account has no Archive folder.")
            .assertIsDisplayed()

        state = loaded.copy(notice = NewNotice.NotArchived("Invoice"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Next action created, but “Invoice” could not be archived.").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun onA320dpScreenBothRowButtonsFitWithFullTouchTargets() {
        show(loaded)

        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val minimum = with(composeRule.density) { 48.dp.toPx() } - 0.5f
        listOf(NewTags.toTask(invoice.id), NewTags.nextAction(invoice.id)).forEach { tag ->
            val button = composeRule.onNodeWithTag(tag).assertIsDisplayed()
            assertTrue(tag, button.getUnclippedBoundsInRoot().right <= root.right)
            val touch = button.fetchSemanticsNode().touchBoundsInRoot
            assertTrue(tag, touch.height >= minimum)
        }
    }

    @Test
    fun theRouteOpensTheCreatedTaskOnceThroughTheShellsOpener() {
        val inbox = MutableStateFlow(Cached<List<GtdTask>>(listOf(plumber), offline = false, refreshedAt = at))
        val tasks =
            mockk<TaskRepository> {
                every { observe(TaskView.INBOX) } returns inbox
                coEvery { refresh(TaskView.INBOX) } returns RefreshResult.Refreshed
            }
        val mailRepo =
            mockk<MailRepository> {
                coEvery { messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(invoice), null))
                coEvery { toNextActions(listOf(invoice.id)) } returns
                    MailResult.Ok(listOf(MailNextAction(invoice.id, taskId, "Invoice", archived = true)))
            }
        val vm = NewViewModel(tasks, mailRepo, mockk<MailReadRepository>(), NewMailCache(), { NewFilter.ALL }, clock)
        val opened = mutableListOf<Pair<UUID, GtdTask?>>()
        composeRule.setContent {
            CockpitTheme {
                CompositionLocalProvider(LocalTaskOpener provides TaskOpener { id, snapshot, _ -> opened += id to snapshot }) {
                    NewRoute(viewModel = vm)
                }
            }
        }

        composeRule.onNodeWithTag(NewTags.nextAction(invoice.id)).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf<Pair<UUID, GtdTask?>>(taskId to null), opened)
        composeRule.onNodeWithText("Invoice").assertDoesNotExist()
        composeRule.onNodeWithText("Call the plumber").assertExists()
        assertEquals(null, vm.uiState.value.openTask)
    }
}
