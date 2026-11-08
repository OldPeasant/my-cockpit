package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
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
 * The month view on a phone: the "new event" button over the calendar leaves the last week's cells free, a busy day
 * keeps its "+n" inside the cell, and tasks that could not be loaded can be loaded again.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS-w360dp-h640dp")
class CalendarMonthLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.parse("2026-10-21")

    // November 2026 with weeks starting on Monday: 6 weeks, 26 October to 6 December.
    private val november = LocalDate.parse("2026-11-21")
    private val lastDay = LocalDate.parse("2026-12-06")
    private val calls = mutableListOf<String>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)

    private fun allDay(
        n: Int,
        date: LocalDate,
    ) = Occurrence(id(n), work.id, "Fictitious event $n", null, true, null, null, date, date.plusDays(1))

    private fun monthState(
        occurrences: List<Occurrence> = emptyList(),
        tasksFailed: Boolean = false,
    ): CalendarUiState {
        val days = monthGrid(november, DayOfWeek.MONDAY)
        return CalendarUiState(
            view = CalendarView.MONTH,
            today = today,
            anchor = november,
            firstDayOfWeek = DayOfWeek.MONDAY,
            zone = utc,
            calendars = listOf(work),
            from = days.first(),
            to = days.last().plusDays(1),
            occurrences = occurrences,
            loaded = true,
            overlay = true,
            tasksFailed = tasksFailed,
        )
    }

    /** The calendar with the editor's floating button, as `CalendarWithEditor` draws it. */
    private fun show(state: CalendarUiState) =
        composeRule.setContent {
            CockpitTheme {
                Box(Modifier.fillMaxSize()) {
                    CalendarContent(
                        state,
                        CalendarCallbacks(
                            openDay = { calls += "day $it" },
                            retryTasks = { calls += "retry tasks" },
                        ),
                    )
                    FloatingActionButton(
                        onClick = { calls += "add" },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag(FAB),
                    ) { Icon(Icons.Outlined.Add, contentDescription = null) }
                }
            }
        }

    @Test
    fun `the floating button does not cover the last week's cells, so tapping them opens the day`() {
        show(monthState())
        assertEquals(42, monthState().days.size)

        val fab = composeRule.onNodeWithTag(FAB).getUnclippedBoundsInRoot()
        for (day in listOf(lastDay.minusDays(1), lastDay)) {
            val cell = composeRule.onNodeWithTag(CalendarTags.monthDay(day)).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$day ends at ${cell.bottom}, the button starts at ${fab.top}", cell.bottom <= fab.top)
        }

        composeRule.onNodeWithTag(CalendarTags.monthDay(lastDay)).performClick()
        composeRule.onNodeWithTag(CalendarTags.monthDay(lastDay.minusDays(1))).performClick()
        assertEquals(listOf("day $lastDay", "day ${lastDay.minusDays(1)}"), calls)
    }

    @Test
    fun `a busy day of a six-week month keeps its +n inside the cell and counts what is not listed`() {
        show(monthState(occurrences = (1..5).map { allDay(it, lastDay) }))

        val cell = composeRule.onNodeWithTag(CalendarTags.monthDay(lastDay)).getUnclippedBoundsInRoot()
        val more = composeRule.onNodeWithTag(CalendarTags.monthMore(lastDay), useUnmergedTree = true).assertIsDisplayed()
        val bounds = more.getUnclippedBoundsInRoot()
        assertTrue("+n has no height", bounds.bottom > bounds.top)
        assertTrue("+n ends at ${bounds.bottom}, the cell at ${cell.bottom}", bounds.bottom <= cell.bottom)

        // Listed titles plus n are all five entries.
        val listed =
            (1..5).count { n ->
                composeRule
                    .onAllNodes(hasText("Fictitious event $n"), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        assertTrue("listed $listed titles", listed in 0..MONTH_TITLES)
        more.assertTextContains("+${5 - listed}")
        composeRule.onNodeWithContentDescription("Sunday, December 6, 2026, 5 entries").assertExists()
    }

    /** Checks that the last day's "+n" is shown inside its cell and, with the listed titles, counts all [count] entries. */
    private fun assertMoreInsideLastCell(count: Int) {
        val cell = composeRule.onNodeWithTag(CalendarTags.monthDay(lastDay)).getUnclippedBoundsInRoot()
        val more = composeRule.onNodeWithTag(CalendarTags.monthMore(lastDay), useUnmergedTree = true).assertIsDisplayed()
        val bounds = more.getUnclippedBoundsInRoot()
        assertTrue("+n has no height", bounds.bottom > bounds.top)
        assertTrue("+n ends at ${bounds.bottom}, the cell at ${cell.bottom}", bounds.bottom <= cell.bottom)
        val listed =
            (1..count).count { n ->
                composeRule
                    .onAllNodes(hasText("Fictitious event $n"), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        more.assertTextContains("+${count - listed}")
    }

    @Test
    @Config(fontScale = 1.5f)
    fun `with a large font fewer titles are listed and +n still stays inside the cell`() {
        show(monthState(occurrences = (1..5).map { allDay(it, lastDay) }))

        assertMoreInsideLastCell(5)
    }

    @Test
    fun `a theme without a line height for small labels still shows the month and its +n`() {
        // TextUnit.Unspecified cannot be converted to dp: the month view falls back instead of crashing.
        composeRule.setContent {
            val typography = MaterialTheme.typography
            MaterialTheme(typography = typography.copy(labelSmall = typography.labelSmall.copy(lineHeight = TextUnit.Unspecified))) {
                CalendarContent(monthState(occurrences = (1..5).map { allDay(it, lastDay) }), CalendarCallbacks())
            }
        }

        composeRule.onNodeWithTag(CalendarTags.MONTH).assertIsDisplayed()
        assertMoreInsideLastCell(5)
    }

    @Test
    fun `the tasks line offers a retry`() {
        show(monthState(tasksFailed = true))

        composeRule.onNodeWithTag(CalendarTags.TASKS_FAILED).assertTextContains("The tasks could not be loaded.")
        composeRule.onNodeWithContentDescription("Load the tasks again").assertIsDisplayed().performClick()
        assertEquals(listOf("retry tasks"), calls)
    }

    @Test
    @Config(qualifiers = "de-rDE-w360dp-h640dp")
    fun `the tasks retry is described in German`() {
        show(monthState(tasksFailed = true))

        composeRule.onNodeWithTag(CalendarTags.TASKS_RETRY).assertTextContains("Erneut versuchen")
        composeRule.onNodeWithContentDescription("Aufgaben erneut laden").assertIsDisplayed()
    }

    private companion object {
        const val FAB = "test-fab"
    }
}
