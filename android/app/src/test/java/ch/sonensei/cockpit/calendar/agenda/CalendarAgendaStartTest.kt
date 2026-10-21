package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The agenda opens at its first day (today), also when its days arrive after the list was first shown with only its
 * footer, and when it is reloaded from a later day while scrolled down. The lists here are longer than the screen, so
 * a list kept at its footer or at its old position would hide the first day.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class CalendarAgendaStartTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.parse("2026-10-21")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(9000), "Work", 0xFF3366FFL, true, false, 1)

    private fun allDay(
        n: Int,
        date: LocalDate,
    ) = Occurrence(id(n), work.id, "Event $n", null, true, null, null, date, date.plusDays(1))

    /** An agenda of 30 days from [first] with an event on each of its first 20 days: more than the screen holds. */
    private fun loadedFrom(first: LocalDate) =
        CalendarUiState(
            view = CalendarView.AGENDA,
            today = first,
            anchor = first,
            firstDayOfWeek = DayOfWeek.MONDAY,
            zone = utc,
            calendars = listOf(work),
            from = first,
            to = first.plusDays(CalendarViewModel.AGENDA_CHUNK_DAYS),
            occurrences = (0 until 20).map { allDay(it + 1, first.plusDays(it.toLong())) },
            loaded = true,
        )

    private var state by mutableStateOf(loadedFrom(today))

    private fun showAgenda() {
        composeRule.setContent {
            CockpitTheme { CalendarContent(state, CalendarCallbacks()) }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `days that arrive after the agenda was shown empty open it at today`() {
        // The first showing: nothing loaded yet, so the list holds only its footer.
        state = loadedFrom(today).copy(occurrences = emptyList(), loaded = false, loading = true)
        showAgenda()

        composeRule.runOnIdle { state = loadedFrom(today) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CalendarTags.day(today)).assertIsDisplayed()
    }

    @Test
    fun `an agenda reloaded from a later day while scrolled down opens at its new first day`() {
        showAgenda()
        composeRule.onNodeWithTag(CalendarTags.AGENDA).performScrollToKey("footer")
        composeRule.waitForIdle()

        // The app comes back the next day: the agenda now starts tomorrow, most of its days are the same as before.
        val tomorrow = today.plusDays(1)
        composeRule.runOnIdle { state = loadedFrom(tomorrow) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CalendarTags.day(tomorrow)).assertIsDisplayed()
    }
}
