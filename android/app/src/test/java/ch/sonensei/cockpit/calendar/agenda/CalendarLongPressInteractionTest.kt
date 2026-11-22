package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * A long press in the day and week grid, the all-day strip and the month asks for a new event there; taps, scrolls
 * and swipes keep doing what they did, and the same "New event" is offered as an accessibility action.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class CalendarLongPressInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")

    // Wednesday 21 October 2026. The tests use days other than today, so the grid opens at 7:00 (or the first entry)
    // whatever the real clock says.
    private val today = LocalDate.parse("2026-10-21")
    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)

    /** What the long presses asked for: day, start time, all day. */
    private val created = mutableListOf<Triple<LocalDate, LocalTime?, Boolean>>()
    private val opened = mutableListOf<Occurrence>()
    private val openedDays = mutableListOf<LocalDate>()
    private val moves = mutableListOf<Int>()
    private val haptics = mutableListOf<HapticFeedbackType>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun timed(
        n: Int,
        start: String,
        end: String,
    ) = Occurrence(id(n), work.id, "Dentist $n", null, false, Instant.parse(start), Instant.parse(end), null, null)

    private fun state(
        view: CalendarView,
        anchor: LocalDate,
        occurrences: List<Occurrence> = emptyList(),
    ): CalendarUiState {
        val base = CalendarUiState(view = view, today = today, anchor = anchor, firstDayOfWeek = DayOfWeek.MONDAY, zone = utc)
        val days = base.days
        return base.copy(
            calendars = listOf(work),
            from = days.first(),
            to = days.last().plusDays(1),
            occurrences = occurrences,
            loaded = true,
        )
    }

    private fun show(initial: CalendarUiState): MutableState<CalendarUiState> {
        val holder = mutableStateOf(initial)
        val feedback =
            object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    haptics += hapticFeedbackType
                }
            }
        composeRule.setContent {
            CockpitTheme {
                CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                    CalendarContent(
                        holder.value,
                        CalendarCallbacks(
                            move = { step ->
                                moves += step
                                val current = holder.value
                                holder.value = state(current.view, shiftPeriod(current.view, current.anchor, step.toLong()))
                            },
                            openDay = { openedDays += it },
                            open = { opened += it },
                            create = { date, start, allDay -> created += Triple(date, start, allDay) },
                        ),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return holder
    }

    /** The scrolling grid on the page of [page] (a neighbouring page may be composed as well). */
    private fun grid(page: LocalDate): SemanticsNodeInteraction =
        composeRule.onNode(hasTestTag(CalendarTags.GRID_SCROLL) and hasAnyAncestor(hasTestTag(CalendarTags.page(page))))

    private fun gridScroll(page: LocalDate): Float =
        grid(page)
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    private fun px(dp: Float): Float = with(composeRule.density) { dp * density }

    /**
     * The point (relative to the grid on [page]) of [minute] past midnight in day column [column] of [columns]:
     * the hour grid is 48 dp per hour after a 48 dp gutter with the hour labels.
     */
    private fun gridPoint(
        page: LocalDate,
        minute: Float,
        column: Int = 0,
        columns: Int = 1,
    ): Offset {
        val bounds = grid(page).fetchSemanticsNode().boundsInRoot
        val gutter = px(48f)
        val columnWidth = (bounds.width - gutter) / columns
        val y = minute * px(48f) / 60f - gridScroll(page)
        assertTrue("the point $y is in the visible grid (height ${bounds.height})", y > 0f && y < bounds.height)
        return Offset(gutter + columnWidth * (column + 0.5f), y)
    }

    /** Puts a finger down at [at] on [node] and holds it there for longer than the long-press timeout. */
    private fun pressAndHold(
        node: SemanticsNodeInteraction,
        at: Offset,
    ) {
        node.performTouchInput { down(at) }
        composeRule.mainClock.advanceTimeBy(LONG_PRESS_MILLIS)
        composeRule.waitForIdle()
    }

    private fun lift(node: SemanticsNodeInteraction) {
        node.performTouchInput { up() }
        composeRule.waitForIdle()
    }

    private fun ghost(day: LocalDate) = composeRule.onNodeWithTag(CalendarTags.ghost(day), useUnmergedTree = true)

    @Test
    fun `a long press in the day grid shows a ghost of the rounded slot and creates the event there when lifted`() {
        val day = today.plusDays(5)
        show(state(CalendarView.DAY, day))
        val grid = grid(day)
        // 7:57 and a half: rounded down to 7:45.
        val point = gridPoint(day, 7 * 60 + 57.5f)

        pressAndHold(grid, point)
        assertEquals(listOf(HapticFeedbackType.LongPress), haptics)
        // The ghost shows the slot while the finger is still down: from 7:45, one hour (48 dp) high.
        val ghost = ghost(day).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val gridTop = grid.fetchSemanticsNode().boundsInRoot.top
        val pressY = gridTop + point.y
        assertTrue("the ghost starts at or above the press", ghost.top <= pressY)
        assertTrue("the ghost starts within the quarter hour above the press", pressY - ghost.top < px(12f) + 1f)
        assertEquals(px(48f), ghost.height, 1f)
        assertTrue("nothing is created before the finger is lifted", created.isEmpty())

        lift(grid)
        assertEquals(listOf(Triple(day, LocalTime.of(7, 45), false)), created)
        ghost(day).assertDoesNotExist()
        assertTrue(opened.isEmpty())
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `a long press in the week grid creates the event on the day of the pressed column`() {
        // The week of Monday 26 October; Wednesday 28 is the third column.
        val anchor = today.plusDays(7)
        val wednesday = LocalDate.parse("2026-10-28")
        show(state(CalendarView.WEEK, anchor))
        val first = LocalDate.parse("2026-10-26")
        val grid = grid(first)
        val point = gridPoint(first, 9 * 60 + 20.5f, column = 2, columns = 7)

        pressAndHold(grid, point)
        ghost(wednesday).assertIsDisplayed()
        ghost(wednesday.minusDays(1)).assertDoesNotExist()
        ghost(wednesday.plusDays(1)).assertDoesNotExist()

        lift(grid)
        assertEquals(listOf(Triple(wednesday, LocalTime.of(9, 15), false)), created)
        ghost(wednesday).assertDoesNotExist()
    }

    @Test
    fun `a long press on an existing event creates a new event there instead of opening it, a tap still opens it`() {
        val day = today.plusDays(5)
        val dentist = timed(1, "2026-10-26T08:00:00Z", "2026-10-26T09:00:00Z")
        show(state(CalendarView.DAY, day, listOf(dentist)))
        val entry = composeRule.onNodeWithTag(CalendarTags.entry(dentist.key, day), useUnmergedTree = true)

        // About 8:24 in the 8:00 to 9:00 block: the slot from 8:15.
        val block = entry.fetchSemanticsNode().boundsInRoot
        pressAndHold(entry, Offset(block.width / 2, block.height * 0.4f))
        ghost(day).assertIsDisplayed()
        lift(entry)
        assertEquals(listOf(Triple(day, LocalTime.of(8, 15), false)), created)
        assertTrue("the long-pressed event is not opened", opened.isEmpty())

        entry.performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(dentist), opened)
        assertEquals("a tap creates nothing", 1, created.size)
    }

    @Test
    fun `a recognised long press whose gesture is cancelled removes the ghost and creates nothing`() {
        // The system takes the gesture away (ACTION_CANCEL) while the finger is still down: it was never lifted.
        val day = today.plusDays(5)
        show(state(CalendarView.DAY, day))
        val grid = grid(day)
        pressAndHold(grid, gridPoint(day, 8 * 60 + 40.5f))
        ghost(day).assertIsDisplayed()

        grid.performTouchInput { cancel() }
        composeRule.waitForIdle()
        ghost(day).assertDoesNotExist()
        assertTrue("a cancelled gesture is not a release", created.isEmpty())
    }

    @Test
    fun `a recognised long press on the all-day strip whose gesture is cancelled creates nothing`() {
        val day = today.plusDays(5)
        show(state(CalendarView.DAY, day))
        val strip = composeRule.onNodeWithTag(CalendarTags.allDay(day), useUnmergedTree = true)
        pressAndHold(strip, Offset(px(4f), px(6f)))
        ghost(day).assertIsDisplayed()

        strip.performTouchInput { cancel() }
        composeRule.waitForIdle()
        ghost(day).assertDoesNotExist()
        assertTrue("a cancelled gesture is not a release", created.isEmpty())
    }

    @Test
    fun `a tap on the empty grid creates nothing`() {
        val day = today.plusDays(5)
        show(state(CalendarView.DAY, day))
        val grid = grid(day)
        val point = gridPoint(day, 8 * 60 + 5f)
        grid.performTouchInput { click(point) }
        composeRule.waitForIdle()
        assertTrue(created.isEmpty())
        assertTrue(haptics.isEmpty())
        ghost(day).assertDoesNotExist()
    }

    @Test
    fun `a vertical scroll of the grid creates nothing and scrolls`() {
        val day = today.plusDays(5)
        show(state(CalendarView.DAY, day))
        val before = gridScroll(day)
        grid(day).performTouchInput { swipe(Offset(centerX, bottom - 10f), Offset(centerX, top + 10f), 300) }
        composeRule.waitForIdle()
        assertTrue(created.isEmpty())
        assertTrue(haptics.isEmpty())
        assertTrue("the grid scrolled", gridScroll(day) > before)
    }

    @Test
    fun `a finger that moved beyond the touch slop and then rests is a scroll, not a long press`() {
        val day = today.plusDays(5)
        show(state(CalendarView.DAY, day))
        val grid = grid(day)
        grid.performTouchInput {
            down(center)
            moveBy(Offset(0f, -viewConfiguration.touchSlop * 4))
        }
        composeRule.mainClock.advanceTimeBy(LONG_PRESS_MILLIS)
        composeRule.waitForIdle()
        ghost(day).assertDoesNotExist()
        lift(grid)
        assertTrue(created.isEmpty())
        assertTrue(haptics.isEmpty())
    }

    @Test
    fun `a second finger before the timeout is a pinch, not a long press`() {
        val day = today.plusDays(5)
        show(state(CalendarView.DAY, day))
        val grid = grid(day)
        val point = gridPoint(day, 8 * 60 + 5f)
        grid.performTouchInput {
            down(0, point)
            down(1, point + Offset(px(40f), 0f))
        }
        composeRule.mainClock.advanceTimeBy(LONG_PRESS_MILLIS)
        composeRule.waitForIdle()
        ghost(day).assertDoesNotExist()
        grid.performTouchInput {
            up(1)
            up(0)
        }
        composeRule.waitForIdle()
        assertTrue(created.isEmpty())
        assertTrue(haptics.isEmpty())
    }

    @Test
    fun `a horizontal swipe on the grid pages and creates nothing`() {
        val day = today.plusDays(5)
        show(state(CalendarView.DAY, day))
        grid(day).performTouchInput { swipe(Offset(right - 10f, centerY), Offset(left + 10f, centerY), 300) }
        composeRule.waitForIdle()
        assertEquals(listOf(1), moves)
        assertTrue(created.isEmpty())
        assertTrue(haptics.isEmpty())
    }

    @Test
    fun `once recognised, moving the finger neither scrolls nor pages and the pressed slot is created`() {
        val day = today.plusDays(5)
        show(state(CalendarView.DAY, day))
        val grid = grid(day)
        val before = gridScroll(day)
        pressAndHold(grid, gridPoint(day, 8 * 60 + 40.5f))
        grid.performTouchInput {
            moveBy(Offset(0f, -px(120f)))
            moveBy(Offset(-width * 0.6f, 0f))
        }
        composeRule.waitForIdle()
        lift(grid)
        assertEquals(listOf(Triple(day, LocalTime.of(8, 30), false)), created)
        assertEquals(before, gridScroll(day))
        assertTrue(moves.isEmpty())
        composeRule.onNodeWithTag(CalendarTags.page(day)).assertIsDisplayed()
    }

    @Test
    fun `a long press on the all-day strip shows a ghost and creates an all-day event on that day`() {
        // The strip is there even without all-day entries.
        val anchor = today.plusDays(7)
        val friday = LocalDate.parse("2026-10-30")
        show(state(CalendarView.WEEK, anchor))
        val strip = composeRule.onNodeWithTag(CalendarTags.allDay(friday), useUnmergedTree = true)
        strip.assertIsDisplayed()

        pressAndHold(strip, Offset(px(4f), px(6f)))
        assertEquals(listOf(HapticFeedbackType.LongPress), haptics)
        ghost(friday).assertIsDisplayed()
        lift(strip)
        assertEquals(listOf(Triple(friday, null as LocalTime?, true)), created)
        ghost(friday).assertDoesNotExist()
    }

    @Test
    fun `a long press on the all-day strip of the day view creates an all-day event`() {
        val day = today.plusDays(5)
        show(state(CalendarView.DAY, day))
        val strip = composeRule.onNodeWithTag(CalendarTags.allDay(day), useUnmergedTree = true)
        pressAndHold(strip, Offset(px(4f), px(6f)))
        lift(strip)
        assertEquals(listOf(Triple(day, null as LocalTime?, true)), created)
    }

    @Test
    fun `a long press on a month day creates a timed event with the default start, a tap still opens the day`() {
        val day = LocalDate.parse("2026-10-14")
        show(state(CalendarView.MONTH, today))
        val cell = composeRule.onNodeWithTag(CalendarTags.monthDay(day))

        cell.performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(listOf(Triple(day, null as LocalTime?, false)), created)
        assertEquals(listOf(HapticFeedbackType.LongPress), haptics)
        assertTrue("a long press does not open the day", openedDays.isEmpty())

        cell.performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(day), openedDays)
        assertEquals("a tap creates nothing", 1, created.size)
    }

    @Test
    fun `a horizontal swipe on the month pages and creates nothing`() {
        show(state(CalendarView.MONTH, today))
        composeRule.onNodeWithTag(CalendarTags.PAGER).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(listOf(1), moves)
        assertTrue(created.isEmpty())
    }

    private val hasNewEventAction =
        SemanticsMatcher("has the New event action") { node ->
            node.config
                .getOrNull(SemanticsActions.CustomActions)
                .orEmpty()
                .any { it.label == "New event" }
        }

    /** Runs the "New event" custom action of [node]. */
    private fun runNewEvent(node: SemanticsNodeInteraction) {
        val action =
            node
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == "New event" }
        composeRule.runOnIdle { assertTrue(action.action()) }
    }

    @Test
    fun `each day column of the week grid offers New event at the default start`() {
        val first = LocalDate.parse("2026-10-26")
        show(state(CalendarView.WEEK, today.plusDays(7)))
        val columns =
            composeRule
                .onAllNodes(
                    hasNewEventAction and hasAnyAncestor(hasTestTag(CalendarTags.GRID_SCROLL)) and
                        hasAnyAncestor(hasTestTag(CalendarTags.page(first))),
                ).fetchSemanticsNodes()
                .sortedBy { it.boundsInRoot.left }
        assertEquals(7, columns.size)

        val wednesday = columns[2]
        composeRule.runOnIdle {
            assertTrue(wednesday.config[SemanticsActions.CustomActions].single { it.label == "New event" }.action())
        }
        assertEquals(listOf(Triple(LocalDate.parse("2026-10-28"), null as LocalTime?, false)), created)
    }

    @Test
    fun `the all-day strip offers New event as an all-day event`() {
        val day = today.plusDays(5)
        show(state(CalendarView.DAY, day))
        val strip = composeRule.onNodeWithTag(CalendarTags.allDay(day), useUnmergedTree = true)
        strip.assert(hasNewEventAction)
        runNewEvent(strip)
        assertEquals(listOf(Triple(day, null as LocalTime?, true)), created)
    }

    @Test
    fun `a month day offers New event, and its long click is labelled the same`() {
        val day = LocalDate.parse("2026-10-14")
        show(state(CalendarView.MONTH, today))
        val cell = composeRule.onNodeWithTag(CalendarTags.monthDay(day))
        cell.assert(hasNewEventAction)
        assertEquals("New event", cell.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        runNewEvent(cell)
        assertEquals(listOf(Triple(day, null as LocalTime?, false)), created)
        assertTrue(openedDays.isEmpty())
    }

    private companion object {
        /** Longer than the platform long-press timeout (400 to 500 ms). */
        const val LONG_PRESS_MILLIS = 1_000L
    }
}
