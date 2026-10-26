package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Cached events shown while the server cannot be reached: the offline banner and the read-only views (SPEC.md §9.3). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class CalendarScreensCachedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.parse("2026-10-21")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)
    private val lunch = Occurrence(id(1), work.id, "Lunch", null, true, null, null, today, today.plusDays(1))
    private val calls = mutableListOf<String>()

    private fun show(
        view: CalendarView = CalendarView.AGENDA,
        occurrences: List<Occurrence> = listOf(lunch),
    ) = composeRule.setContent {
        CockpitTheme {
            CalendarContent(
                CalendarUiState(
                    view = view,
                    today = today,
                    anchor = today,
                    firstDayOfWeek = DayOfWeek.MONDAY,
                    zone = utc,
                    calendars = listOf(work),
                    from = today,
                    to = if (view == CalendarView.AGENDA) today.plusDays(30) else today.plusDays(1),
                    occurrences = occurrences,
                    loaded = false,
                    message = CalendarMessage.OFFLINE,
                    cachedAt = Instant.parse("2026-10-20T17:30:00Z"),
                ),
                CalendarCallbacks(
                    loadMore = { calls += "more" },
                    setVisible = { calendar, visible -> calls += "visible $calendar $visible" },
                    open = { calls += "open ${it.title}" },
                ),
            )
        }
    }

    @Test
    fun `the offline banner says when the cached events were loaded`() {
        show()

        composeRule
            .onNodeWithText("Offline. Showing events as loaded Oct 20, 2026", substring = true)
            .assertTextContains("read-only until the server is reachable", substring = true)
    }

    @Test
    fun `cached events are shown but cannot be opened, and no more days are offered`() {
        show()

        composeRule.onNodeWithTag(CalendarTags.entry(lunch.key, today)).performClick()
        composeRule.onNodeWithTag(CalendarTags.MORE).assertDoesNotExist()
        composeRule.onNodeWithText("Showing until Nov 19, 2026").assertExists()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `cached calendars cannot be shown or hidden`() {
        show()

        composeRule.onNodeWithTag(CalendarTags.CALENDARS).performClick()
        composeRule.onNodeWithTag(CalendarTags.calendar(work.id)).assertIsNotEnabled()
    }

    @Test
    fun `an empty cached day says it is empty`() {
        show(view = CalendarView.DAY, occurrences = emptyList())

        composeRule.onNodeWithTag(CalendarTags.EMPTY).assertExists()
    }
}
