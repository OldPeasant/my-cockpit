package ch.sonensei.cockpit.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Back-stack behavior of the shell: More sub-screens, system back, and one entry per bottom-bar tab. */
@RunWith(AndroidJUnit4::class)
class CockpitShellNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        composeRule.setContent {
            navController = rememberNavController()
            CockpitTheme { CockpitShell(navController) }
        }
    }

    private fun tab(label: String): SemanticsNodeInteraction = composeRule.onNode(hasText(label) and isSelectable())

    private fun openMoreItem(label: String) {
        tab("More").performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText(label))
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
    }

    private fun assertShown(destination: CockpitDestination) {
        composeRule.onNodeWithTag(screenTag(destination)).assertIsDisplayed()
        CockpitDestination.entries.filter { it != destination }.forEach {
            composeRule.onNodeWithTag(screenTag(it)).assertDoesNotExist()
        }
    }

    private fun pressSystemBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun currentRoute(): String? = composeRule.runOnUiThread { navController.currentDestination?.route }

    private fun previousRoute(): String? = composeRule.runOnUiThread { navController.previousBackStackEntry?.destination?.route }

    @Test
    fun everyMoreItemOpensItsOwnScreenWithBackToTheList() {
        CockpitDestination.moreItems.forEach { destination ->
            val label = composeRule.activity.getString(destination.labelRes)

            openMoreItem(label)

            assertShown(destination)
            assertEquals(destination.route, currentRoute())
            tab("More").assertIsSelected()
            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.waitForIdle()
            assertShown(CockpitDestination.More)
        }
    }

    @Test
    fun bottomBarScreensHaveNoBackButton() {
        CockpitDestination.bottomBar.forEach { destination ->
            tab(composeRule.activity.getString(destination.labelRes)).performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
        }
    }

    @Test
    fun systemBackFromAMoreScreenReturnsToTheMoreList() {
        openMoreItem("Journal")
        assertShown(CockpitDestination.Journal)

        pressSystemBack()

        assertShown(CockpitDestination.More)
        tab("More").assertIsSelected()
    }

    @Test
    fun systemBackFromATabReturnsToToday() {
        tab("Inbox").performClick()
        tab("Mail").performClick()

        pressSystemBack()

        assertShown(CockpitDestination.Today)
        tab("Today").assertIsSelected()
    }

    @Test
    fun switchingTabsKeepsOnlyTodayBelowTheCurrentTab() {
        tab("Inbox").performClick()
        tab("Calendar").performClick()
        tab("Inbox").performClick()
        tab("Mail").performClick()
        composeRule.waitForIdle()

        assertEquals("mail", currentRoute())
        assertEquals("today", previousRoute())
    }

    @Test
    fun reselectingTheCurrentTabDoesNotStackItTwice() {
        tab("Inbox").performClick()
        tab("Inbox").performClick()
        composeRule.waitForIdle()

        assertEquals("inbox", currentRoute())
        assertEquals("today", previousRoute())
    }

    @Test
    fun reselectingTodayLeavesNothingBelowIt() {
        tab("Calendar").performClick()
        tab("Today").performClick()
        composeRule.waitForIdle()

        assertEquals("today", currentRoute())
        assertNull(previousRoute())
    }

    @Test
    fun everyDestinationLabelResolvesToADistinctNonBlankString() {
        val labels = CockpitDestination.entries.map { composeRule.activity.getString(it.labelRes) }

        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun leavingMoreFromASubScreenAndComingBackShowsTheMoreList() {
        openMoreItem("Habits")
        tab("Inbox").performClick()
        tab("More").performClick()
        composeRule.waitForIdle()

        assertShown(CockpitDestination.More)
    }
}
