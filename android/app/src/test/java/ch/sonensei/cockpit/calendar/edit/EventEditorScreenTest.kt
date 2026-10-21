package ch.sonensei.cockpit.calendar.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** The event form alone, driven by states the tests control: fields, validation messages and the scope prompt. */
@RunWith(AndroidJUnit4::class)
class EventEditorScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val work = CalendarInfo(UUID.fromString("00000000-0000-7000-8000-000000000090"), "Work", null, true, false, 1)
    private val day = LocalDate.parse("2026-10-21")
    private val form =
        EventForm(
            title = "Standup",
            calendarId = work.id,
            startDate = day,
            startTime = LocalTime.of(9, 0),
            endDate = day,
            endTime = LocalTime.of(9, 15),
            zone = ZoneId.of("Europe/Zurich"),
            repeat = Repeat.WEEKLY,
        )
    private val calls = mutableListOf<String>()

    /** The form as the last `update` left it; `null` while nothing was changed. */
    private var updated: EventForm? = null

    private fun show(initial: EventEditorUiState): (EventEditorUiState) -> Unit {
        var state by mutableStateOf(initial)
        composeRule.setContent {
            CockpitTheme {
                EventEditorContent(
                    state = state,
                    callbacks =
                        EventEditorCallbacks(
                            update = { change ->
                                state = state.copy(form = state.form?.let(change))
                                updated = state.form
                            },
                            save = { calls += "save" },
                            close = { calls += "close" },
                            chooseScope = { calls += "scope:$it" },
                            dismissScope = { calls += "dismiss" },
                            reload = { calls += "reload" },
                        ),
                )
            }
        }
        return { state = it }
    }

    @Test
    fun `the form shows the event and edits its fields`() {
        show(EventEditorUiState(open = true, form = form, calendars = listOf(work)))
        composeRule.onNodeWithText("Edit event").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.CALENDAR).assertTextContains("Work")
        composeRule.onNodeWithTag(EventEditorTags.REPEAT).assertTextContains("Every week")
        composeRule.onNodeWithTag(EventEditorTags.TITLE).performTextReplacement("Daily standup")
        composeRule.onNodeWithTag(EventEditorTags.TITLE).assertTextContains("Daily standup")
        composeRule.onNodeWithTag(EventEditorTags.START_TIME).assertIsDisplayed()

        composeRule.onNodeWithTag(EventEditorTags.ALL_DAY).performClick()
        composeRule.onNodeWithTag(EventEditorTags.START_TIME).assertDoesNotExist()

        composeRule.onNodeWithTag(EventEditorTags.SAVE).performClick()
        composeRule.onNodeWithTag(EventEditorTags.CLOSE).performClick()
        assertEquals(listOf("save", "close"), calls)
    }

    @Test
    fun `the repeat presets are offered`() {
        show(EventEditorUiState(open = true, creating = true, form = form.copy(repeat = Repeat.NONE), calendars = listOf(work)))
        composeRule.onNodeWithTag(EventEditorTags.REPEAT).performScrollTo().performClick()
        REPEAT_PRESETS.forEach { composeRule.onNodeWithTag(EventEditorTags.repeat(it)).assertIsDisplayed() }
        composeRule.onNodeWithTag(EventEditorTags.repeat(Repeat.CUSTOM)).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.repeat(Repeat.MONTHLY)).performClick()
        composeRule.onNodeWithTag(EventEditorTags.REPEAT).assertTextContains("Every month")
    }

    @Test
    fun `a custom rule of a series is kept as a choice`() {
        show(
            EventEditorUiState(
                open = true,
                form = form.copy(repeat = Repeat.CUSTOM, rule = "FREQ=WEEKLY;BYDAY=MO"),
                calendars = listOf(work),
            ),
        )
        composeRule.onNodeWithTag(EventEditorTags.REPEAT).assertTextContains("Custom (FREQ=WEEKLY;BYDAY=MO)")
        composeRule.onNodeWithTag(EventEditorTags.REPEAT).performScrollTo().performClick()
        composeRule.onNodeWithTag(EventEditorTags.repeat(Repeat.CUSTOM)).assertIsDisplayed()
        REPEAT_PRESETS.forEach { composeRule.onNodeWithTag(EventEditorTags.repeat(it)).assertIsDisplayed() }
        composeRule.onNodeWithTag(EventEditorTags.repeat(Repeat.DAILY)).performClick()
        assertEquals("FREQ=DAILY", updated?.rrule)

        // The loaded rule can still be chosen again after a preset was picked.
        composeRule.onNodeWithTag(EventEditorTags.REPEAT).performScrollTo().performClick()
        composeRule.onNodeWithTag(EventEditorTags.repeat(Repeat.CUSTOM)).assertIsDisplayed().performClick()
        assertEquals("FREQ=WEEKLY;BYDAY=MO", updated?.rrule)
        composeRule.onNodeWithTag(EventEditorTags.REPEAT).assertTextContains("Custom (FREQ=WEEKLY;BYDAY=MO)")
    }

    @Test
    fun `a failed save says so and offers no reload, saving stays possible`() {
        show(EventEditorUiState(open = true, form = form, calendars = listOf(work), message = EditorMessage.FAILED))
        composeRule.onNodeWithText("The event could not be saved.").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.RELOAD).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.SAVE).assertIsEnabled()
    }

    @Test
    fun `no answer while saving asks to try again, without claiming nothing was saved`() {
        show(EventEditorUiState(open = true, form = form, calendars = listOf(work), message = EditorMessage.OFFLINE))
        composeRule.onNodeWithText("Can't reach the server. Try again.").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing was saved", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.SAVE).assertIsEnabled()
    }

    @Test
    fun `while a save runs the editor can't be closed`() {
        val update = show(EventEditorUiState(open = true, form = form, calendars = listOf(work), saving = true))
        composeRule.onNodeWithTag(EventEditorTags.CLOSE).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(EventEditorTags.SAVE).assertIsNotEnabled()
        assertEquals(emptyList<String>(), calls)

        update(EventEditorUiState(open = true, form = form, calendars = listOf(work), message = EditorMessage.OFFLINE))
        composeRule.onNodeWithTag(EventEditorTags.CLOSE).assertIsEnabled().performClick()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `an occurrence that is gone says so, without a form or a reload`() {
        show(EventEditorUiState(open = true, calendars = listOf(work), series = true, message = EditorMessage.GONE))
        composeRule.onNodeWithText("This event was changed or removed elsewhere.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.TITLE).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.RELOAD).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(EventEditorTags.CLOSE).performClick()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `a failed load shows no form and offers a retry`() {
        show(EventEditorUiState(open = true, calendars = listOf(work), message = EditorMessage.LOAD_OFFLINE))
        composeRule.onNodeWithText("Can't reach the server. The event could not be loaded.").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.TITLE).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(EventEditorTags.RELOAD).assertTextContains("Retry").performClick()
        assertEquals(listOf("reload"), calls)
    }

    @Test
    fun `a new event is titled as new and an all-day one hides times and zone`() {
        show(EventEditorUiState(open = true, creating = true, form = form.copy(allDay = true), calendars = listOf(work)))
        composeRule.onNodeWithText("New event").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.START_TIME).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.END_TIME).assertDoesNotExist()
        composeRule.onNodeWithText("Time zone", substring = true).assertDoesNotExist()
        // With a single writable calendar there is nothing to choose.
        composeRule.onNodeWithTag(EventEditorTags.CALENDAR).assertIsNotEnabled()
    }

    @Test
    fun `confirming the date and time pickers keeps the date and time shown`() {
        show(EventEditorUiState(open = true, form = form, calendars = listOf(work)))
        composeRule.onNodeWithTag(EventEditorTags.START_DATE).performClick()
        composeRule.onNodeWithText("OK").performClick()
        assertEquals(day, updated?.startDate)
        assertEquals(day, updated?.endDate)

        composeRule.onNodeWithTag(EventEditorTags.START_TIME).performClick()
        composeRule.onNodeWithText("OK").performClick()
        assertEquals(LocalTime.of(9, 0) to LocalTime.of(9, 15), updated?.startTime to updated?.endTime)
    }

    @Test
    fun `a cancelled picker changes nothing`() {
        show(EventEditorUiState(open = true, form = form, calendars = listOf(work)))
        composeRule.onNodeWithTag(EventEditorTags.END_TIME).performScrollTo().performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag(EventEditorTags.END_DATE).performScrollTo().performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(null, updated)
        composeRule.onNodeWithText("OK").assertDoesNotExist()
    }

    @Test
    fun `validation messages show once a save was tried`() {
        val bad = form.copy(title = "", calendarId = null, endTime = LocalTime.of(8, 0))
        val update = show(EventEditorUiState(open = true, creating = true, form = bad, calendars = emptyList()))
        composeRule.onNodeWithText("Enter a title.").assertDoesNotExist()
        update(EventEditorUiState(open = true, creating = true, form = bad, calendars = emptyList(), showErrors = true))
        composeRule.onNodeWithText("Enter a title.").assertIsDisplayed()
        composeRule.onNodeWithText("Choose a calendar. Add one on the web if there is none.").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.END_ERROR).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the scope prompt offers the given scopes`() {
        show(EventEditorUiState(open = true, form = form, calendars = listOf(work), series = true, scopes = EditScope.entries))
        composeRule.onNodeWithText("Which events of the series should this apply to?").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.scope(EditScope.THIS)).assertTextContains("Only this event")
        composeRule.onNodeWithTag(EventEditorTags.scope(EditScope.ALL)).assertTextContains("All events in the series")
        composeRule.onNodeWithTag(EventEditorTags.scope(EditScope.FOLLOWING)).performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("scope:FOLLOWING", "dismiss"), calls)
    }

    @Test
    fun `a new repeat rule leaves THIS out of the prompt`() {
        show(EventEditorUiState(open = true, form = form, calendars = listOf(work), scopes = listOf(EditScope.FOLLOWING, EditScope.ALL)))
        composeRule.onNodeWithTag(EventEditorTags.scope(EditScope.THIS)).assertDoesNotExist()
        composeRule.onNodeWithTag(EventEditorTags.scope(EditScope.FOLLOWING)).assertIsDisplayed()
    }

    @Test
    fun `a conflict disables saving and offers a reload`() {
        show(EventEditorUiState(open = true, form = form, calendars = listOf(work), message = EditorMessage.CONFLICT))
        composeRule.onNodeWithText("changed elsewhere", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(EventEditorTags.RELOAD).performClick()
        assertEquals(listOf("reload"), calls)
    }

    @Test
    fun `a read-only calendar disables the form`() {
        val feed = work.copy(readOnly = true)
        show(EventEditorUiState(open = true, form = form, calendars = listOf(feed)))
        composeRule.onNodeWithText("This calendar is read-only. Its events can't be changed here.").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(EventEditorTags.TITLE).assertIsNotEnabled()
    }

    @Test
    fun `the calendar of a new event can be chosen`() {
        val home = CalendarInfo(UUID.fromString("00000000-0000-7000-8000-000000000091"), "Home", null, true, false, 1)
        show(EventEditorUiState(open = true, creating = true, form = form, calendars = listOf(work, home)))
        composeRule.onNodeWithTag(EventEditorTags.CALENDAR).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(EventEditorTags.calendar(home.id)).performClick()
        composeRule.onNodeWithTag(EventEditorTags.CALENDAR).assertTextContains("Home")
    }
}
