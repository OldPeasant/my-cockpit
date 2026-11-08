package ch.sonensei.cockpit.gtd.task

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.TimeZone
import java.util.UUID

/** The task detail screen's "To reference" / "To calendar" menu items, the shared dialog and the snackbar with "Open". */
@RunWith(AndroidJUnit4::class)
class TaskDetailConvertScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val task = GtdTask(id(1), "Dentist appointment", TaskList.NEXT, 0, version = 3)
    private val work = TargetCalendar(id(90), "Work")
    private val home = TargetCalendar(id(91), "Home", isDefault = true)
    private val plan = EventPlan(home.id, LocalDate.of(2026, 10, 20), LocalTime.of(11, 0))
    private val note = ConvertedItem(ConvertedType.NOTE, id(70), "Dentist appointment")
    private val event = ConvertedItem(ConvertedType.EVENT, id(71), "Dentist appointment", LocalDate.of(2026, 10, 22))

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(TaskDetailUiState())

    private val callbacks =
        TaskDetailCallbacks(
            close = { calls += "close" },
            dismiss = { calls += "dismiss $it" },
            toReference = { calls += "toReference" },
            openToCalendar = { calls += "openToCalendar" },
            editPlan = { change ->
                state.calendarDialog?.let { state = state.copy(calendarDialog = it.copy(plan = change(it.plan))) }
                calls += "editPlan"
            },
            retryCalendars = { calls += "retryCalendars" },
            cancelToCalendar = { calls += "cancel" },
            confirmToCalendar = { calls += "confirm" },
            openConverted = { calls += "open ${it.type} ${it.id}" },
        )

    private fun loaded(detail: TaskDetail = TaskDetail(task)) = TaskDetailUiState(loading = false, detail = detail)

    private fun show(initial: TaskDetailUiState) {
        state = initial
        composeRule.setContent { CockpitTheme { TaskDetailContent(state, callbacks) } }
    }

    private fun dialog(choice: CalendarChoice = CalendarChoice(listOf(home, work))) =
        loaded().copy(calendarDialog = CalendarDialogState(plan, choice))

    // --- menu ---

    @Test
    fun `the menu of an open task offers To reference and To calendar`() {
        show(loaded())

        composeRule.onNodeWithTag(TaskDetailTags.MORE).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.TO_REFERENCE).assertTextContains("To reference").performClick()
        composeRule.onNodeWithTag(TaskDetailTags.TO_REFERENCE).assertDoesNotExist()
        composeRule.onNodeWithTag(TaskDetailTags.MORE).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.TO_CALENDAR).assertTextContains("To calendar").performClick()

        assertEquals(listOf("toReference", "openToCalendar"), calls)
    }

    @Test
    fun `a completed task offers no conversion`() {
        show(loaded(TaskDetail(task, completedAt = Instant.parse("2026-10-19T08:00:00Z"))))

        composeRule.onNodeWithTag(TaskDetailTags.MORE).performClick()

        composeRule.onNodeWithTag(TaskDetailTags.TO_REFERENCE).assertDoesNotExist()
        composeRule.onNodeWithTag(TaskDetailTags.TO_CALENDAR).assertDoesNotExist()
    }

    @Test
    fun `a converted task's menu cannot be opened`() {
        show(loaded().copy(converted = note))

        composeRule.onNodeWithTag(TaskDetailTags.MORE).assertIsNotEnabled()
    }

    // --- dialog ---

    @Test
    fun `the dialog shows the calendars with the planned one selected, a start and the durations`() {
        show(dialog())

        composeRule.onNodeWithTag(ToCalendarTags.DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("Move to the calendar").assertIsDisplayed()
        composeRule.onNodeWithTag(ToCalendarTags.calendar(home)).assertIsSelected()
        composeRule.onNodeWithTag(ToCalendarTags.ALL_DAY).assertIsOff()
        composeRule
            .onNodeWithTag(
                ToCalendarTags.DATE,
            ).assertTextContains(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(plan.date))
        composeRule.onNodeWithTag(ToCalendarTags.TIME).assertIsDisplayed()
        composeRule.onNodeWithTag(ToCalendarTags.duration(60)).assertIsSelected().assertTextContains("1 h")
        composeRule.onNodeWithTag(ToCalendarTags.duration(15)).assertTextContains("15 min")
        composeRule.onNodeWithTag(ToCalendarTags.duration(90)).assertTextContains("1 h 30 min")
        composeRule.onNodeWithTag(ToCalendarTags.CONFIRM).assertIsEnabled()
    }

    @Test
    fun `choosing a calendar and a duration edits the plan, confirm and cancel report back`() {
        show(dialog())

        composeRule.onNodeWithTag(ToCalendarTags.calendar(work)).performClick()
        composeRule.onNodeWithTag(ToCalendarTags.duration(30)).performScrollTo().performClick()

        assertEquals(work.id, state.calendarDialog?.plan?.calendarId)
        assertEquals(30, state.calendarDialog?.plan?.durationMin)
        composeRule.onNodeWithTag(ToCalendarTags.calendar(work)).assertIsSelected()
        composeRule.onNodeWithTag(ToCalendarTags.CONFIRM).performClick()
        composeRule.onNodeWithTag(ToCalendarTags.CANCEL).performClick()
        assertEquals(listOf("editPlan", "editPlan", "confirm", "cancel"), calls)
    }

    @Test
    fun `all day hides the time and the durations`() {
        show(dialog())

        composeRule.onNodeWithTag(ToCalendarTags.ALL_DAY).performClick()

        assertEquals(true, state.calendarDialog?.plan?.allDay)
        composeRule.onNodeWithTag(ToCalendarTags.ALL_DAY).assertIsOn()
        composeRule.onNodeWithTag(ToCalendarTags.TIME).assertDoesNotExist()
        composeRule.onNodeWithTag(ToCalendarTags.duration(60)).assertDoesNotExist()
        composeRule.onNodeWithTag(ToCalendarTags.DATE).assertIsDisplayed()
    }

    @Test
    fun `while the calendars load it says so and cannot confirm`() {
        show(dialog(CalendarChoice()))

        composeRule.onNodeWithTag(ToCalendarTags.LOADING).assertTextContains("Loading calendars…")
        composeRule.onNodeWithTag(ToCalendarTags.CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `calendars that failed to load can be retried and block confirming`() {
        show(dialog(CalendarChoice(failed = true)))

        composeRule.onNodeWithTag(ToCalendarTags.FAILED).assertTextContains("The calendars could not be loaded.")
        composeRule.onNodeWithTag(ToCalendarTags.CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithTag(ToCalendarTags.RETRY).performClick()

        assertEquals(listOf("retryCalendars"), calls)
    }

    @Test
    fun `without a writable calendar it says so and cannot confirm`() {
        show(dialog(CalendarChoice(emptyList())))

        composeRule.onNodeWithTag(ToCalendarTags.NONE).assertTextContains("There is no calendar to add events to.")
        composeRule.onNodeWithTag(ToCalendarTags.CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `an invalid plan or a running request cannot be confirmed`() {
        show(dialog().copy(calendarDialog = CalendarDialogState(plan.copy(calendarId = null), CalendarChoice(listOf(home)))))
        composeRule.onNodeWithTag(ToCalendarTags.CONFIRM).assertIsNotEnabled()

        state = dialog().copy(busy = true)
        composeRule.onNodeWithTag(ToCalendarTags.CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `the date picker keeps the planned day when confirmed, in a zone east of UTC`() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        try {
            show(dialog())

            composeRule.onNodeWithTag(ToCalendarTags.DATE).performClick()
            composeRule.onNodeWithText("OK").assertExists().performClick()

            composeRule.onNodeWithText("OK").assertDoesNotExist()
            assertEquals(plan.date, state.calendarDialog?.plan?.date)
            assertEquals(listOf("editPlan"), calls)
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `the time picker keeps the planned time when confirmed, cancelling it changes nothing`() {
        show(dialog())

        composeRule.onNodeWithTag(ToCalendarTags.TIME).performClick()
        composeRule.onNodeWithText("OK").assertExists().performClick()
        assertEquals(plan.time, state.calendarDialog?.plan?.time)
        assertEquals(listOf("editPlan"), calls)

        composeRule.onNodeWithTag(ToCalendarTags.TIME).performClick()
        composeRule.onAllNodesWithText("Cancel")[1].performClick()
        composeRule.onNodeWithText("OK").assertDoesNotExist()
        composeRule.onNodeWithTag(ToCalendarTags.DIALOG).assertIsDisplayed()
        assertEquals(listOf("editPlan"), calls)
    }

    @Test
    fun `cancelling the date picker keeps the dialog and the day`() {
        show(dialog())

        composeRule.onNodeWithTag(ToCalendarTags.DATE).performClick()
        composeRule.onAllNodesWithText("Cancel")[1].performClick()

        composeRule.onNodeWithText("OK").assertDoesNotExist()
        composeRule.onNodeWithTag(ToCalendarTags.DIALOG).assertIsDisplayed()
        assertEquals(plan.date, state.calendarDialog?.plan?.date)
        assertTrue(calls.isEmpty())
    }

    // --- snackbar ---

    @Test
    fun `after converting to a note the snackbar names it and Open opens it`() {
        show(loaded().copy(converted = note, message = DetailMessage.Converted(note)))

        composeRule.onNodeWithText("Moved to reference as note “Dentist appointment”").assertIsDisplayed()
        composeRule.onNodeWithText("Open").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("dismiss ${DetailMessage.Converted(note)}", "open NOTE ${note.id}"), calls)
    }

    @Test
    fun `after converting to an event the snackbar names its day, and the screen closes when it times out`() {
        show(loaded().copy(converted = event, message = DetailMessage.Converted(event)))
        val day = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(event.day)

        composeRule.onNodeWithText("Moved to the calendar on $day as event “Dentist appointment”").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(SNACKBAR_TIMEOUT_MS)
        composeRule.waitForIdle()

        assertEquals(listOf("dismiss ${DetailMessage.Converted(event)}", "close"), calls)
    }

    @Test
    fun `an event without a known day is named without one`() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

        val text = convertedText(resources, event.copy(day = null))

        assertEquals("Moved to the calendar as event “Dentist appointment”", text)
        assertTrue(convertedText(resources, note).contains("Dentist appointment"))
    }

    private companion object {
        /** Longer than a long snackbar (10 s). */
        const val SNACKBAR_TIMEOUT_MS = 12_000L
    }
}
