package ch.sonensei.cockpit.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CockpitShellTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        composeRule.setContent { CockpitTheme { CockpitShell() } }
    }

    private fun tab(label: String): SemanticsNodeInteraction = composeRule.onNode(hasText(label) and isSelectable())

    /** The More list is lazy and the Robolectric screen is small: scroll an item into view before using it. */
    private fun moreItem(label: String): SemanticsNodeInteraction {
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText(label))
        return composeRule.onNodeWithText(label)
    }

    private fun assertShown(destination: CockpitDestination) {
        composeRule.onNodeWithTag(screenTag(destination)).assertIsDisplayed()
        CockpitDestination.entries.filter { it != destination }.forEach {
            composeRule.onNodeWithTag(screenTag(it)).assertDoesNotExist()
        }
    }

    @Test
    fun startsOnTodayWithTodaySelected() {
        assertShown(CockpitDestination.Today)
        tab("Today").assertIsSelected()
        tab("Inbox").assertIsNotSelected()
    }

    @Test
    fun bottomBarSwitchesBetweenAllMainDestinations() {
        listOf(
            "Inbox" to CockpitDestination.Inbox,
            "Calendar" to CockpitDestination.Calendar,
            "Mail" to CockpitDestination.Mail,
            "More" to CockpitDestination.More,
            "Today" to CockpitDestination.Today,
        ).forEach { (label, destination) ->
            tab(label).performClick()
            composeRule.waitForIdle()

            assertShown(destination)
            tab(label).assertIsSelected()
        }
    }

    @Test
    fun moreListsEverySecondaryDestination() {
        tab("More").performClick()

        listOf(
            "Next",
            "Projects",
            "Waiting",
            "Someday",
            "Notes",
            "Files",
            "Contacts",
            "Bookmarks",
            "Journal",
            "Habits",
            "Review",
            "Settings",
        ).forEach { moreItem(it).assertExists() }
    }

    @Test
    fun openingAMoreItemShowsItsScreenKeepsMoreSelectedAndBackReturnsToTheList() {
        tab("More").performClick()
        moreItem("Notes").performClick()

        assertShown(CockpitDestination.Notes)
        tab("More").assertIsSelected()

        composeRule.onNodeWithContentDescription("Back").performClick()
        assertShown(CockpitDestination.More)
    }

    @Test
    fun reselectingMoreFromASecondaryScreenReturnsToTheList() {
        tab("More").performClick()
        moreItem("Settings").performClick()
        assertShown(CockpitDestination.Settings)

        tab("More").performClick()
        assertShown(CockpitDestination.More)
    }

    @Test
    fun tabKeepsItsPlaceWhenComingBack() {
        tab("Inbox").performClick()
        tab("Calendar").performClick()
        tab("Inbox").performClick()

        assertShown(CockpitDestination.Inbox)
    }
}
