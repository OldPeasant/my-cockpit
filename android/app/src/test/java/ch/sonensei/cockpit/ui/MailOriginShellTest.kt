package ch.sonensei.cockpit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * A mail opened from a task's links lies on the shell's item route on top of the task: back returns to the task, and
 * back again to the screen the task was opened from.
 */
@RunWith(AndroidJUnit4::class)
class MailOriginShellTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    private val task = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val mail = UUID.fromString("00000000-0000-7000-8000-000000000002")

    private fun setFrame() =
        composeRule.setContent {
            navController = rememberNavController()
            CockpitTheme {
                CockpitShellFrame(navController, prompts = {}) { destination, _, modifier ->
                    Box(modifier.fillMaxSize().testTag(screenTag(destination)))
                }
            }
        }

    private fun open(route: String) {
        composeRule.runOnUiThread { navController.navigate(route) }
        composeRule.waitForIdle()
    }

    private fun currentRoute(): String? = composeRule.runOnUiThread { navController.currentDestination?.route }

    private fun back() {
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun aMailOpenedFromATaskLinkReturnsToTheTaskThenToItsOrigin() {
        setFrame()
        open(CockpitDestination.Next.route)
        open(TaskRoutes.detail(task))
        open(SearchRoutes.item("EMAIL", mail))
        assertEquals(SearchRoutes.ITEM, currentRoute())

        back()
        assertEquals(TaskRoutes.DETAIL, currentRoute())

        back()
        assertEquals(CockpitDestination.Next.route, currentRoute())
    }
}
