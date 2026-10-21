package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
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
 * An agenda shown again with its days keeps its scroll position. That it opens at its first day when its days arrive
 * is covered by [CalendarAgendaStartTest].
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class CalendarAgendaPositionTest {
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

    /** 25 days with an event each, more than the screen holds. */
    private val loadedAgenda =
        CalendarUiState(
            view = CalendarView.AGENDA,
            today = today,
            anchor = today,
            firstDayOfWeek = DayOfWeek.MONDAY,
            zone = utc,
            calendars = listOf(work),
            from = today,
            to = today.plusDays(CalendarViewModel.AGENDA_CHUNK_DAYS),
            occurrences = (0 until 25).map { allDay(it + 1, today.plusDays(it.toLong())) },
            loaded = true,
        )

    @Test
    fun `an agenda shown again with its days keeps where it was scrolled to`() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { CockpitTheme { CalendarContent(loadedAgenda, CalendarCallbacks()) } }
        val later = today.plusDays(15)
        composeRule.onNodeWithTag(CalendarTags.AGENDA).performScrollToKey("day:$later")
        composeRule.onNodeWithTag(CalendarTags.day(today)).assertDoesNotExist()

        // Like a rotation or a return to the Calendar tab: the agenda is composed again from its saved state.
        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(CalendarTags.day(later)).assertIsDisplayed()
        composeRule.onNodeWithTag(CalendarTags.day(today)).assertDoesNotExist()
    }
}
