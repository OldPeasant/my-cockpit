package ch.sonensei.cockpit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.task.TaskRoutes
import ch.sonensei.cockpit.search.SearchRoutes
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Bottom-bar selection on stacked screens after the activity is recreated (rotation, process death): the back stack
 * comes back from the saved state, and a stacked screen must still highlight the tab of the screen it lies on, also
 * when it lies on another stacked screen (a mail or task opened from a task opened on New).
 */
@RunWith(AndroidJUnit4::class)
class CockpitShellSelectionRestoreTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    private val task = UUID.fromString("00000000-0000-7000-8000-00000000b001")
    private val otherTask = UUID.fromString("00000000-0000-7000-8000-00000000b002")
    private val mail = UUID.fromString("00000000-0000-7000-8000-00000000b003")

    private val restoration by lazy { StateRestorationTester(composeRule) }

    private fun showShell() {
        restoration.setContent {
            navController = rememberNavController()
            CockpitTheme {
                CockpitShellFrame(navController, prompts = {}) { destination, _, modifier ->
                    Box(modifier.fillMaxSize().testTag(screenTag(destination)))
                }
            }
        }
    }

    private fun tab(label: String): SemanticsNodeInteraction = composeRule.onNode(hasText(label) and isSelectable())

    private val tabs = listOf("Dashboard", "New", "Calendar", "Mail", "More")

    private fun assertOnlySelected(label: String) {
        tabs.forEach { if (it == label) tab(it).assertIsSelected() else tab(it).assertIsNotSelected() }
    }

    private fun open(route: String) {
        composeRule.runOnUiThread { navController.navigate(route) }
        composeRule.waitForIdle()
    }

    private fun currentRoute(): String? = composeRule.runOnUiThread { navController.currentDestination?.route }

    private fun recreate() {
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
    }

    @Test
    fun aTaskOnNewKeepsNewSelectedAfterRecreation() {
        showShell()
        tab("New").performClick()
        composeRule.waitForIdle()
        open(TaskRoutes.detail(task))

        recreate()

        assertEquals(TaskRoutes.DETAIL, currentRoute())
        assertOnlySelected("New")
    }

    @Test
    fun aMailOpenedFromATaskOnNewKeepsNewSelectedAfterRecreation() {
        showShell()
        tab("New").performClick()
        composeRule.waitForIdle()
        open(TaskRoutes.detail(task))
        open(SearchRoutes.item("EMAIL", mail))
        assertOnlySelected("New")

        recreate()

        assertEquals(SearchRoutes.ITEM, currentRoute())
        assertOnlySelected("New")
    }

    @Test
    fun aTaskOpenedFromATaskOnCalendarKeepsCalendarSelectedAfterRecreationAndBack() {
        showShell()
        tab("Calendar").performClick()
        composeRule.waitForIdle()
        open(TaskRoutes.detail(task))
        open(TaskRoutes.detail(otherTask))

        recreate()
        assertOnlySelected("Calendar")

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(TaskRoutes.DETAIL, currentRoute())
        assertOnlySelected("Calendar")
        val below = composeRule.runOnUiThread { navController.previousBackStackEntry?.destination?.route }
        assertEquals(CockpitDestination.Calendar.route, below)
    }
}
