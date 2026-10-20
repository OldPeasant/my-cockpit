package ch.sonensei.cockpit.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceholderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun placeholderTextNamesTheApp() {
        assertEquals("My Cockpit: scaffold", placeholderText("My Cockpit"))
    }

    @Test
    fun placeholderScreenShowsTheScaffoldText() {
        composeRule.setContent { PlaceholderScreen() }

        composeRule.onNodeWithText("My Cockpit: scaffold").assertIsDisplayed()
    }
}
