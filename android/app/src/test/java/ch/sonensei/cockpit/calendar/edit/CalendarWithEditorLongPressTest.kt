package ch.sonensei.cockpit.calendar.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.CalendarRepository
import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.CalendarTags
import ch.sonensei.cockpit.calendar.agenda.CalendarView
import ch.sonensei.cockpit.calendar.agenda.CalendarViewModel
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** A long press in the calendar views opens the editor with a new event there, in the add button's calendar. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class CalendarWithEditorLongPressTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")

    // Wednesday 21 October 2026, 10:20 UTC.
    private val clock = Clock.fixed(Instant.parse("2026-10-21T10:20:00Z"), utc)
    private val today = LocalDate.parse("2026-10-21")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    // A read-only feed and a hidden calendar first: the new event goes to the first visible writable one.
    private val feed = CalendarInfo(id(89), "Holidays", 0xFF00AA00L, true, true, 1)
    private val hidden = CalendarInfo(id(88), "Private", 0xFF888888L, false, false, 1)
    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)

    private val calendarRepo = mockk<CalendarRepository>()
    private val editRepo = mockk<EventEditRepository>()

    @Before
    fun setUp() {
        coEvery { calendarRepo.calendars() } returns CalendarResult.Ok(listOf(feed, hidden, work))
        coEvery { calendarRepo.occurrences(any(), any(), any()) } returns CalendarResult.Ok(emptyList())
    }

    private fun show(): Pair<CalendarViewModel, EventEditorViewModel> {
        val calendar = CalendarViewModel(calendarRepo, clock, Locale.US)
        val editor = EventEditorViewModel(editRepo, clock)
        composeRule.setContent {
            CockpitTheme {
                CalendarWithEditor(calendarViewModel = calendar, editor = editor, contact = { _, _ -> })
            }
        }
        composeRule.waitForIdle()
        return calendar to editor
    }

    @Test
    fun `a long press on the all-day strip of the day view opens a new all-day event on that day`() {
        val (calendar, editor) = show()
        val friday = today.plusDays(2)
        composeRule.runOnIdle { calendar.openDay(friday) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CalendarTags.allDay(friday), useUnmergedTree = true).performTouchInput { longClick() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("New event").assertIsDisplayed()
        val state = editor.uiState.value
        assertTrue(state.open && state.creating)
        val form = state.form!!
        assertTrue(form.allDay)
        assertEquals(friday to friday, form.startDate to form.endDate)
        assertEquals(work.id, form.calendarId)
    }

    @Test
    fun `a long press on a month day opens a new event there with the add button's defaults`() {
        val (calendar, editor) = show()
        composeRule.runOnIdle { calendar.select(CalendarView.MONTH) }
        composeRule.waitForIdle()

        // Today: from the next full hour, like the add button.
        composeRule.onNodeWithTag(CalendarTags.monthDay(today)).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("New event").assertIsDisplayed()
        val form = editor.uiState.value.form!!
        assertEquals(today to LocalTime.of(11, 0), form.startDate to form.startTime)
        assertEquals(false, form.allDay)
        assertEquals(work.id, form.calendarId)
        composeRule.onNodeWithTag(EventEditorTags.EDITOR).assertIsDisplayed()
    }

    @Test
    fun `a long press on another month day starts at the default start`() {
        val (calendar, editor) = show()
        composeRule.runOnIdle { calendar.select(CalendarView.MONTH) }
        composeRule.waitForIdle()
        val day = LocalDate.parse("2026-10-14")
        composeRule.onNodeWithTag(CalendarTags.monthDay(day)).performTouchInput { longClick() }
        composeRule.waitForIdle()
        val form = editor.uiState.value.form!!
        assertEquals(day to LocalTime.of(9, 0), form.startDate to form.startTime)
    }
}
