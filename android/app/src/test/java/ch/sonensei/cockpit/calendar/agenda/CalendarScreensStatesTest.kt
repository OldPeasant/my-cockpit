package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Loading, empty and error states of the calendar views, and the labels of events spanning days. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class CalendarScreensStatesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.parse("2026-10-21")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)
    private val calls = mutableListOf<String>()

    private fun show(state: CalendarUiState) =
        composeRule.setContent {
            CockpitTheme {
                CalendarContent(
                    state,
                    CalendarCallbacks(
                        move = { calls += "move $it" },
                        loadMore = { calls += "more" },
                        retry = { calls += "retry" },
                        openDay = { calls += "day $it" },
                    ),
                )
            }
        }

    private fun state(
        view: CalendarView = CalendarView.AGENDA,
        occurrences: List<Occurrence> = emptyList(),
        calendars: List<CalendarInfo> = listOf(work),
        loaded: Boolean = true,
        loading: Boolean = false,
        loadingMore: Boolean = false,
        message: CalendarMessage? = null,
        anchor: LocalDate = today,
    ) = CalendarUiState(
        view = view,
        today = today,
        anchor = anchor,
        firstDayOfWeek = DayOfWeek.MONDAY,
        zone = utc,
        calendars = calendars,
        from = today,
        to = if (view == CalendarView.AGENDA) today.plusDays(30) else today.plusDays(1),
        occurrences = occurrences,
        loaded = loaded,
        loading = loading,
        loadingMore = loadingMore,
        message = message,
    )

    @Test
    fun `before the first load the agenda claims neither emptiness nor more days`() {
        show(state(loaded = false, loading = true))

        composeRule.onNodeWithTag(CalendarTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(CalendarTags.MORE).assertDoesNotExist()
    }

    @Test
    fun `while later days load there is no button to load them again`() {
        show(state(loadingMore = true))

        composeRule.onNodeWithTag(CalendarTags.MORE).assertDoesNotExist()
        composeRule.onNodeWithTag(CalendarTags.EMPTY).assertExists()
    }

    @Test
    fun `an empty agenda says until which day it was loaded`() {
        show(state())

        // The range is [from, to): the last loaded day is the day before `to`.
        composeRule.onNodeWithTag(CalendarTags.EMPTY).assertTextContains("Nov 19, 2026", substring = true)
    }

    @Test
    fun `an agenda that does not fill the screen does not load later days by itself`() {
        val one = Occurrence(id(1), work.id, "Lunch", null, true, null, null, today, today.plusDays(1))
        show(state(occurrences = listOf(one)))

        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `offline is told apart from a failure, and retry is disabled while loading`() {
        show(state(message = CalendarMessage.OFFLINE, loading = true))

        composeRule.onNodeWithText("Can't reach the server. Showing the last loaded events.").assertExists()
        composeRule.onNodeWithTag(CalendarTags.RETRY).assertIsNotEnabled()
    }

    @Test
    fun `a refused calendar change says so and offers retry`() {
        show(state(message = CalendarMessage.VISIBILITY_FAILED))

        composeRule.onNodeWithText("The calendar could not be changed. Try again.").assertExists()
        composeRule.onNodeWithTag(CalendarTags.RETRY).assertIsEnabled().performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `without calendars the filter says where to add one`() {
        show(state(calendars = emptyList()))

        composeRule.onNodeWithTag(CalendarTags.CALENDARS).performClick()
        composeRule.onNodeWithText("No calendars yet. Add one on the web.").assertExists()
    }

    @Test
    fun `an event spanning days is labelled as continuing on each of its days`() {
        val trip =
            Occurrence(
                id(1),
                work.id,
                "Night train",
                null,
                false,
                Instant.parse("2026-10-21T22:00:00Z"),
                Instant.parse("2026-10-22T07:30:00Z"),
                null,
                null,
            )
        show(state(occurrences = listOf(trip)))

        composeRule.onNodeWithTag(CalendarTags.entry(trip.key, today)).assertTextContains("continues", substring = true)
        composeRule
            .onNodeWithTag(CalendarTags.entry(trip.key, today.plusDays(1)))
            .assertTextContains("…", substring = true)
    }

    @Test
    fun `days other than today are named by weekday, a day in another year shows the year`() {
        val friday = today.plusDays(2)
        val newYear = LocalDate.parse("2027-01-01")
        val events =
            listOf(
                Occurrence(id(1), work.id, "Market", null, true, null, null, friday, friday.plusDays(1)),
                Occurrence(id(2), work.id, "New year", null, true, null, null, newYear, newYear.plusDays(1)),
            )
        show(state(occurrences = events).copy(to = today.plusDays(90)))

        composeRule.onNodeWithTag(CalendarTags.day(friday)).assertTextContains("Friday", substring = true)
        composeRule.onNodeWithTag(CalendarTags.AGENDA).performScrollToNode(hasTestTag(CalendarTags.day(newYear)))
        composeRule.onNodeWithTag(CalendarTags.day(newYear)).assertTextContains("January 2027", substring = true)
    }

    @Test
    fun `the week view moves by week and names its arrows for weeks`() {
        show(state(view = CalendarView.WEEK))

        composeRule.onNodeWithContentDescription("Previous week").performClick()
        composeRule.onNodeWithContentDescription("Next week").performClick()
        assertEquals(listOf("move -1", "move 1"), calls)
    }

    @Test
    fun `the day view names its arrows for days and shows no now line on another day`() {
        show(state(view = CalendarView.DAY, anchor = today.plusDays(3)))

        composeRule.onNodeWithContentDescription("Previous day").assertExists()
        composeRule.onNodeWithContentDescription("Next day").assertExists()
        composeRule.onNodeWithTag(CalendarTags.NOW).assertDoesNotExist()
    }

    @Test
    fun `an empty week does not claim the day is empty`() {
        show(state(view = CalendarView.WEEK))

        composeRule.onNodeWithTag(CalendarTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `a day not loaded yet does not claim to be empty`() {
        show(state(view = CalendarView.DAY, loaded = false, loading = true))

        composeRule.onNodeWithTag(CalendarTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `the calendar screen outside a Hilt activity shows the placeholder`() {
        composeRule.setContent { CockpitTheme { CalendarScreen() } }

        composeRule.onNodeWithText("Calendar").assertExists()
        composeRule.onNodeWithTag(CalendarTags.AGENDA).assertDoesNotExist()
    }
}
