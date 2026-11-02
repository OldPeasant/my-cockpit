package ch.sonensei.cockpit.gtd.task

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.lists.LiveListCallbacks
import ch.sonensei.cockpit.gtd.lists.TaskListUiState
import ch.sonensei.cockpit.gtd.lists.WaitingContent
import ch.sonensei.cockpit.gtd.next.NextCallbacks
import ch.sonensei.cockpit.gtd.next.NextContent
import ch.sonensei.cockpit.gtd.next.NextUiState
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.gtd.next.TaskRow
import ch.sonensei.cockpit.ui.CockpitShellFrame
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Opening the task detail screen from the lists and the shell, and editing on it. */
@RunWith(AndroidJUnit4::class)
class TaskDetailScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 20)
    private val seeds = GtdTask(id(1), "Buy seeds", TaskList.NEXT, 0, 3, timeEstimateMin = 15, notesMd = "Tomatoes **and** basil")
    private val contract = GtdTask(id(2), "Contract", TaskList.WAITING, 0, 1, waitingForText = "Anna", waitingSince = today.minusDays(3))

    private val opened = mutableListOf<Pair<UUID, GtdTask?>>()
    private val opener = TaskOpener { id, snapshot, _ -> opened += id to snapshot }

    @Test
    fun `tapping a Next row opens the task with the list's copy, the checkbox still completes`() {
        val completed = mutableListOf<GtdTask>()
        composeRule.setContent {
            CockpitTheme {
                CompositionLocalProvider(LocalTaskOpener provides opener) {
                    NextContent(
                        NextUiState(tasks = listOf(seeds), total = 1, refreshedAt = Instant.EPOCH, today = today),
                        NextCallbacks(complete = { completed += it }),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Buy seeds").performClick()
        assertEquals(listOf(seeds.id to seeds), opened)
        composeRule.onNodeWithTag(TaskListTags.complete(seeds.id)).performClick()
        assertEquals(listOf(seeds), completed)
        assertEquals(1, opened.size)
    }

    @Test
    fun `tapping a Waiting row opens the task, its follow-up button does not`() {
        val followed = mutableListOf<String>()
        composeRule.setContent {
            CockpitTheme {
                CompositionLocalProvider(LocalTaskOpener provides opener) {
                    WaitingContent(
                        TaskListUiState(tasks = listOf(contract), loading = false, loaded = true, today = today),
                        LiveListCallbacks(),
                        { task, _ -> followed += task.title },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Contract").performClick()
        assertEquals(listOf(contract.id to contract), opened)
        composeRule.onNodeWithText("Follow up").performClick()
        assertEquals(listOf("Contract"), followed)
        assertEquals(1, opened.size)
    }

    @Test
    fun `outside the shell rows have no tap target`() {
        composeRule.setContent {
            CockpitTheme { TaskRow(seeds, emptyMap(), today, readOnly = false, onComplete = {}) }
        }
        composeRule.onNodeWithText("Buy seeds").performClick()
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `in the shell a task row opens the detail route on top and Back returns to the list`() {
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            CockpitTheme {
                CockpitShellFrame(navController, prompts = {}) { destination, _, modifier ->
                    if (destination == CockpitDestination.Next) {
                        Box(modifier) { TaskRow(seeds, emptyMap(), today, readOnly = false, onComplete = {}) }
                    } else {
                        Box(modifier)
                    }
                }
            }
        }
        composeRule.runOnUiThread { navController.navigate(CockpitDestination.Next.route) }
        composeRule.onNodeWithText("Buy seeds").performClick()
        composeRule.waitForIdle()
        assertEquals(TaskRoutes.DETAIL, composeRule.runOnUiThread { navController.currentDestination?.route })
        assertEquals(
            seeds.id.toString(),
            composeRule.runOnUiThread {
                navController.currentBackStackEntry?.arguments?.getString(TaskRoutes.ARG_ID)
            },
        )
        assertEquals(seeds, TaskSnapshots.get(seeds.id))
        composeRule.onNodeWithText("Task").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Next)).assertIsDisplayed()
    }

    @Test
    fun `editing the title saves it with PATCH and shows the saved task`() {
        val source = FakeTaskDetailSource(TaskDetail(seeds))
        val vm = TaskDetailViewModel(source, flowOf(emptyList()), flowOf(emptyList()), flowOf(null))
        vm.open(seeds.id)
        composeRule.setContent {
            CockpitTheme {
                val state = vm.uiState.collectAsStateWithLifecycle().value
                TaskDetailContent(state, TaskDetailCallbacks.of(vm))
            }
        }
        composeRule.onNodeWithTag(TaskDetailTags.TITLE).assertIsDisplayed()
        composeRule.onNodeWithTag(TaskDetailTags.EDIT).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.TITLE_INPUT).performScrollTo().performTextReplacement("Buy seeds and soil")
        composeRule.onNodeWithTag(TaskDetailTags.SAVE).performClick()
        composeRule.waitForIdle()
        val (task, patch) = source.updates.single()
        assertEquals(3L, task.version)
        assertEquals(JsonObject(mapOf("title" to JsonPrimitive("Buy seeds and soil"))), patch)
        composeRule.onNodeWithTag(TaskDetailTags.FORM).assertDoesNotExist()
        composeRule.onNodeWithText("Buy seeds and soil").assertIsDisplayed()
    }

    @Test
    fun `a field error from the server is shown at the field`() {
        val source = FakeTaskDetailSource(TaskDetail(seeds))
        source.updateResult = TaskResult.Invalid(mapOf("timeEstimateMin" to "must be at most 10000"), "Invalid task")
        val vm = TaskDetailViewModel(source, flowOf(emptyList()), flowOf(emptyList()), flowOf(null))
        vm.open(seeds.id)
        composeRule.setContent {
            CockpitTheme {
                val state = vm.uiState.collectAsStateWithLifecycle().value
                TaskDetailContent(state, TaskDetailCallbacks.of(vm))
            }
        }
        composeRule.onNodeWithTag(TaskDetailTags.EDIT).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.ESTIMATE_INPUT).performScrollTo().performTextReplacement("30")
        composeRule.onNodeWithTag(TaskDetailTags.SAVE).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.ESTIMATE_INPUT).assert(hasText("must be at most 10000"))
    }

    @Test
    fun `offline the task shows read-only with the offline banner`() {
        val state = TaskDetailUiState(loading = false, detail = TaskDetail(seeds), offline = true)
        composeRule.setContent { CockpitTheme { TaskDetailContent(state, TaskDetailCallbacks()) } }
        composeRule.onNodeWithTag(TaskListTags.OFFLINE).assertIsDisplayed()
        composeRule.onNodeWithText("Buy seeds").assertIsDisplayed()
        composeRule.onNodeWithTag(TaskDetailTags.EDIT).assertIsNotEnabled()
        composeRule.onNodeWithTag(TaskDetailTags.COMPLETE).assertIsNotEnabled()
        composeRule.onNodeWithTag(TaskDetailTags.MORE).assertIsNotEnabled()
        composeRule.onNodeWithTag(TaskDetailTags.ADD_ITEM_INPUT).assertDoesNotExist()
        composeRule.onNodeWithTag(TaskDetailTags.RETRY).assertIsEnabled()
    }
}
