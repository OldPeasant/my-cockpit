package ch.sonensei.cockpit.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
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
import ch.sonensei.cockpit.gtd.task.TaskRoutes
import ch.sonensei.cockpit.search.SearchRoutes
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Bottom-bar selection and Back with New in the bottom bar and Inbox under More: stacked screens (a task, search, an
 * item opened from a task) keep the tab of the screen below them, and the Dashboard's cards open Inbox/Today on top
 * of a Dashboard that its tab returns to with nothing below it. The real More list, plain content for the other
 * destinations; the Dashboard has two buttons standing in for its Inbox and Today cards (they call the same `onOpen`
 * as the real cards).
 */
@RunWith(AndroidJUnit4::class)
class CockpitShellSelectionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    private val task = UUID.fromString("00000000-0000-7000-8000-00000000a001")
    private val otherTask = UUID.fromString("00000000-0000-7000-8000-00000000a002")
    private val mail = UUID.fromString("00000000-0000-7000-8000-00000000a003")

    @Before
    fun setUp() {
        composeRule.setContent {
            navController = rememberNavController()
            CockpitTheme {
                CockpitShellFrame(navController, prompts = {}) { destination, onOpen, modifier ->
                    if (destination == CockpitDestination.More) {
                        MoreScreen(onOpen, modifier)
                        return@CockpitShellFrame
                    }
                    Column(modifier.fillMaxSize().testTag(screenTag(destination))) {
                        if (destination == CockpitDestination.Dashboard) {
                            Button(onClick = { onOpen(CockpitDestination.Inbox) }, Modifier.testTag(INBOX_CARD)) { Text("Inbox card") }
                            Button(onClick = { onOpen(CockpitDestination.Today) }, Modifier.testTag(TODAY_CARD)) { Text("Today card") }
                        }
                    }
                }
            }
        }
    }

    private fun tab(label: String): SemanticsNodeInteraction = composeRule.onNode(hasText(label) and isSelectable())

    private val tabs = listOf("Dashboard", "New", "Calendar", "Mail", "More")

    /** Exactly [label] is highlighted in the bottom bar. */
    private fun assertOnlySelected(label: String) {
        tabs.forEach { if (it == label) tab(it).assertIsSelected() else tab(it).assertIsNotSelected() }
    }

    private fun open(route: String) {
        composeRule.runOnUiThread { navController.navigate(route) }
        composeRule.waitForIdle()
    }

    private fun clickTab(label: String) {
        tab(label).performClick()
        composeRule.waitForIdle()
    }

    private fun openMoreItem(label: String) {
        clickTab("More")
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText(label))
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
    }

    private fun clickCard(tag: String) {
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitForIdle()
    }

    private fun pressSystemBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun currentRoute(): String? = composeRule.runOnUiThread { navController.currentDestination?.route }

    private fun previousRoute(): String? = composeRule.runOnUiThread { navController.previousBackStackEntry?.destination?.route }

    private fun backStackRoutes(): List<String?> =
        composeRule.runOnUiThread {
            // The graph's own entry has no route; only the screens count.
            navController.currentBackStack.value.mapNotNull { it.destination.route }
        }

    // Selection on the New view and on what is opened from it.

    @Test
    fun aTaskOpenedOnTopOfNewKeepsNewSelected() {
        clickTab("New")
        assertOnlySelected("New")

        open(TaskRoutes.detail(task))

        assertEquals(TaskRoutes.DETAIL, currentRoute())
        assertEquals(CockpitDestination.New.route, previousRoute())
        assertOnlySelected("New")
    }

    @Test
    fun aMailOrTaskOpenedFromATaskOnNewKeepsNewSelected() {
        clickTab("New")
        open(TaskRoutes.detail(task))

        open(SearchRoutes.item("EMAIL", mail))
        assertEquals(SearchRoutes.ITEM, currentRoute())
        assertOnlySelected("New")

        open(TaskRoutes.detail(otherTask))
        assertEquals(TaskRoutes.DETAIL, currentRoute())
        assertOnlySelected("New")
    }

    @Test
    fun searchOpenedFromNewKeepsNewSelectedAndBackReturnsToNew() {
        clickTab("New")

        composeRule.onNodeWithTag(SEARCH_ACTION_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(SearchRoutes.SEARCH, currentRoute())
        assertOnlySelected("New")

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        assertEquals(CockpitDestination.New.route, currentRoute())
        assertOnlySelected("New")
    }

    @Test
    fun backFromATaskOnNewReturnsToNewWithNewStillSelected() {
        clickTab("New")
        open(TaskRoutes.detail(task))

        pressSystemBack()

        assertEquals(CockpitDestination.New.route, currentRoute())
        composeRule.onNodeWithTag(screenTag(CockpitDestination.New)).assertIsDisplayed()
        assertOnlySelected("New")
    }

    @Test
    fun aTaskOpenedOnTopOfTheDashboardKeepsDashboardSelected() {
        open(TaskRoutes.detail(task))

        assertEquals(TaskRoutes.DETAIL, currentRoute())
        assertOnlySelected("Dashboard")
    }

    // Selection on the Inbox (a More screen).

    @Test
    fun inboxOpenedFromMoreSelectsMoreAndATaskOnItKeepsMoreSelected() {
        openMoreItem("Inbox")
        assertEquals(CockpitDestination.Inbox.route, currentRoute())
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Inbox)).assertIsDisplayed()
        assertOnlySelected("More")

        open(TaskRoutes.detail(task))

        assertEquals(TaskRoutes.DETAIL, currentRoute())
        assertEquals(CockpitDestination.Inbox.route, previousRoute())
        assertOnlySelected("More")
    }

    @Test
    fun inboxOpenedFromTheDashboardCardSelectsMore() {
        clickCard(INBOX_CARD)

        assertEquals(CockpitDestination.Inbox.route, currentRoute())
        assertEquals(CockpitDestination.Dashboard.route, previousRoute())
        assertOnlySelected("More")
    }

    @Test
    fun routesInboxAndNewStillOpenTheirScreens() {
        open(CockpitDestination.Inbox.route)
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Inbox)).assertIsDisplayed()
        assertOnlySelected("More")

        open(CockpitDestination.New.route)
        composeRule.onNodeWithTag(screenTag(CockpitDestination.New)).assertIsDisplayed()
        assertOnlySelected("New")
    }

    // System Back from New and Inbox.

    @Test
    fun systemBackFromNewReturnsToTheDashboard() {
        clickTab("New")
        assertEquals(listOf("dashboard", "new"), backStackRoutes())

        pressSystemBack()

        assertEquals(CockpitDestination.Dashboard.route, currentRoute())
        assertNull(previousRoute())
        assertOnlySelected("Dashboard")
    }

    @Test
    fun systemBackFromInboxOpenedFromMoreReturnsToTheMoreList() {
        openMoreItem("Inbox")

        pressSystemBack()

        assertEquals(CockpitDestination.More.route, currentRoute())
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).assertIsDisplayed()
        assertOnlySelected("More")

        pressSystemBack()
        assertEquals(CockpitDestination.Dashboard.route, currentRoute())
        assertNull(previousRoute())
    }

    @Test
    fun backArrowFromInboxOpenedFromMoreReturnsToTheMoreList() {
        openMoreItem("Inbox")

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()

        assertEquals(CockpitDestination.More.route, currentRoute())
    }

    @Test
    fun systemBackFromInboxOpenedFromTheDashboardCardReturnsToTheDashboard() {
        clickCard(INBOX_CARD)

        pressSystemBack()

        assertEquals(CockpitDestination.Dashboard.route, currentRoute())
        assertNull(previousRoute())
        assertOnlySelected("Dashboard")
    }

    // The Dashboard tab after a card opened a screen on top of it.

    @Test
    fun dashboardTabAfterTheInboxCardShowsTheDashboardWithNothingBelow() {
        clickCard(INBOX_CARD)

        clickTab("Dashboard")

        assertEquals(listOf("dashboard"), backStackRoutes())
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertIsDisplayed()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Inbox)).assertDoesNotExist()
        assertOnlySelected("Dashboard")
    }

    @Test
    fun dashboardTabAfterTheTodayCardShowsTheDashboardWithNothingBelow() {
        clickCard(TODAY_CARD)
        assertEquals(CockpitDestination.Today.route, currentRoute())
        assertOnlySelected("More")

        clickTab("Dashboard")

        assertEquals(listOf("dashboard"), backStackRoutes())
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Today)).assertDoesNotExist()
        assertOnlySelected("Dashboard")
    }

    /**
     * Regression: leaving the Inbox opened from the card for another tab saves the Dashboard's stack (with the Inbox);
     * coming back via the Dashboard tab must not restore that Inbox on top of the Dashboard.
     */
    @Test
    fun dashboardTabAfterTheInboxCardAndAnotherTabDoesNotBringTheInboxBack() {
        clickCard(INBOX_CARD)
        clickTab("Calendar")
        assertEquals(listOf("dashboard", "calendar"), backStackRoutes())

        clickTab("Dashboard")

        assertEquals(listOf("dashboard"), backStackRoutes())
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertIsDisplayed()
        assertOnlySelected("Dashboard")
    }

    @Test
    fun dashboardTabAfterTheTodayCardAndNewDoesNotBringTodayBack() {
        clickCard(TODAY_CARD)
        clickTab("New")
        assertEquals(listOf("dashboard", "new"), backStackRoutes())

        clickTab("Dashboard")

        assertEquals(listOf("dashboard"), backStackRoutes())
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Today)).assertDoesNotExist()
    }

    /** The New tab still keeps its own saved stack: a task left open on New comes back with the New tab. */
    @Test
    fun newTabRestoresTheTaskLeftOpenOnIt() {
        clickTab("New")
        open(TaskRoutes.detail(task))
        clickTab("Calendar")

        clickTab("New")

        assertEquals(listOf("dashboard", "new", TaskRoutes.DETAIL), backStackRoutes())
        assertOnlySelected("New")
    }

    // Chains of stacked screens: each entry inherits the destination of the screen below it.

    @Test
    fun aTaskOpenedFromATaskOnNewIsRestoredWithNewSelectedAndBackWalksDownWithNewSelected() {
        clickTab("New")
        open(TaskRoutes.detail(task))
        open(TaskRoutes.detail(otherTask))
        clickTab("Calendar")
        assertOnlySelected("Calendar")

        clickTab("New")

        assertEquals(listOf("dashboard", "new", TaskRoutes.DETAIL, TaskRoutes.DETAIL), backStackRoutes())
        assertOnlySelected("New")
        pressSystemBack()
        assertEquals(listOf("dashboard", "new", TaskRoutes.DETAIL), backStackRoutes())
        assertOnlySelected("New")
        pressSystemBack()
        assertEquals(CockpitDestination.New.route, currentRoute())
        assertOnlySelected("New")
    }

    @Test
    fun theSameTaskOpenedOnTwoTabsKeepsTheTabItLiesOnSelected() {
        clickTab("New")
        open(TaskRoutes.detail(task))
        clickTab("Calendar")
        open(TaskRoutes.detail(task))
        assertEquals(CockpitDestination.Calendar.route, previousRoute())
        assertOnlySelected("Calendar")

        clickTab("New")
        assertEquals(CockpitDestination.New.route, previousRoute())
        assertOnlySelected("New")

        clickTab("Calendar")
        assertEquals(listOf("dashboard", "calendar", TaskRoutes.DETAIL), backStackRoutes())
        assertOnlySelected("Calendar")
    }

    @Test
    fun aTaskOpenedFromASearchItemOpenedFromSearchOnNewKeepsNewSelected() {
        clickTab("New")
        composeRule.onNodeWithTag(SEARCH_ACTION_TAG).performClick()
        composeRule.waitForIdle()
        open(SearchRoutes.item("EMAIL", mail))
        assertOnlySelected("New")

        open(TaskRoutes.detail(task))

        assertEquals(listOf("dashboard", "new", SearchRoutes.SEARCH, SearchRoutes.ITEM, TaskRoutes.DETAIL), backStackRoutes())
        assertOnlySelected("New")
        pressSystemBack()
        pressSystemBack()
        assertEquals(SearchRoutes.SEARCH, currentRoute())
        assertOnlySelected("New")
    }

    @Test
    fun anItemOpenedFromATaskOnTheInboxOpenedFromTheCardKeepsMoreSelected() {
        clickCard(INBOX_CARD)
        open(TaskRoutes.detail(task))
        open(SearchRoutes.item("EMAIL", mail))

        assertEquals(listOf("dashboard", "inbox", TaskRoutes.DETAIL, SearchRoutes.ITEM), backStackRoutes())
        assertOnlySelected("More")

        clickTab("Dashboard")
        assertEquals(listOf("dashboard"), backStackRoutes())
        assertOnlySelected("Dashboard")
    }

    private companion object {
        const val INBOX_CARD = "test-card-inbox"
        const val TODAY_CARD = "test-card-today"
    }
}
