package ch.sonensei.cockpit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.capture.entry.CaptureButtonTags
import ch.sonensei.cockpit.search.SearchRoutes
import ch.sonensei.cockpit.search.SearchTags
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The app bar's search action on every screen of the shell, the search screen's frame, and the way back. */
@RunWith(AndroidJUnit4::class)
class CockpitShellSearchTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    /** The real frame with plain content per destination, so every destination can be visited quickly. */
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

    private fun assertTitle(title: String) = composeRule.onNode(hasText(title) and !hasContentDescription(title)).assertExists()

    @Test
    fun everyDestinationHasTheSearchActionAndItOpensSearchWithBackToThatScreen() {
        setFrame()
        CockpitDestination.entries.forEach { destination ->
            open(destination.route)
            composeRule.onNodeWithTag(screenTag(destination)).assertIsDisplayed()

            composeRule.onNodeWithTag(SEARCH_ACTION_TAG).assertIsDisplayed().performClick()
            composeRule.waitForIdle()

            assertEquals("from $destination", SearchRoutes.SEARCH, currentRoute())
            composeRule.onNodeWithTag(SEARCH_SCREEN_TAG).assertIsDisplayed()
            composeRule.onNodeWithTag(screenTag(destination)).assertDoesNotExist()
            assertTitle("Search")
            // No search action on the search screen itself.
            composeRule.onNodeWithTag(SEARCH_ACTION_TAG).assertDoesNotExist()

            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.waitForIdle()

            assertEquals("back to $destination", destination.route, currentRoute())
            composeRule.onNodeWithTag(screenTag(destination)).assertIsDisplayed()
            composeRule.onNodeWithTag(SEARCH_SCREEN_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun theSearchActionHasATalkBackLabel() {
        setFrame()

        composeRule.onNodeWithTag(SEARCH_ACTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    @Test
    fun screensWithTheirOwnFabKeepTheCaptureActionNextToSearch() {
        setFrame()
        CockpitDestination.withOwnFab.forEach { destination ->
            open(destination.route)

            composeRule.onNodeWithTag(SEARCH_ACTION_TAG).assertIsDisplayed()
            composeRule.onNodeWithTag(CaptureButtonTags.ACTION).assertIsDisplayed()
        }
    }

    @Test
    fun theSearchScreenHasNoCaptureFab() {
        setFrame()
        composeRule.onNodeWithTag(SEARCH_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CaptureButtonTags.FAB).assertDoesNotExist()
    }

    @Test
    fun anItemOpenedFromSearchIsTitledWithItsTypeAndBackReturnsToSearch() {
        setFrame()
        composeRule.onNodeWithTag(SEARCH_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        open(SearchRoutes.item("NOTE", UUID.fromString("00000000-0000-7000-8000-000000000007")))
        assertEquals(SearchRoutes.ITEM, currentRoute())
        assertTitle("Notes")
        composeRule.onNodeWithTag(SEARCH_ACTION_TAG).assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()

        assertEquals(SearchRoutes.SEARCH, currentRoute())
        assertTitle("Search")
    }

    @Test
    fun anItemRouteWithABrokenIdShowsNothingAndDoesNotCrash() {
        setFrame()
        open("search/item/CONTACT/not-a-uuid")

        assertEquals(SearchRoutes.ITEM, currentRoute())
        assertTitle("Contacts")
    }

    @Test
    fun theRealShellOpensTheSearchScreenWithItsHint() {
        composeRule.setContent { CockpitTheme { CockpitShell() } }

        composeRule.onNodeWithTag(SEARCH_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SEARCH_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SearchTags.HINT).assertIsDisplayed()
        composeRule.onNodeWithText("Search everything").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Today)).assertIsDisplayed()
    }
}
