package ch.sonensei.cockpit.newitems

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.mail.list.MailPage
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.mail.list.Mailbox
import ch.sonensei.cockpit.mail.read.MailReadRepository
import ch.sonensei.cockpit.newitems.NewTestData.mail
import ch.sonensei.cockpit.newitems.NewTestData.task
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The route with a real view model: the list loads when shown, a task opens on top of it and back returns. */
@RunWith(AndroidJUnit4::class)
class NewRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val at = Instant.parse("2026-10-20T12:00:00Z")
    private val plumber = task("Call the plumber", "2026-10-20T08:00:00Z", 1, notes = "Ask about the boiler")
    private val invoice = mail("Invoice", "2026-10-20T09:00:00Z", 3)
    private val inbox = MutableStateFlow(Cached<List<GtdTask>>(listOf(plumber), offline = false, refreshedAt = at))
    private val tasks =
        mockk<TaskRepository> {
            every { observe(TaskView.INBOX) } returns inbox
            coEvery { refresh(TaskView.INBOX) } returns RefreshResult.Refreshed
        }
    private val mailRepo =
        mockk<MailRepository> {
            coEvery { messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(invoice), null))
        }

    private fun show() {
        val vm =
            NewViewModel(
                tasks,
                mailRepo,
                mockk<MailReadRepository>(),
                NewMailCache(),
                { NewFilter.ALL },
                Clock.fixed(at, ZoneOffset.UTC),
            )
        composeRule.setContent { CockpitTheme { NewRoute(viewModel = vm) } }
    }

    @Test
    fun showingTheScreenLoadsTheMail() {
        show()

        composeRule.onNodeWithText("Invoice").assertExists()
        composeRule.onNodeWithText("Call the plumber").assertExists()
        coVerify(atLeast = 1) { mailRepo.messages(Mailbox.Unified, null) }
    }

    @Test
    fun tappingATaskOpensItAndBackReturnsToTheList() {
        show()

        composeRule.onNodeWithText("Call the plumber").performClick()
        composeRule.onNodeWithTag(NewTags.TASK_VIEW).assertExists()
        composeRule.onNodeWithText("Ask about the boiler").assertExists()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithTag(NewTags.TASK_VIEW).assertDoesNotExist()
        composeRule.onNodeWithTag(NewTags.LIST).assertExists()
    }

    @Test
    fun anOpenTaskProcessedElsewhereReturnsToTheList() {
        show()
        composeRule.onNodeWithText("Call the plumber").performClick()
        composeRule.onNodeWithTag(NewTags.TASK_VIEW).assertExists()

        inbox.value = Cached(emptyList(), offline = false, refreshedAt = at)

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(NewTags.TASK_VIEW).assertDoesNotExist()
        composeRule.onNodeWithText("Invoice").assertExists()
    }
}
