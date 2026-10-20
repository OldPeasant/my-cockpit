package ch.sonensei.cockpit.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceholderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun placeholderShowsTheFeatureTitleAndTheNotBuiltHint() {
        composeRule.setContent { PlaceholderScreen(R.string.nav_inbox, Icons.Outlined.Inbox) }

        composeRule.onNodeWithText("Inbox").assertIsDisplayed()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertIsDisplayed()
    }

    @Test
    fun placeholderDoesNotShowOtherText() {
        // Proves the Robolectric semantics tree is real: an absent text is not found.
        composeRule.setContent { PlaceholderScreen(R.string.nav_inbox, Icons.Outlined.Inbox) }

        composeRule.onNodeWithText("Some other screen").assertDoesNotExist()
    }
}
