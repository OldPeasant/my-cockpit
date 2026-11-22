package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The pager together with the arrows, Today, taps and data arriving late: the controls stay in sync with the settled
 * page, and the agenda has no pager.
 */
@RunWith(AndroidJUnit4::class)
class CalendarSwipeInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")

    // Wednesday 21 October 2026.
    private val today = LocalDate.parse("2026-10-21")
    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)
    private val moves = mutableListOf<Int>()
    private val opened = mutableListOf<LocalDate>()
    private val openedEntries = mutableListOf<Occurrence>()
    private var todays = 0

    /** What the fake server has, for the move callback to put into the new period's state. */
    private var server: List<Occurrence> = emptyList()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun allDay(
        n: Int,
        date: LocalDate,
    ) = Occurrence(id(n), work.id, "All day $n", null, true, null, null, date, date.plusDays(1))

    private fun state(
        view: CalendarView,
        anchor: LocalDate = today,
        loaded: Boolean = true,
    ): CalendarUiState {
        val base = CalendarUiState(view = view, today = today, anchor = anchor, firstDayOfWeek = DayOfWeek.MONDAY, zone = utc)
        val days = base.days
        val from = days.first()
        val to = days.last().plusDays(1)
        return base.copy(
            calendars = listOf(work),
            from = from,
            to = to,
            occurrences = if (loaded) server.filter { it.startDate!! >= from && it.startDate!! < to } else emptyList(),
            loaded = loaded,
        )
    }

    private fun show(
        initial: CalendarUiState,
        restoration: StateRestorationTester? = null,
    ): MutableState<CalendarUiState> {
        val holder = mutableStateOf(initial)
        val setContent: (@Composable () -> Unit) -> Unit = restoration?.let { it::setContent } ?: composeRule::setContent
        setContent {
            CockpitTheme {
                CalendarContent(
                    holder.value,
                    CalendarCallbacks(
                        move = { step ->
                            moves += step
                            val current = holder.value
                            holder.value = state(current.view, shiftPeriod(current.view, current.anchor, step.toLong()))
                        },
                        today = {
                            todays++
                            holder.value = state(holder.value.view, today)
                        },
                        openDay = { opened += it },
                        open = { openedEntries += it },
                    ),
                )
            }
        }
        composeRule.waitForIdle()
        return holder
    }

    @Test
    fun `the agenda view has no pager`() {
        show(state(CalendarView.AGENDA))
        composeRule.onNodeWithTag(CalendarTags.AGENDA).assertIsDisplayed()
        composeRule.onNodeWithTag(CalendarTags.PAGER).assertDoesNotExist()
        composeRule.onNodeWithTag(CalendarTags.page(today)).assertDoesNotExist()
    }

    @Test
    fun `the previous arrow slides the pager back and Today jumps back from several days away`() {
        val holder = show(state(CalendarView.DAY))
        composeRule.onNodeWithTag(CalendarTags.PREVIOUS).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(-1), moves)
        composeRule.onNodeWithTag(CalendarTags.page(today.minusDays(1))).assertIsDisplayed()

        repeat(4) {
            composeRule.onNodeWithTag(CalendarTags.NEXT).performClick()
            composeRule.waitForIdle()
        }
        assertEquals(listOf(-1, 1, 1, 1, 1), moves)
        composeRule.onNodeWithTag(CalendarTags.page(today.plusDays(3))).assertIsDisplayed()

        // Three days away: the pager jumps, and settling there does not move the view model again.
        composeRule.onNodeWithTag(CalendarTags.TODAY).performClick()
        composeRule.waitForIdle()
        assertEquals(1, todays)
        assertEquals(listOf(-1, 1, 1, 1, 1), moves)
        assertEquals(today, holder.value.anchor)
        composeRule.onNodeWithTag(CalendarTags.page(today)).assertIsDisplayed()
        composeRule.onNodeWithTag(CalendarTags.page(today.plusDays(3))).assertDoesNotExist()
    }

    @Test
    fun `two quick taps on the next arrow end two days later without a move back`() {
        val holder = show(state(CalendarView.DAY))
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(CalendarTags.NEXT).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(CalendarTags.NEXT).performClick()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(listOf(1, 1), moves)
        assertEquals(today.plusDays(2), holder.value.anchor)
        composeRule.onNodeWithTag(CalendarTags.page(today.plusDays(2))).assertIsDisplayed()
    }

    @Test
    fun `a second tap on the next arrow after the slide passed halfway ends two days later without a move back`() {
        val holder = show(state(CalendarView.DAY))
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(CalendarTags.NEXT).performClick()
        // Well past the midpoint of the slide (the pager's current page is already the next day), but not finished.
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.onNodeWithTag(CalendarTags.NEXT).performClick()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(listOf(1, 1), moves)
        assertEquals(today.plusDays(2), holder.value.anchor)
        composeRule.onNodeWithTag(CalendarTags.page(today.plusDays(2))).assertIsDisplayed()
    }

    @Test
    fun `a swipe that interrupts an arrow slide still moves the view model`() {
        val holder = show(state(CalendarView.DAY))
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(CalendarTags.NEXT).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        // The finger catches the sliding pager and swipes it back past the day the arrow started from.
        composeRule.onNodeWithTag(CalendarTags.PAGER).performTouchInput { swipeRight() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(1, moves.first())
        assertEquals("the swipe after the arrow moved the view model", 2, moves.size)
        assertTrue("the swipe went back", moves.last() < 0)
        assertEquals(today.plusDays(moves.sum().toLong()), holder.value.anchor)
        composeRule.onNodeWithTag(CalendarTags.page(holder.value.anchor)).assertIsDisplayed()
    }

    @Test
    fun `a mostly vertical diagonal gesture scrolls the grid and keeps the period`() {
        val holder = show(state(CalendarView.DAY))
        val before = gridScroll(today)
        composeRule.onNodeWithTag(CalendarTags.GRID_SCROLL).performTouchInput {
            swipe(Offset(centerX, bottom - 10f), Offset(centerX - width * 0.1f, top + 10f), 300)
        }
        composeRule.waitForIdle()
        assertTrue(moves.isEmpty())
        assertEquals(today, holder.value.anchor)
        assertTrue("the grid scrolled down", gridScroll(today) > before)
    }

    @Test
    fun `a horizontal swipe on the grid pages without scrolling it vertically`() {
        // Days other than today without timed entries open at the default hour, so both pages start alike.
        val start = today.plusDays(5)
        val holder = show(state(CalendarView.DAY, start))
        val before = gridScroll(start)
        composeRule.onNodeWithTag(CalendarTags.GRID_SCROLL).performTouchInput {
            swipe(Offset(right - 10f, centerY), Offset(left + 10f, centerY + 30f), 300)
        }
        composeRule.waitForIdle()
        assertEquals(listOf(1), moves)
        assertEquals(start.plusDays(1), holder.value.anchor)
        assertEquals(before, gridScroll(start.plusDays(1)))
    }

    @Test
    fun `a short, slow, mostly horizontal drag snaps back without scrolling vertically or moving`() {
        val start = today.plusDays(5)
        val holder = show(state(CalendarView.DAY, start))
        val before = gridScroll(start)
        composeRule.onNodeWithTag(CalendarTags.GRID_SCROLL).performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX - width * 0.2f, centerY - 40f), 1500)
        }
        composeRule.waitForIdle()
        assertTrue(moves.isEmpty())
        assertEquals(start, holder.value.anchor)
        composeRule.onNodeWithTag(CalendarTags.page(start)).assertIsDisplayed()
        assertEquals(before, gridScroll(start))
    }

    @Test
    fun `day headers and entries can be tapped on the week swiped to`() {
        val nextFriday = LocalDate.parse("2026-10-30")
        server = listOf(allDay(1, nextFriday))
        show(state(CalendarView.WEEK))
        composeRule.onNodeWithTag(CalendarTags.PAGER).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(listOf(1), moves)

        composeRule.onNodeWithTag(CalendarTags.day(nextFriday)).performClick()
        assertEquals(listOf(nextFriday), opened)
        composeRule.onNodeWithText("All day 1", useUnmergedTree = true).performClick()
        assertEquals(listOf(allDay(1, nextFriday)), openedEntries)
    }

    @Test
    fun `month days can be tapped on the month swiped to`() {
        show(state(CalendarView.MONTH))
        composeRule.onNodeWithTag(CalendarTags.PAGER).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(listOf(1), moves)
        composeRule.onNodeWithTag(CalendarTags.page(LocalDate.parse("2026-10-26"))).assertIsDisplayed()

        composeRule.onNodeWithTag(CalendarTags.monthDay(LocalDate.parse("2026-11-18"))).performClick()
        assertEquals(listOf(LocalDate.parse("2026-11-18")), opened)
    }

    @Test
    fun `a page swiped to before its data arrives fills when the data comes`() {
        val tomorrow = today.plusDays(1)
        val holder = show(state(CalendarView.DAY))
        composeRule.onNodeWithTag(CalendarTags.PAGER).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        // The view model has moved, but nothing is loaded for the new day yet: an empty page.
        holder.value = state(CalendarView.DAY, tomorrow, loaded = false)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CalendarTags.page(tomorrow)).assertIsDisplayed()
        composeRule.onNodeWithText("All day 7", useUnmergedTree = true).assertDoesNotExist()

        server = listOf(allDay(7, tomorrow))
        holder.value = state(CalendarView.DAY, tomorrow)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CalendarTags.page(tomorrow)).assertIsDisplayed()
        composeRule.onNodeWithText("All day 7", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(listOf(1), moves)
    }

    @Test
    fun `a restored screen keeps the period without moving the view model again`() {
        val restoration = StateRestorationTester(composeRule)
        // The holder outlives the composition, like the view model across a rotation or a return to the tab.
        val holder = show(state(CalendarView.DAY), restoration)
        composeRule.onNodeWithTag(CalendarTags.NEXT).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(1), moves)

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals(listOf(1), moves)
        assertEquals(today.plusDays(1), holder.value.anchor)
        composeRule.onNodeWithTag(CalendarTags.page(today.plusDays(1))).assertIsDisplayed()
    }

    @Test
    fun `a period change during a drag is followed once the drag snaps back`() {
        val start = today.plusDays(5)
        val holder = show(state(CalendarView.DAY, start))
        composeRule.onNodeWithTag(CalendarTags.PAGER).performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.2f, 0f))
        }
        // The view model moves (an arrow, Today) while the finger still holds the pager.
        holder.value = state(CalendarView.DAY, start.plusDays(1))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CalendarTags.PAGER).performTouchInput { up() }
        composeRule.waitForIdle()
        assertTrue(moves.isEmpty())
        assertEquals(start.plusDays(1), holder.value.anchor)
        composeRule.onNodeWithTag(CalendarTags.page(start.plusDays(1))).assertIsDisplayed()
        composeRule.onNodeWithTag(CalendarTags.page(start)).assertDoesNotExist()
    }

    /** The vertical scroll of the grid on the page of [page]; a page left behind may stay composed for a moment. */
    private fun gridScroll(page: LocalDate): Float =
        composeRule
            .onNode(hasTestTag(CalendarTags.GRID_SCROLL) and hasAnyAncestor(hasTestTag(CalendarTags.page(page))))
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()
}
