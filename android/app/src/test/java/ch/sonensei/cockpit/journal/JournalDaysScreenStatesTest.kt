package ch.sonensei.cockpit.journal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasContentDescription
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** States of the journal's day navigation, mood, tags, calendar and "on this day" not covered elsewhere. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class JournalDaysScreenStatesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 22)
    private val entry =
        JournalEntry(UUID.fromString("00000000-0000-7000-8000-000000000002"), today, "Lake swim", 2, listOf("sport"), Instant.EPOCH, 1)
    private val calls = mutableListOf<String>()

    private val callbacks =
        JournalCallbacks(
            setBody = { calls += "body $it" },
            openDay = { calls += "open $it" },
            showMonth = { calls += "month $it" },
            setMood = { calls += "mood $it" },
            setTags = { calls += "tags $it" },
            tidyTags = { calls += "tidy" },
        )

    private fun loaded(
        date: LocalDate = today,
        editing: Boolean = false,
    ) = JournalUiState(
        date = date,
        today = today,
        entry = entry.copy(date = date),
        body = "Lake swim",
        mood = 2,
        tags = listOf("sport"),
        tagsText = "sport",
        loading = false,
        unloaded = false,
        editing = editing,
        month = YearMonth.from(date),
    )

    private fun show(state: JournalUiState) =
        composeRule.setContent { CockpitTheme { JournalContent(state, callbacks, imageLoader = null) } }

    @Test
    fun `leaving the tags field for the text tidies the tags`() {
        // The field shows what was typed, as the view model does; a frozen state would make the field reset it.
        var state by mutableStateOf(loaded(editing = true))
        val typing =
            callbacks.copy(setTags = {
                calls += "tags $it"
                state = state.copy(tagsText = it)
            })
        composeRule.setContent { CockpitTheme { JournalContent(state, typing, imageLoader = null) } }

        composeRule.onNodeWithTag(JournalTags.TAGS).performScrollTo().performTextReplacement("sport,,  sea")
        composeRule.onNodeWithTag(JournalTags.BODY).performScrollTo().performClick()

        assertEquals(listOf("tags sport,,  sea", "tidy"), calls)
    }

    @Test
    fun `mood and tags are locked while a conflict waits`() {
        show(loaded().copy(conflict = JournalConflict(entry.copy(bodyMd = "Web text"))))

        composeRule.onNodeWithTag(JournalTags.mood(1)).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(JournalTags.TAGS).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(JournalTags.mood(1)).performClick()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `while the day loads there is no mood, no tags and no earlier years, but the days can be changed`() {
        show(JournalUiState(date = today.minusDays(2), today = today))

        composeRule.onNodeWithTag(JournalTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.mood(3)).assertDoesNotExist()
        composeRule.onNodeWithTag(JournalTags.TAGS).assertDoesNotExist()
        composeRule.onNodeWithTag(JournalTags.ON_THIS_DAY).assertDoesNotExist()
        composeRule.onNodeWithTag(JournalTags.PREVIOUS_DAY).assertIsEnabled()
        composeRule.onNodeWithTag(JournalTags.NEXT_DAY).assertIsEnabled()
        composeRule.onNodeWithTag(JournalTags.TODAY).assertIsDisplayed()
    }

    @Test
    fun `a day whose load failed still lists its earlier years but offers no mood or tags`() {
        show(JournalUiState(date = today, loading = false, unloaded = true, message = JournalMessage.LOAD_FAILED))

        composeRule.onNodeWithTag(JournalTags.mood(3)).assertDoesNotExist()
        composeRule.onNodeWithTag(JournalTags.TAGS).assertDoesNotExist()
        composeRule.onNodeWithTag(JournalTags.ON_THIS_DAY).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `no mood chosen leaves every choice unselected`() {
        show(loaded().copy(mood = null))

        (1..5).forEach { composeRule.onNodeWithTag(JournalTags.mood(it)).performScrollTo().assertIsNotSelected() }
    }

    @Test
    fun `on this day says when there are no earlier entries`() {
        show(loaded())

        composeRule.onNodeWithText("No entries from this day in earlier years", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `while the earlier years load, on this day shows progress instead of claiming there are none`() {
        show(loaded().copy(onThisDayLoading = true))

        composeRule.onNodeWithTag(JournalTags.ON_THIS_DAY_LOADING).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Loading earlier years…").assertIsDisplayed()
        composeRule.onNodeWithText("No entries from this day in earlier years", substring = true).assertDoesNotExist()
    }

    @Test
    fun `earlier years still loading after a failed answer show progress, not the failure`() {
        show(loaded().copy(onThisDayLoading = true, onThisDayFailed = true))

        composeRule.onNodeWithTag(JournalTags.ON_THIS_DAY_LOADING).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Earlier years could not be loaded.").assertDoesNotExist()
    }

    @Test
    fun `an earlier entry without text or mood shows only its year`() {
        val bare = entry.copy(date = today.minusYears(2), bodyMd = "![](cockpit-file:x)", mood = null)
        show(loaded().copy(onThisDay = listOf(bare)))

        composeRule.onNodeWithText("2024, 2 years ago").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the calendar of an earlier month can go forward and closes again`() {
        val earlier = LocalDate.of(2026, 8, 14)
        show(loaded(date = earlier))

        composeRule.onNodeWithContentDescription("Show calendar").performClick()
        composeRule.onNodeWithText("August 2026").assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.day(earlier)).assertIsDisplayed()
        composeRule.onNodeWithTag(JournalTags.NEXT_MONTH).performClick()
        assertEquals(listOf("month 2026-09"), calls)

        composeRule.onNodeWithContentDescription("Hide calendar").performClick()
        composeRule.onNodeWithTag(JournalTags.CALENDAR).assertDoesNotExist()
    }

    @Test
    fun `a day without an entry is not announced as having one, a marked day without a mood is`() {
        val unmarked = LocalDate.of(2026, 10, 4)
        val noMood = LocalDate.of(2026, 10, 6)
        show(loaded().copy(monthDays = mapOf(noMood to null)))

        composeRule.onNodeWithTag(JournalTags.CALENDAR_TOGGLE).performClick()
        composeRule
            .onNodeWithTag(JournalTags.day(unmarked))
            .assert(hasContentDescription("October 4, 2026", substring = true))
            .assert(!hasContentDescription("has an entry", substring = true))
        composeRule
            .onNodeWithTag(JournalTags.day(noMood))
            .assert(hasContentDescription("October 6, 2026, has an entry", substring = true))
        composeRule.onNodeWithTag(JournalTags.day(unmarked)).performClick()
        assertEquals(listOf("open $unmarked"), calls)
    }

    @Test
    fun `cancelling the date picker opens nothing`() {
        show(loaded())

        composeRule.onNodeWithTag(JournalTags.PICK_DATE).performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag(JournalTags.DATE_PICKER).assertDoesNotExist()
        assertTrue(calls.isEmpty())
    }
}
