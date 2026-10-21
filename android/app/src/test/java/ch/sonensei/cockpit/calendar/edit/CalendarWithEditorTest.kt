package ch.sonensei.cockpit.calendar.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.CalendarRepository
import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.CalendarTags
import ch.sonensei.cockpit.calendar.agenda.CalendarViewModel
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** The calendar views with the editor: the add button and a tapped occurrence open the form, closing it goes back. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class CalendarWithEditorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")

    // Wednesday 21 October 2026, 10:20 UTC.
    private val clock = Clock.fixed(Instant.parse("2026-10-21T10:20:00Z"), utc)
    private val today = LocalDate.parse("2026-10-21")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)
    private val holiday = Occurrence(id(1), work.id, "Team day", null, true, null, null, today, today.plusDays(1))

    private val calendarRepo = mockk<CalendarRepository>()
    private val editRepo = mockk<EventEditRepository>()

    @Before
    fun setUp() {
        coEvery { calendarRepo.calendars() } returns CalendarResult.Ok(listOf(work))
        coEvery { calendarRepo.occurrences(any(), any(), any()) } answers {
            val from = firstArg<LocalDate>()
            val to = secondArg<LocalDate>()
            CalendarResult.Ok(listOf(holiday).filter { it.startDate!! in from..<to })
        }
        coEvery { editRepo.event(holiday.eventId) } returns
            CalendarResult.Ok(
                EventDetails(
                    id = holiday.eventId,
                    calendarId = work.id,
                    title = "Team day",
                    location = "Lakeside hall",
                    description = null,
                    allDay = true,
                    start = null,
                    end = null,
                    startDate = today,
                    endDate = today.plusDays(1),
                    timeZone = null,
                    rrule = null,
                    seriesId = null,
                    version = 2,
                ),
            )
    }

    private fun show(): Pair<CalendarViewModel, EventEditorViewModel> {
        val calendar = CalendarViewModel(calendarRepo, clock, Locale.US)
        val editor = EventEditorViewModel(editRepo, clock)
        composeRule.setContent {
            CockpitTheme { CalendarWithEditor(calendarViewModel = calendar, editor = editor) }
        }
        composeRule.waitForIdle()
        return calendar to editor
    }

    @Test
    fun `the add button opens a new event today from the next full hour, closing goes back to the views`() {
        val (_, editor) = show()
        composeRule.onNodeWithTag(EventEditorTags.ADD).performClick()
        composeRule.onNodeWithText("New event").assertIsDisplayed()
        val form = editor.uiState.value.form!!
        assertEquals(today to LocalTime.of(11, 0), form.startDate to form.startTime)
        assertEquals(work.id, form.calendarId)
        composeRule.onNodeWithTag(EventEditorTags.CALENDAR).assertTextContains("Work")

        composeRule.onNodeWithTag(EventEditorTags.CLOSE).performClick()
        composeRule.onNodeWithTag(EventEditorTags.EDITOR).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.ADD).assertIsDisplayed()
        assertFalse(editor.uiState.value.open)
    }

    @Test
    fun `in the day view the add button opens a new event on the day shown, at nine`() {
        val (calendar, editor) = show()
        val friday = today.plusDays(2)
        composeRule.runOnIdle { calendar.openDay(friday) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EventEditorTags.ADD).performClick()
        composeRule.waitForIdle()
        val form = editor.uiState.value.form!!
        assertEquals(friday to LocalTime.of(9, 0), form.startDate to form.startTime)
    }

    @Test
    fun `a tapped occurrence opens its event for editing`() {
        val (_, editor) = show()
        composeRule.onNodeWithTag(CalendarTags.entry(holiday.key, today)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edit event").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.TITLE).assertTextContains("Team day")
        val state = editor.uiState.value
        assertTrue(state.open && !state.creating)
        assertEquals(listOf(work), state.calendars)
        assertEquals(
            "Lakeside hall",
            state.form
                ?.location,
        )
    }
}
