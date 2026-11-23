package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Swiping the day, week and month views to the neighbouring period, and the pages' preloaded data. */
@RunWith(AndroidJUnit4::class)
class CalendarSwipeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.parse("2026-10-21")

    // "Now" for the grid (where today opens, the now line): fixed, so no test depends on the real time of day.
    private val nowClock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), utc)
    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)
    private val moves = mutableListOf<Int>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun allDay(
        n: Int,
        date: LocalDate,
    ) = Occurrence(id(n), work.id, "All day $n", null, true, null, null, date, date.plusDays(1))

    private fun state(
        view: CalendarView,
        anchor: LocalDate = today,
        occurrences: List<Occurrence> = emptyList(),
        neighbours: List<LoadedRange> = emptyList(),
    ): CalendarUiState {
        val base = CalendarUiState(view = view, today = today, anchor = anchor, firstDayOfWeek = DayOfWeek.MONDAY, zone = utc)
        val days = base.days
        return base.copy(
            calendars = listOf(work),
            from = days.first(),
            to = days.last().plusDays(1),
            occurrences = occurrences,
            loaded = true,
            neighbours = neighbours,
        )
    }

    /** Shows [initial]; a move is recorded and applied like the view model does. */
    private fun show(initial: CalendarUiState): MutableState<CalendarUiState> {
        val holder = mutableStateOf(initial)
        composeRule.setContent {
            CompositionLocalProvider(LocalNowClock provides nowClock) {
                CockpitTheme {
                    CalendarContent(
                        holder.value,
                        CalendarCallbacks(
                            move = { step ->
                                moves += step
                                val current = holder.value
                                holder.value = state(current.view, shiftPeriod(current.view, current.anchor, step.toLong()))
                            },
                        ),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return holder
    }

    @Test
    fun `swiping the day view pages to the next and previous day`() {
        val holder = show(state(CalendarView.DAY))
        composeRule.onNodeWithTag(CalendarTags.PAGER).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(listOf(1), moves)
        assertEquals(today.plusDays(1), holder.value.anchor)
        composeRule.onNodeWithTag(CalendarTags.page(today.plusDays(1))).assertIsDisplayed()

        composeRule.onNodeWithTag(CalendarTags.PAGER).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertEquals(listOf(1, -1), moves)
        assertEquals(today, holder.value.anchor)
    }

    @Test
    fun `swiping the week and month views pages by a week and a month`() {
        val holder = show(state(CalendarView.WEEK))
        composeRule.onNodeWithTag(CalendarTags.PAGER).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(LocalDate.parse("2026-10-26"), holder.value.days.first())
        composeRule.onNodeWithTag(CalendarTags.page(LocalDate.parse("2026-10-26"))).assertIsDisplayed()

        holder.value = state(CalendarView.MONTH)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CalendarTags.PAGER).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertEquals(listOf(1, -1), moves)
        assertEquals(9, holder.value.anchor.monthValue)
    }

    @Test
    fun `a vertical swipe scrolls the grid and keeps the period`() {
        val holder = show(state(CalendarView.DAY))
        composeRule.onNodeWithTag(CalendarTags.GRID_SCROLL).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertTrue(moves.isEmpty())
        assertEquals(today, holder.value.anchor)
    }

    @Test
    fun `the arrows move the pager along, and the range title follows`() {
        val holder = show(state(CalendarView.DAY))
        composeRule.onNodeWithTag(CalendarTags.NEXT).performClick()
        composeRule.waitForIdle()
        // The arrow moved the view model once; the pager settling on that page does not move it again.
        assertEquals(listOf(1), moves)
        composeRule.onNodeWithTag(CalendarTags.page(today.plusDays(1))).assertIsDisplayed()
        val title =
            holder.value.anchor.format(
                java.time.format.DateTimeFormatter
                    .ofLocalizedDate(java.time.format.FormatStyle.FULL),
            )
        composeRule.onNodeWithTag(CalendarTags.RANGE).assertTextEquals(title)
    }

    @Test
    fun `a neighbouring page shows its preloaded occurrences, an empty grid while they are missing`() {
        val next = today.plusDays(1)
        val shown = state(CalendarView.DAY, neighbours = listOf(LoadedRange(next, next.plusDays(1), listOf(allDay(1, next)))))
        val page = shown.pageState(next)
        assertEquals(next to next.plusDays(1), page.from to page.to)
        assertEquals(listOf(allDay(1, next)), page.occurrences)
        assertTrue(page.loaded)

        val previous = shown.pageState(today.minusDays(1))
        assertTrue(previous.occurrences.isEmpty())
        assertEquals(false, previous.loaded)
        assertEquals(shown, shown.pageState(today))
    }

    @Test
    fun `periods between anchors count days, weeks and months`() {
        assertEquals(3, periodsBetween(CalendarView.DAY, today, today.plusDays(3), DayOfWeek.MONDAY))
        // Sunday 25 October is still in the week of Wednesday 21 October; Monday 26 starts the next.
        assertEquals(0, periodsBetween(CalendarView.WEEK, today, LocalDate.parse("2026-10-25"), DayOfWeek.MONDAY))
        assertEquals(1, periodsBetween(CalendarView.WEEK, today, LocalDate.parse("2026-10-26"), DayOfWeek.MONDAY))
        assertEquals(-1, periodsBetween(CalendarView.MONTH, today, LocalDate.parse("2026-09-30"), DayOfWeek.MONDAY))
    }
}
