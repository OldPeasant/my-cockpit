package ch.sonensei.cockpit.capture.entry

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.produceState
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.capture.queue.CaptureQueueStatus
import ch.sonensei.cockpit.ui.CockpitShell
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CaptureButtonTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the view model follows the queue's pending count`() =
        runTest(UnconfinedTestDispatcher()) {
            val status = MutableStateFlow(CaptureQueueStatus(0, null))
            val viewModel = CaptureButtonViewModel(status)
            backgroundScope.launch { viewModel.pendingCount.collect {} }
            assertEquals(0, viewModel.pendingCount.value)

            status.value = CaptureQueueStatus(2, null)
            assertEquals(2, viewModel.pendingCount.value)

            status.value = CaptureQueueStatus(0, null)
            assertEquals(0, viewModel.pendingCount.value)
        }

    @Test
    fun `an unreadable queue shows no count instead of failing`() =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = CaptureButtonViewModel(flow { throw IllegalStateException("db") })
            backgroundScope.launch { viewModel.pendingCount.collect {} }
            assertEquals(0, viewModel.pendingCount.value)
        }

    @Test
    fun `the badge shows the pending count and the description says it`() {
        val count = MutableStateFlow(0)
        composeRule.setContent {
            CockpitTheme {
                val pending = produceState(0) { count.collect { value = it } }.value
                CaptureButtonContent(CaptureButtonPlacement.Fab, pending, onClick = {})
            }
        }
        composeRule.onNodeWithTag(CaptureButtonTags.BADGE).assertDoesNotExist()
        composeRule.onNodeWithTag(CaptureButtonTags.FAB).assertContentDescriptionEquals("Capture to Inbox")

        count.value = 3
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CaptureButtonTags.BADGE, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(CaptureButtonTags.FAB).assertContentDescriptionEquals("Capture to Inbox, 3 to sync")
    }

    @Test
    fun `a large count is capped in the badge`() {
        assertEquals("7", badgeText(7))
        assertEquals("99", badgeText(99))
        assertEquals("99+", badgeText(100))
    }

    @Test
    fun `every screen has exactly one capture button and screens with their own FAB get it in the app bar`() {
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            CockpitTheme { CockpitShell(navController) }
        }
        CockpitDestination.entries.forEach { destination ->
            composeRule.runOnUiThread { navController.navigate(destination.route) }
            composeRule.waitForIdle()
            val inAppBar = destination in OWN_FAB
            composeRule.onAllNodesWithTag(CaptureButtonTags.FAB).assertCountEquals(if (inAppBar) 0 else 1)
            composeRule.onAllNodesWithTag(CaptureButtonTags.ACTION).assertCountEquals(if (inAppBar) 1 else 0)
        }
    }

    @Test
    fun `tapping the button opens the capture sheet in the app's task`() {
        composeRule.setContent { CockpitTheme { CockpitShell() } }
        composeRule.onNodeWithTag(CaptureButtonTags.FAB).performClick()

        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(CaptureActivity::class.java.name, started.component?.className)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(Intent.FLAG_ACTIVITY_SINGLE_TOP, started.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    @Test
    fun `one pending capture is described in the singular form and shown in the badge`() {
        composeRule.setContent { CockpitTheme { CaptureButtonContent(CaptureButtonPlacement.AppBarAction, 1, onClick = {}) } }

        composeRule.onNodeWithTag(CaptureButtonTags.ACTION).assertContentDescriptionEquals("Capture to Inbox, 1 to sync")
        composeRule.onNodeWithTag(CaptureButtonTags.FAB).assertDoesNotExist()
        composeRule.onNodeWithTag(CaptureButtonTags.BADGE, useUnmergedTree = true).assertExists()
        // The badge's number is not read out a second time: only the button's description carries it.
        composeRule.onNodeWithText("1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a large count keeps the exact number in the description`() {
        composeRule.setContent { CockpitTheme { CaptureButtonContent(CaptureButtonPlacement.Fab, 150, onClick = {}) } }

        composeRule.onNodeWithTag(CaptureButtonTags.BADGE, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(CaptureButtonTags.FAB).assertContentDescriptionEquals("Capture to Inbox, 150 to sync")
    }

    @Test
    fun `both placements call the click handler`() {
        var clicks = 0
        composeRule.setContent {
            CockpitTheme {
                CaptureButtonContent(CaptureButtonPlacement.Fab, 0, onClick = { clicks++ })
                CaptureButtonContent(CaptureButtonPlacement.AppBarAction, 0, onClick = { clicks++ })
            }
        }
        composeRule.onNodeWithTag(CaptureButtonTags.FAB).performClick()
        composeRule.onNodeWithTag(CaptureButtonTags.ACTION).performClick()

        assertEquals(2, clicks)
    }

    @Test
    @Config(qualifiers = "de")
    fun `the button is described in German`() {
        val count = MutableStateFlow(0)
        composeRule.setContent {
            CockpitTheme {
                val pending = produceState(0) { count.collect { value = it } }.value
                CaptureButtonContent(CaptureButtonPlacement.Fab, pending, onClick = {})
            }
        }
        composeRule.onNodeWithTag(CaptureButtonTags.FAB).assertContentDescriptionEquals("Im Eingang erfassen")

        count.value = 2
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CaptureButtonTags.FAB).assertContentDescriptionEquals("Im Eingang erfassen, 2 zu synchronisieren")
    }

    @Test
    fun `the app-bar action on a screen with its own FAB opens the capture sheet in the app's task`() {
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            CockpitTheme { CockpitShell(navController) }
        }
        composeRule.runOnUiThread { navController.navigate(CockpitDestination.Notes.route) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CaptureButtonTags.ACTION).performClick()

        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(CaptureActivity::class.java.name, started.component?.className)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(Intent.FLAG_ACTIVITY_SINGLE_TOP, started.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private companion object {
        val OWN_FAB =
            setOf(
                CockpitDestination.Notes,
                CockpitDestination.Files,
                CockpitDestination.Mail,
                CockpitDestination.Calendar,
                CockpitDestination.Contacts,
            )
    }
}
