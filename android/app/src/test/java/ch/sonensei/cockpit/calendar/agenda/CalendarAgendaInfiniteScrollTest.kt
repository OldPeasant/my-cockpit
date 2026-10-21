package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Infinite scroll of the agenda over more than one range: every time the end of what is loaded is reached, the next
 * days are asked for again, also when the range loaded last added only a few entries.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class CalendarAgendaInfiniteScrollTest {
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

    /** How often the agenda asked for later days. */
    private var loads = 0

    /** What the agenda shows: 20 days with an event each, more than the screen holds. */
    private val initial =
        CalendarUiState(
            view = CalendarView.AGENDA,
            today = today,
            anchor = today,
            firstDayOfWeek = DayOfWeek.MONDAY,
            zone = utc,
            calendars = listOf(work),
            from = today,
            to = today.plusDays(CalendarViewModel.AGENDA_CHUNK_DAYS),
            occurrences = (0 until 20).map { allDay(it + 1, today.plusDays(it.toLong())) },
            loaded = true,
        )

    private var state by mutableStateOf(initial)

    /** The answer of a load of later days: the next 30 days, with [events] events in them. */
    private fun CalendarUiState.withNextRange(events: Int): CalendarUiState {
        val from = to
        return copy(
            to = from.plusDays(CalendarViewModel.AGENDA_CHUNK_DAYS),
            occurrences = occurrences + (0 until events).map { allDay(loads * 100 + it, from.plusDays(1L + it)) },
            loadingMore = false,
        )
    }

    /** Hosts the agenda; each request for later days is handled by [onLoadMore]. */
    private fun showAgenda(onLoadMore: () -> Unit) {
        composeRule.setContent {
            CockpitTheme {
                CalendarContent(
                    state,
                    CalendarCallbacks(
                        loadMore = {
                            loads++
                            onLoadMore()
                        },
                    ),
                )
            }
        }
    }

    /**
     * Hosts the agenda the way the view model drives it: each request for later days appends the next 30 days with
     * [eventsPerRange] events in them.
     */
    private fun showGrowingAgenda(eventsPerRange: Int) = showAgenda { state = state.withNextRange(eventsPerRange) }

    /** Scrolls the agenda to its footer, the end of what is loaded so far. */
    private fun scrollToEnd() {
        composeRule.onNodeWithTag(CalendarTags.AGENDA).performScrollToKey("footer")
        composeRule.waitForIdle()
    }

    @Test
    fun `reaching the end again after a range with many events loads the next days again`() {
        showGrowingAgenda(eventsPerRange = 20)

        scrollToEnd()
        val afterFirstEnd = composeRule.runOnIdle { loads }
        assertTrue("the first end of the agenda loads later days", afterFirstEnd >= 1)

        scrollToEnd()
        composeRule.runOnIdle {
            assertTrue("the end of the appended days loads later days again ($loads loads)", loads > afterFirstEnd)
        }
    }

    @Test
    fun `reaching the end again after a range with few events loads the next days again`() {
        showGrowingAgenda(eventsPerRange = 1)

        scrollToEnd()
        val afterFirstEnd = composeRule.runOnIdle { loads }
        assertTrue("the first end of the agenda loads later days", afterFirstEnd >= 1)

        // The range loaded added a single day; its end is reached again by scrolling to it.
        scrollToEnd()
        composeRule.runOnIdle {
            assertTrue("the end of the appended days loads later days again ($loads loads)", loads > afterFirstEnd)
        }
    }

    @Test
    fun `a range without events does not make the agenda load range after range by itself`() {
        showGrowingAgenda(eventsPerRange = 0)

        scrollToEnd()
        composeRule.runOnIdle { assertEquals(1, loads) }

        scrollToEnd()
        composeRule.runOnIdle { assertEquals("nothing new to scroll to, no new load", 1, loads) }
    }

    @Test
    fun `later days that arrive after a while near the end load the next days again`() {
        // Like the view model: the request starts a load, and its answer arrives later.
        showAgenda { state = state.copy(loadingMore = true) }

        scrollToEnd()
        composeRule.runOnIdle { assertEquals(1, loads) }

        composeRule.runOnIdle { state = state.withNextRange(events = 1) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals("the end is still in view after the answer", 2, loads) }
    }

    @Test
    fun `the end reached while the agenda reloads asks for later days once the reload is done`() {
        state = initial.copy(loading = true)
        showAgenda { state = state.copy(loadingMore = true) }

        scrollToEnd()
        composeRule.runOnIdle { assertEquals("a view model ignores requests while it loads", 0, loads) }

        composeRule.runOnIdle { state = state.copy(loading = false) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, loads) }
    }
}
