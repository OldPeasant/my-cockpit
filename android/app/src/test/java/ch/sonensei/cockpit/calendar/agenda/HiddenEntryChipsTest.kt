package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** The "▲ n" / "▼ n" chips of the day and week grid: entries scrolled out of view, a tap brings them back. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class HiddenEntryChipsTest {
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

    private fun show(
        view: CalendarView,
        occurrences: List<Occurrence>,
    ) {
        val base = CalendarUiState(view = view, today = today, anchor = today, firstDayOfWeek = DayOfWeek.MONDAY, zone = utc)
        val days = base.days
        val state =
            base.copy(calendars = listOf(work), from = days.first(), to = days.last().plusDays(1), occurrences = occurrences, loaded = true)
        val clock = Clock.fixed(Instant.parse("2026-10-21T10:30:00Z"), ZoneOffset.UTC)
        composeRule.setContent {
            CompositionLocalProvider(LocalNowClock provides clock) {
                CockpitTheme { CalendarContent(state, CalendarCallbacks()) }
            }
        }
        composeRule.waitForIdle()
    }

    private val hourPx: Int get() = with(composeRule.density) { 48.dp.roundToPx() }

    // How far a revealed entry is kept from the edge (HIDDEN_ENTRY_MARGIN), clear of the chip drawn there.
    private val marginPx: Int get() = with(composeRule.density) { HIDDEN_ENTRY_MARGIN.roundToPx() }

    private fun scrollRange() =
        composeRule
            .onNodeWithTag(CalendarTags.GRID_SCROLL)
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]

    private fun scrolled(): Int = scrollRange().value().toInt()

    private fun exists(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the day view shows how many entries are earlier and later, and a tap scrolls to the nearest`() {
        show(
            CalendarView.DAY,
            listOf(
                timed(1, "2026-10-21T02:00:00Z", "2026-10-21T03:00:00Z"),
                timed(2, "2026-10-21T04:00:00Z", "2026-10-21T05:00:00Z"),
                timed(3, "2026-10-21T22:00:00Z", "2026-10-21T23:00:00Z"),
            ),
        )
        // The grid opens at 2:00: nothing above, the 22:00 entry below.
        assertEquals(2 * hourPx, scrolled())
        assertTrue(!exists(CalendarTags.hiddenAbove(today)))
        composeRule
            .onNodeWithTag(CalendarTags.hiddenBelow(today))
            .assertContentDescriptionEquals("1 event later, show")

        // Tap: the 22:00 entry comes into view, its end (23:00) a margin above the bottom edge, one hour above the
        // grid's end.
        composeRule.onNodeWithTag(CalendarTags.hiddenBelow(today)).performClick()
        composeRule.waitForIdle()
        assertEquals(scrollRange().maxValue().toInt() - hourPx + marginPx, scrolled())
        assertTrue(!exists(CalendarTags.hiddenBelow(today)))
        composeRule
            .onNodeWithTag(CalendarTags.hiddenAbove(today))
            .assertContentDescriptionEquals("2 events earlier, show")

        // Tap above: the nearest earlier one (4:00) starts a margin below the top edge, the 2:00 one is still above.
        composeRule.onNodeWithTag(CalendarTags.hiddenAbove(today)).performClick()
        composeRule.waitForIdle()
        assertEquals(4 * hourPx - marginPx, scrolled())
        composeRule
            .onNodeWithTag(CalendarTags.hiddenAbove(today))
            .assertContentDescriptionEquals("1 event earlier, show")
    }

    @Test
    fun `the chips update while scrolling`() {
        show(
            CalendarView.DAY,
            listOf(timed(1, "2026-10-21T01:00:00Z", "2026-10-21T02:00:00Z"), timed(2, "2026-10-21T20:00:00Z", "2026-10-21T21:00:00Z")),
        )
        assertTrue(exists(CalendarTags.hiddenBelow(today)))
        composeRule.onNodeWithTag(CalendarTags.hiddenBelow(today)).performClick()
        composeRule.waitForIdle()
        assertTrue(exists(CalendarTags.hiddenAbove(today)))
        // Back to the top by a swipe: the chip above goes, the one below comes back.
        repeat(6) { composeRule.onNodeWithTag(CalendarTags.GRID_SCROLL).performTouchInput { swipeDown() } }
        composeRule.waitForIdle()
        assertEquals(0, scrolled())
        assertTrue(!exists(CalendarTags.hiddenAbove(today)))
        assertTrue(exists(CalendarTags.hiddenBelow(today)))
    }

    @Test
    fun `in the week each column has its own chips and they fit the narrow column on a 360dp phone`() {
        val thursday = today.plusDays(1)
        show(
            CalendarView.WEEK,
            listOf(
                timed(1, "2026-10-21T06:00:00Z", "2026-10-21T07:00:00Z"),
                timed(2, "2026-10-22T21:00:00Z", "2026-10-22T22:00:00Z"),
                timed(3, "2026-10-22T22:00:00Z", "2026-10-22T23:00:00Z"),
            ),
        )
        assertTrue(!exists(CalendarTags.hiddenBelow(today)))
        val chip = composeRule.onNodeWithTag(CalendarTags.hiddenBelow(thursday))
        chip.assertContentDescriptionEquals("2 events later, show")
        val bounds = chip.getUnclippedBoundsInRoot()
        // A column is (360dp - 48dp gutter) / 7, about 44dp.
        assertTrue("chip ${bounds.width} wide", bounds.width.value <= (360f - 48f) / 7f)
    }

    @Test
    fun `tapping a week column's chip scrolls that column's nearest entry into view`() {
        val thursday = today.plusDays(1)
        show(
            CalendarView.WEEK,
            listOf(
                timed(1, "2026-10-21T06:00:00Z", "2026-10-21T07:00:00Z"),
                timed(2, "2026-10-22T21:00:00Z", "2026-10-22T22:00:00Z"),
                timed(3, "2026-10-22T23:00:00Z", "2026-10-23T00:00:00Z"),
            ),
        )
        assertEquals(6 * hourPx, scrolled())
        composeRule.onNodeWithTag(CalendarTags.hiddenBelow(thursday)).performClick()
        composeRule.waitForIdle()
        // The 21:00 entry ends a margin (32dp, 40 minutes) above the bottom edge, clear of the chip; the 23:00 one
        // is still below.
        val viewport =
            composeRule
                .onNodeWithTag(CalendarTags.GRID_SCROLL)
                .fetchSemanticsNode()
                .size.height
        assertEquals(22 * hourPx + marginPx - viewport, scrolled())
        composeRule
            .onNodeWithTag(CalendarTags.hiddenBelow(thursday))
            .assertContentDescriptionEquals("1 event later, show")
        // Wednesday's 6:00 entry is now above in its own column.
        composeRule
            .onNodeWithTag(CalendarTags.hiddenAbove(today))
            .assertContentDescriptionEquals("1 event earlier, show")
    }

    @Test
    fun `an overnight entry is counted in both of its day columns`() {
        val thursday = today.plusDays(1)
        show(
            CalendarView.WEEK,
            listOf(
                timed(1, "2026-10-21T08:00:00Z", "2026-10-21T09:00:00Z"),
                // Wednesday 23:00 until Thursday 1:00.
                timed(2, "2026-10-21T23:00:00Z", "2026-10-22T01:00:00Z"),
            ),
        )
        // Opens at 8:00: Thursday's part (until 1:00) is above, Wednesday's part (from 23:00) below.
        assertEquals(8 * hourPx, scrolled())
        composeRule
            .onNodeWithTag(CalendarTags.hiddenAbove(thursday))
            .assertContentDescriptionEquals("1 event earlier, show")
        composeRule
            .onNodeWithTag(CalendarTags.hiddenBelow(today))
            .assertContentDescriptionEquals("1 event later, show")
        assertTrue(!exists(CalendarTags.hiddenBelow(thursday)))
        assertTrue(!exists(CalendarTags.hiddenAbove(today)))

        composeRule.onNodeWithTag(CalendarTags.hiddenAbove(thursday)).performClick()
        composeRule.waitForIdle()
        assertEquals(0, scrolled())
        assertTrue(!exists(CalendarTags.hiddenAbove(thursday)))
    }

    @Test
    fun `no chips when every entry is visible`() {
        show(CalendarView.DAY, listOf(timed(1, "2026-10-21T09:00:00Z", "2026-10-21T10:00:00Z")))
        assertTrue(!exists(CalendarTags.hiddenAbove(today)))
        assertTrue(!exists(CalendarTags.hiddenBelow(today)))
    }

    @Test
    fun `the day view chips say earlier and later, the week chips show the arrow and the number only`() {
        // The chip text is hidden from semantics (clearAndSetSemantics, TalkBack reads the description), so the
        // label HiddenChip shows is checked through the function that picks it.
        val labels = mutableListOf<String>()
        composeRule.setContent {
            labels += hiddenChipLabel(count = 1, earlier = false, week = false)
            labels += hiddenChipLabel(count = 2, earlier = true, week = false)
            labels += hiddenChipLabel(count = 2, earlier = false, week = true)
            labels += hiddenChipLabel(count = 1, earlier = true, week = true)
        }
        composeRule.waitForIdle()
        assertEquals(listOf("▼ 1 later", "▲ 2 earlier", "▼ 2", "▲ 1"), labels.take(4))
    }

    @Test
    @Config(qualifiers = "de-rDE-w360dp-h640dp")
    fun `the chips are described in German`() {
        show(
            CalendarView.DAY,
            listOf(
                timed(1, "2026-10-21T02:00:00Z", "2026-10-21T03:00:00Z"),
                timed(2, "2026-10-21T04:00:00Z", "2026-10-21T05:00:00Z"),
                timed(3, "2026-10-21T22:00:00Z", "2026-10-21T23:00:00Z"),
            ),
        )
        composeRule
            .onNodeWithTag(CalendarTags.hiddenBelow(today))
            .assertContentDescriptionEquals("1 Termin später, anzeigen")
        composeRule.onNodeWithTag(CalendarTags.hiddenBelow(today)).performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(CalendarTags.hiddenAbove(today))
            .assertContentDescriptionEquals("2 Termine früher, anzeigen")
    }
}
