package ch.sonensei.cockpit.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.CockpitApp
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.LoginTags
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
    fun startsOnTheLoginScreenWithoutStoredTokens() {
        // The shell after login is covered by auth.AppStartRoutingTest.
        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()
    }
}
