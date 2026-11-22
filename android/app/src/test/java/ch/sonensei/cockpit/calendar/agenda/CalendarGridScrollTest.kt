package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Where the day and week grid is scrolled to when a period opens. */
@RunWith(AndroidJUnit4::class)
class CalendarGridScrollTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.parse("2026-10-21")
    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun timed(
        n: Int,
        start: String,
        end: String,
    ) = Occurrence(id(n), work.id, "Event $n", null, false, Instant.parse(start), Instant.parse(end), null, null)

    private fun allDay(
        n: Int,
        date: LocalDate,
    ) = Occurrence(id(n), work.id, "All day $n", null, true, null, null, date, date.plusDays(1))

    private fun state(
        view: CalendarView = CalendarView.DAY,
        anchor: LocalDate = today,
        occurrences: List<Occurrence> = emptyList(),
        loaded: Boolean = true,
        cachedAt: Instant? = null,
    ): CalendarUiState {
        val base = CalendarUiState(view = view, today = today, anchor = anchor, firstDayOfWeek = DayOfWeek.MONDAY, zone = utc)
        val days = base.days
        return base.copy(
            calendars = listOf(work),
            from = days.first(),
            to = days.last().plusDays(1),
            occurrences = occurrences,
            loaded = loaded,
            cachedAt = cachedAt,
        )
    }

    private fun show(
        initial: CalendarUiState,
        now: String = "2026-10-21T10:30:00Z",
    ): MutableState<CalendarUiState> {
        val holder = mutableStateOf(initial)
        val clock = Clock.fixed(Instant.parse(now), ZoneOffset.UTC)
        composeRule.setContent {
            CompositionLocalProvider(LocalNowClock provides clock) {
                CockpitTheme { CalendarContent(holder.value, CalendarCallbacks()) }
            }
        }
        composeRule.waitForIdle()
        return holder
    }

    private val hourPx: Int get() = with(composeRule.density) { 48.dp.roundToPx() }

    private fun scrollRange() =
        composeRule
            .onNodeWithTag(CalendarTags.GRID_SCROLL)
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]

    private fun scrolled(): Int = scrollRange().value().toInt()

    @Test
    fun `the day opens at the full hour before its earliest timed entry`() {
        show(
            state(
                occurrences =
                    listOf(
                        timed(1, "2026-10-21T16:00:00Z", "2026-10-21T17:00:00Z"),
                        timed(2, "2026-10-21T14:20:00Z", "2026-10-21T15:00:00Z"),
                    ),
            ),
        )
        assertEquals(14 * hourPx, scrolled())
    }

    @Test
    fun `an entry near midnight scrolls to the end of the grid`() {
        show(state(occurrences = listOf(timed(1, "2026-10-21T23:30:00Z", "2026-10-21T23:50:00Z"))))
        val range = scrollRange()
        assertTrue(range.maxValue() < 23 * hourPx)
        assertEquals(range.maxValue().toInt(), scrolled())
    }

    @Test
    fun `the week opens at its earliest timed entry on any day`() {
        show(
            state(
                view = CalendarView.WEEK,
                occurrences =
                    listOf(
                        timed(1, "2026-10-19T11:00:00Z", "2026-10-19T12:00:00Z"),
                        timed(2, "2026-10-24T08:45:00Z", "2026-10-24T09:00:00Z"),
                    ),
            ),
        )
        assertEquals(8 * hourPx, scrolled())
    }

    @Test
    fun `entries continuing from an earlier day and all-day entries are ignored`() {
        val overnight = timed(1, "2026-10-20T22:00:00Z", "2026-10-21T02:00:00Z")
        show(state(occurrences = listOf(overnight, allDay(2, today), timed(3, "2026-10-21T09:10:00Z", "2026-10-21T09:30:00Z"))))
        assertEquals(9 * hourPx, scrolled())
    }

    @Test
    fun `without a timed entry today opens at the current hour`() {
        val overnight = timed(1, "2026-10-20T22:00:00Z", "2026-10-21T02:00:00Z")
        show(state(occurrences = listOf(overnight, allDay(2, today))), now = "2026-10-21T15:40:00Z")
        assertEquals(15 * hourPx, scrolled())
    }

    @Test
    fun `without a timed entry another day opens at seven`() {
        show(state(anchor = today.plusDays(3)))
        assertEquals(7 * hourPx, scrolled())
    }

    @Test
    fun `a period change positions the grid once its data is there, a reload of the same period does not move it`() {
        val holder = show(state(occurrences = listOf(timed(1, "2026-10-21T14:00:00Z", "2026-10-21T15:00:00Z"))))
        assertEquals(14 * hourPx, scrolled())

        // The next day: nothing loaded yet, the grid waits for the data.
        val next = today.plusDays(1)
        holder.value = state(anchor = next, loaded = false)
        composeRule.waitForIdle()
        assertEquals(14 * hourPx, scrolled())
        holder.value = state(anchor = next, occurrences = listOf(timed(2, "2026-10-22T06:15:00Z", "2026-10-22T07:00:00Z")))
        composeRule.waitForIdle()
        assertEquals(6 * hourPx, scrolled())

        // The user scrolls, then a background refresh brings an earlier entry: the position stays.
        composeRule.onNodeWithTag(CalendarTags.GRID_SCROLL).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val userPosition = scrolled()
        assertNotEquals(6 * hourPx, userPosition)
        holder.value =
            state(
                anchor = next,
                occurrences =
                    listOf(
                        timed(2, "2026-10-22T06:15:00Z", "2026-10-22T07:00:00Z"),
                        timed(3, "2026-10-22T03:00:00Z", "2026-10-22T04:00:00Z"),
                    ),
            )
        composeRule.waitForIdle()
        assertEquals(userPosition, scrolled())

        // A view switch is a period change too.
        holder.value =
            state(view = CalendarView.WEEK, anchor = next, occurrences = listOf(timed(4, "2026-10-20T12:00:00Z", "2026-10-20T13:00:00Z")))
        composeRule.waitForIdle()
        assertEquals(12 * hourPx, scrolled())
    }

    @Test
    fun `a grid recreated for the same period (rotation, back to the tab) keeps where the user scrolled to`() {
        val restoration = StateRestorationTester(composeRule)
        val clock = Clock.fixed(Instant.parse("2026-10-21T10:30:00Z"), ZoneOffset.UTC)
        val shown = state(occurrences = listOf(timed(1, "2026-10-21T14:00:00Z", "2026-10-21T15:00:00Z")))
        restoration.setContent {
            CompositionLocalProvider(LocalNowClock provides clock) {
                CockpitTheme { CalendarContent(shown, CalendarCallbacks()) }
            }
        }
        composeRule.waitForIdle()
        assertEquals(14 * hourPx, scrolled())

        composeRule.onNodeWithTag(CalendarTags.GRID_SCROLL).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val userPosition = scrolled()
        assertNotEquals(14 * hourPx, userPosition)

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals(userPosition, scrolled())
    }

    @Test
    fun `the week ignores an entry continuing from the previous week`() {
        // Sunday 22:00 to Monday 03:00: the week (Monday 2026-10-19 to Sunday 2026-10-25) shows it from 00:00 on Monday.
        val overnight = timed(1, "2026-10-18T22:00:00Z", "2026-10-19T03:00:00Z")
        val thursday = timed(2, "2026-10-22T11:15:00Z", "2026-10-22T12:00:00Z")
        show(state(view = CalendarView.WEEK, occurrences = listOf(overnight, thursday)))
        assertEquals(11 * hourPx, scrolled())
    }

    @Test
    fun `Today positions the grid on cached data, the network data of the same day then does not move it`() {
        val later = timed(1, "2026-10-24T16:00:00Z", "2026-10-24T17:00:00Z")
        val holder = show(state(anchor = today.plusDays(3), occurrences = listOf(later)))
        assertEquals(16 * hourPx, scrolled())

        // Today: the range is cleared until its data is there, the grid keeps its position meanwhile.
        holder.value = state(anchor = today, loaded = false)
        composeRule.waitForIdle()
        assertEquals(16 * hourPx, scrolled())

        // The server cannot be reached: today's cached data (no timed entry) is shown, the grid goes to the current hour.
        val cachedAt = Instant.parse("2026-10-21T06:00:00Z")
        holder.value = state(anchor = today, loaded = false, cachedAt = cachedAt, occurrences = listOf(allDay(2, today)))
        composeRule.waitForIdle()
        assertEquals(10 * hourPx, scrolled())

        // A later load works and brings an earlier entry: it is a reload of the same day, the position stays.
        val early = timed(3, "2026-10-21T05:00:00Z", "2026-10-21T06:00:00Z")
        holder.value = state(anchor = today, occurrences = listOf(allDay(2, today), early))
        composeRule.waitForIdle()
        assertEquals(10 * hourPx, scrolled())
    }

    @Test
    fun `the first visible hour follows the rules`() {
        val zone = utc
        val day = today.plusDays(5)
        val continuing = entriesOn(listOf(timed(1, "2026-10-25T23:00:00Z", "2026-10-26T05:00:00Z")), day, zone)
        assertEquals(7, firstVisibleHour(mapOf(day to continuing), today, LocalTime.of(10, 0)))
        assertEquals(10, firstVisibleHour(mapOf(today to emptyList()), today, LocalTime.of(10, 59)))
    }
}
