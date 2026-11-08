package ch.sonensei.cockpit.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import java.time.YearMonth

/** The date picker of the day bar: any earlier day can be chosen, no day after today. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class JournalDatePickerScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 22)
    private val calls = mutableListOf<String>()

    private fun show(date: LocalDate) {
        val state =
            JournalUiState(
                date = date,
                today = today,
                loading = false,
                unloaded = false,
                month = YearMonth.from(date),
            )
        composeRule.setContent {
            CockpitTheme { JournalContent(state, JournalCallbacks(openDay = { calls += "open $it" }), imageLoader = null) }
        }
        composeRule.onNodeWithContentDescription("Choose a day").performClick()
        composeRule.onNodeWithTag(JournalTags.DATE_PICKER).assertIsDisplayed()
    }

    /** The picker's button for a day of October 2026, found by its spoken date. */
    private fun october(day: Int) = composeRule.onNode(hasText("October $day, 2026", substring = true) and hasClickAction())

    @Test
    fun `another day chosen in the picker is the day opened`() {
        show(LocalDate.of(2026, 10, 5))

        october(12).performClick()
        composeRule.onNodeWithText("OK").performClick()

        assertEquals(listOf("open 2026-10-12"), calls)
        composeRule.onNodeWithTag(JournalTags.DATE_PICKER).assertDoesNotExist()
    }

    @Test
    fun `days up to today can be chosen, days after today cannot`() {
        show(today.minusDays(3))

        october(22).assertIsEnabled()
        october(23).assertIsNotEnabled()
        october(31).assertIsNotEnabled()
    }
}
