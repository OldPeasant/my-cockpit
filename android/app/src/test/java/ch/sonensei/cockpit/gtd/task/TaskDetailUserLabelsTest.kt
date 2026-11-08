package ch.sonensei.cockpit.gtd.task

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.settings.LocalUserSettings
import ch.sonensei.cockpit.core.settings.UserSettings
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The task detail shows (and edits with) the energy/creativity labels the user named in the synced settings. */
@RunWith(AndroidJUnit4::class)
class TaskDetailUserLabelsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val fence =
        GtdTask(
            UUID.fromString("00000000-0000-7000-8000-000000000001"),
            "Repaint the fence",
            TaskList.NEXT,
            0,
            2,
            energy = Energy.LOW,
            creativity = Creativity.CREATIVE,
        )
    private val renamed =
        UserSettings(
            energyLabels = mapOf("LOW" to "Drained", "HIGH" to "Buzzing"),
            creativityLabels = mapOf("CREATIVE" to "Artsy"),
        )

    @Test
    fun `the properties show the user's labels`() {
        val state = TaskDetailUiState(loading = false, detail = TaskDetail(fence))
        composeRule.setContent {
            CockpitTheme {
                CompositionLocalProvider(LocalUserSettings provides renamed) { TaskDetailContent(state, TaskDetailCallbacks()) }
            }
        }

        composeRule.onNodeWithText("Drained").assertExists()
        composeRule.onNodeWithText("Artsy").assertExists()
    }

    @Test
    fun `the edit form's chips show the user's labels`() {
        val source = FakeTaskDetailSource(TaskDetail(fence))
        val vm = TaskDetailViewModel(source, flowOf(emptyList()), flowOf(emptyList()), flowOf(null))
        vm.open(fence.id)
        composeRule.setContent {
            CockpitTheme {
                CompositionLocalProvider(LocalUserSettings provides renamed) {
                    val state = vm.uiState.collectAsStateWithLifecycle().value
                    TaskDetailContent(state, TaskDetailCallbacks.of(vm))
                }
            }
        }
        composeRule.onNodeWithTag(TaskDetailTags.EDIT).performClick()

        composeRule.onNodeWithTag(TaskDetailTags.energy(Energy.LOW)).performScrollTo().assertTextContains("Drained")
        composeRule.onNodeWithTag(TaskDetailTags.energy(Energy.HIGH)).performScrollTo().assertTextContains("Buzzing")
        composeRule.onNodeWithTag(TaskDetailTags.creativity(Creativity.CREATIVE)).performScrollTo().assertTextContains("Artsy")
    }
}
