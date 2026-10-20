package ch.sonensei.cockpit.auth

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Log out button only exists inside a session and calls its logout. */
@RunWith(AndroidJUnit4::class)
class LogoutButtonTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `settings show no log out button outside a session`() {
        composeRule.setContent { SettingsScreen() }

        composeRule.onNodeWithText("Log out").assertDoesNotExist()
    }

    @Test
    fun `the log out button in settings calls the session's logout`() {
        var logouts = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLogout provides { logouts++ }) { SettingsScreen() }
        }

        composeRule.onNodeWithText("Log out").performClick()

        assertEquals(1, logouts)
    }
}
