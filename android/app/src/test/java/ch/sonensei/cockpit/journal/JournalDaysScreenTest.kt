package ch.sonensei.cockpit.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Day navigation, mood, tags, month calendar and "on this day" on the journal screen. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class JournalDaysScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 22)
    private val entry =
        JournalEntry(UUID.fromString("00000000-0000-7000-8000-000000000001"), today, "Walk", 4, listOf("sport"), Instant.EPOCH, 1)
    private val calls = mutableListOf<String>()

    private val callbacks =
        JournalCallbacks(
            previousDay = { calls += "previous" },
            nextDay = { calls += "next" },
            openToday = { calls += "today" },
            openDay = { calls += "open $it" },
            showMonth = { calls += "month $it" },
            setMood = { calls += "mood $it" },
            setTags = { calls += "tags $it" },
            tidyTags = { calls += "tidy" },
        )

    private fun state(
        date: LocalDate = today,
        onThisDay: List<JournalEntry> = emptyList(),
        onThisDayFailed: Boolean = false,
        monthDays: Map<LocalDate, Int?> = emptyMap(),
        monthFailed: Boolean = false,
    ) = JournalUiState(
        date = date,
        today = today,
        entry = entry.copy(date = date),
        body = "Walk",
        mood = 4,
        tags = listOf("sport"),
        tagsText = "sport",
        loading = false,
        unloaded = false,
        month = YearMonth.from(date),
        monthDays = monthDays,
        monthFailed = monthFailed,
        onThisDay = onThisDay,
        onThisDayFailed = onThisDayFailed,
    )

    private fun show(state: JournalUiState) =
        composeRule.setContent { CockpitTheme { JournalContent(state, callbacks, imageLoader = null) } }

    @Test
    fun `today offers the previous day but neither the next day nor a Today button`() {
        show(state())

        composeRule.onNodeWithTag(JournalTags.RELATIVE).assertIsDisplayed()
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.NEXT_DAY).assertIsNotEnabled()
        composeRule.onNodeWithTag(JournalTags.TODAY).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Previous day").performClick()
        assertEquals(listOf("previous"), calls)
    }

    @Test
    fun `an earlier day goes forward and back to today`() {
        show(state(date = today.minusDays(1)))

        composeRule.onNodeWithText("Yesterday").assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.NEXT_DAY).performClick()
        composeRule.onNodeWithTag(JournalTags.TODAY).performClick()
        assertEquals(listOf("next", "today"), calls)
    }

    @Test
    fun `the date picker opens a chosen day`() {
        show(state(date = LocalDate.of(2026, 10, 5)))

        composeRule.onNodeWithContentDescription("Choose a day").performClick()
        composeRule.onNodeWithTag(JournalTags.DATE_PICKER).assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()
        assertEquals(listOf("open 2026-10-05"), calls)
    }

    @Test
    fun `the mood is picked from five labelled choices`() {
        show(state())

        composeRule.onNodeWithTag(JournalTags.mood(4)).assertIsSelected()
        composeRule.onNodeWithText("Great").performClick()
        assertEquals(listOf("mood 5"), calls)
    }

    @Test
    fun `tags are edited as text and tidied when the field is left`() {
        show(state())

        composeRule.onNodeWithTag(JournalTags.TAGS).performScrollTo().performTextReplacement("sport, travel")
        composeRule.onNodeWithTag(JournalTags.mood(1)).performScrollTo().performClick()
        assertEquals("tags sport, travel", calls.first())
    }

    @Test
    fun `the month calendar marks days with an entry and a tap opens one`() {
        val marked = LocalDate.of(2026, 10, 3)
        show(state(monthDays = mapOf(marked to 5)))

        composeRule.onNodeWithTag(JournalTags.CALENDAR).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show calendar").performClick()
        composeRule.onNodeWithTag(JournalTags.CALENDAR).assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.MONTH_TITLE).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("has an entry", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.day(today)).assertIsSelected()
        composeRule.onNodeWithTag(JournalTags.day(today.plusDays(1))).assertIsNotEnabled()
        composeRule.onNodeWithTag(JournalTags.NEXT_MONTH).assertIsNotEnabled()

        composeRule.onNodeWithTag(JournalTags.day(marked)).performClick()
        composeRule.onNodeWithTag(JournalTags.PREVIOUS_MONTH).performClick()
        assertEquals(listOf("open $marked", "month 2026-09"), calls)
    }

    @Test
    fun `a failed calendar month can be retried`() {
        show(state(monthFailed = true))

        composeRule.onNodeWithTag(JournalTags.CALENDAR_TOGGLE).performClick()
        composeRule.onNodeWithText("The calendar could not be loaded.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.CALENDAR_RETRY).performScrollTo().performClick()
        assertEquals(listOf("month 2026-10"), calls)
    }

    @Test
    fun `on this day lists earlier years with an excerpt and opens them`() {
        val lastYear = entry.copy(date = today.minusYears(1), bodyMd = "# Rain\nStayed **inside**")
        val longAgo = entry.copy(date = today.minusYears(3), bodyMd = "", mood = 5)
        show(state(onThisDay = listOf(lastYear, longAgo)))

        composeRule.onNodeWithText("2025, a year ago").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Rain Stayed inside").assertExists()
        composeRule.onNodeWithText("2023, 3 years ago").assertExists()
        composeRule.onNodeWithTag(JournalTags.earlier(lastYear.date)).performScrollTo().performClick()
        assertEquals(listOf("open ${lastYear.date}"), calls)
    }

    @Test
    fun `on this day says when there is nothing, or when it failed`() {
        show(state(onThisDayFailed = true))
        composeRule.onNodeWithText("Earlier years could not be loaded.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a day that could not be saved says why it stays open`() {
        show(state().copy(message = JournalMessage.DAY_NOT_SAVED))
        composeRule.onNodeWithText("This day is not saved yet", substring = true).assertIsDisplayed()
    }
}
