package ch.sonensei.cockpit.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** A photo that was not added because another day opened tells the user, without offering a load retry. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class JournalPhotoDayChangedScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a photo not added because the day changed says so and can be dismissed`() {
        val calls = mutableListOf<String>()
        val state =
            JournalUiState(
                date = LocalDate.of(2026, 10, 21),
                today = LocalDate.of(2026, 10, 22),
                loading = false,
                unloaded = false,
                message = JournalMessage.PHOTO_DAY_CHANGED,
            )
        composeRule.setContent {
            CockpitTheme { JournalContent(state, JournalCallbacks(dismissMessage = { calls += "dismiss" }), imageLoader = null) }
        }

        composeRule.onNodeWithText("another day was opened meanwhile", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.RETRY_LOAD).assertDoesNotExist()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), calls)
    }
}
