package ch.sonensei.cockpit.newitems

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.task.LocalTaskOpener
import ch.sonensei.cockpit.gtd.task.TaskOpener
import ch.sonensei.cockpit.gtd.task.TaskRoutes
import ch.sonensei.cockpit.gtd.task.TaskSnapshots
import ch.sonensei.cockpit.mail.list.MailPage
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.mail.list.Mailbox
import ch.sonensei.cockpit.mail.read.MailReadRepository
import ch.sonensei.cockpit.newitems.NewTestData.mail
import ch.sonensei.cockpit.newitems.NewTestData.task
import ch.sonensei.cockpit.ui.CockpitShellFrame
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

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

    private fun show(opener: TaskOpener? = null) {
        val vm =
            NewViewModel(
                tasks,
                mailRepo,
                mockk<MailReadRepository>(),
                NewMailCache(),
                { NewFilter.ALL },
                Clock.fixed(at, ZoneOffset.UTC),
            )
        composeRule.setContent {
            CockpitTheme { CompositionLocalProvider(LocalTaskOpener provides opener) { NewRoute(viewModel = vm) } }
        }
    }

    @Test
    fun withoutTheShellsOpenerATaskTapKeepsTheListAndOpensNoSheet() {
        show(opener = null)

        composeRule.onNodeWithText("Call the plumber").performClick()
        composeRule.waitForIdle()

        // No read-only task view any more: the list (and the task's notes only as a row preview) stays.
        composeRule.onNodeWithTag(NewTags.LIST).assertExists()
        composeRule.onNodeWithText("Invoice").assertExists()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun showingTheScreenLoadsTheMail() {
        show()

        composeRule.onNodeWithText("Invoice").assertExists()
        composeRule.onNodeWithText("Call the plumber").assertExists()
        coVerify(atLeast = 1) { mailRepo.messages(Mailbox.Unified, null) }
    }

    @Test
    fun tappingATaskOpensTheTaskDetailWithTheListsCopy() {
        val opened = mutableListOf<Pair<UUID, GtdTask?>>()
        show(TaskOpener { id, snapshot, _ -> opened += id to snapshot })

        composeRule.onNodeWithText("Call the plumber").performClick()

        assertEquals(listOf(plumber.id to plumber), opened)
        // The task is not shown inside the New screen any more: the list stays.
        composeRule.onNodeWithTag(NewTags.LIST).assertExists()
    }

    @Test
    fun inTheShellATaskOpensTheDetailAndBackReturnsWithFilterAndScrollPosition() {
        val many = (1..40).map { task("Task number $it", "2026-10-20T08:00:00Z", 100 + it) }
        inbox.value = Cached(many, offline = false, refreshedAt = at)
        var created = 0
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            CockpitTheme {
                CockpitShellFrame(navController, prompts = {}) { destination, _, modifier ->
                    if (destination == CockpitDestination.New) {
                        // Scoped to the New screen's back stack entry, like hiltViewModel() in the app.
                        val vm =
                            viewModel {
                                created++
                                NewViewModel(tasks, mailRepo, mockk(), NewMailCache(), { NewFilter.ALL }, Clock.fixed(at, ZoneOffset.UTC))
                            }
                        NewRoute(modifier, viewModel = vm)
                    } else {
                        Box(modifier)
                    }
                }
            }
        }
        composeRule.runOnUiThread { navController.navigate(CockpitDestination.New.route) }
        composeRule.onNodeWithTag(NewTags.filter(NewFilter.INBOX)).performClick()
        val target = many[29]
        composeRule.onNodeWithTag(NewTags.LIST).performScrollToKey("task:${target.id}")
        composeRule.onNodeWithText(many[0].title).assertDoesNotExist()

        composeRule.onNodeWithText(target.title).performClick()
        composeRule.waitForIdle()
        assertEquals(TaskRoutes.DETAIL, composeRule.runOnUiThread { navController.currentDestination?.route })
        assertEquals(target, TaskSnapshots.get(target.id))
        composeRule.onNodeWithTag(NewTags.LIST).assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        assertEquals(CockpitDestination.New.route, composeRule.runOnUiThread { navController.currentDestination?.route })
        composeRule.onNodeWithTag(NewTags.filter(NewFilter.INBOX)).assertIsSelected()
        composeRule.onNodeWithText(target.title).assertIsDisplayed()
        composeRule.onNodeWithText(many[0].title).assertDoesNotExist()
        assertEquals(1, created)
    }
}
