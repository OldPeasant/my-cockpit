package ch.sonensei.cockpit.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.CockpitApp
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the real activity under Robolectric with the manifest's Hilt application, as the launcher would. */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun runsInsideTheHiltCockpitApp() {
        assertTrue(composeRule.activity.application is CockpitApp)
    }

    @Test
    fun showsTheShellOnTodayAndNavigatesFromTheBottomBar() {
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Today)).assertIsDisplayed()
        composeRule.onNode(hasText("Today") and isSelectable()).assertIsSelected()

        composeRule.onNode(hasText("Calendar") and isSelectable()).performClick()

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Calendar)).assertIsDisplayed()
    }
}
