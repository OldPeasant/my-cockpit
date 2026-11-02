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
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** The Delete action of the event form: when it is offered, its confirmation and the scope prompt of a delete. */
@RunWith(AndroidJUnit4::class)
class EventEditorDeleteScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val work = CalendarInfo(UUID.fromString("00000000-0000-7000-8000-000000000090"), "Work", null, true, false, 1)
    private val feed = CalendarInfo(UUID.fromString("00000000-0000-7000-8000-000000000089"), "Holidays", null, true, true, 2)
    private val day = LocalDate.parse("2026-10-21")
    private val form =
        EventForm(
            title = "Dentist",
            calendarId = work.id,
            startDate = day,
            startTime = LocalTime.of(9, 0),
            endDate = day,
            endTime = LocalTime.of(10, 0),
            zone = ZoneId.of("Europe/Zurich"),
        )
    private val existing = EventEditorUiState(open = true, form = form, calendars = listOf(work, feed))
    private val calls = mutableListOf<String>()

    private fun show(initial: EventEditorUiState): (EventEditorUiState) -> Unit {
        var state by mutableStateOf(initial)
        composeRule.setContent {
            CockpitTheme {
                EventEditorContent(
                    state = state,
                    callbacks =
                        EventEditorCallbacks(
                            chooseScope = { calls += "scope:$it" },
                            dismissScope = { calls += "dismissScope" },
                            delete = { calls += "delete" },
                            confirmDelete = { calls += "confirm" },
                            dismissDelete = { calls += "dismissDelete" },
                        ),
                )
            }
        }
        return { state = it }
    }

    @Test
    fun `an existing event offers Delete, which asks for a confirmation`() {
        val set = show(existing)
        composeRule
            .onNodeWithTag(EventEditorTags.DELETE)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf("delete"), calls)

        set(existing.copy(confirmingDelete = true))
        composeRule.onNodeWithTag(EventEditorTags.DELETE_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("Delete this event?").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag(EventEditorTags.DELETE_CONFIRM).performClick()
        assertEquals(listOf("delete", "dismissDelete", "confirm"), calls)
    }

    @Test
    fun `no Delete for a new event, a read-only calendar, while loading or while saving`() {
        val set = show(existing.copy(creating = true))
        composeRule.onNodeWithTag(EventEditorTags.DELETE).assertDoesNotExist()
        set(existing.copy(form = form.copy(calendarId = feed.id)))
        composeRule.onNodeWithTag(EventEditorTags.DELETE).assertDoesNotExist()
        set(existing.copy(form = null, loading = true))
        composeRule.onNodeWithTag(EventEditorTags.DELETE).assertDoesNotExist()
        set(existing.copy(saving = true))
        composeRule.onNodeWithTag(EventEditorTags.DELETE).assertDoesNotExist()
        set(existing.copy(message = EditorMessage.CONFLICT))
        composeRule.onNodeWithTag(EventEditorTags.DELETE).assertIsNotEnabled()
        composeRule.onNodeWithTag(EventEditorTags.RELOAD).assertIsDisplayed()
    }

    @Test
    fun `the scope prompt of a delete says so and can be cancelled`() {
        show(existing.copy(scopes = EditScope.entries.toList(), deleting = true))
        composeRule.onNodeWithText("Delete a repeating event").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.scope(EditScope.FOLLOWING)).performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("scope:FOLLOWING", "dismissScope"), calls)
    }

    @Test
    fun `a failed delete shows its message`() {
        show(existing.copy(message = EditorMessage.DELETE_FAILED))
        composeRule.onNodeWithText("The event could not be deleted.").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "de")
    fun `the delete texts are German in German`() {
        show(existing.copy(confirmingDelete = true))
        composeRule.onNodeWithText("Diesen Termin löschen?").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.DELETE).assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun `the delete button, scope prompt and failure are German in German`() {
        val set = show(existing)
        composeRule.onNodeWithTag(EventEditorTags.DELETE).assertTextContains("Löschen")
        set(existing.copy(scopes = EditScope.entries.toList(), deleting = true))
        composeRule.onNodeWithText("Wiederkehrenden Termin löschen").assertIsDisplayed()
        set(existing.copy(message = EditorMessage.DELETE_FAILED))
        composeRule.onNodeWithText("Der Termin konnte nicht gelöscht werden.").assertIsDisplayed()
    }

    @Test
    fun `the scope prompt of a change keeps its own title`() {
        show(existing.copy(scopes = EditScope.entries.toList(), deleting = false))
        composeRule.onNodeWithText("Change a repeating event").assertIsDisplayed()
        composeRule.onNodeWithText("Delete a repeating event").assertDoesNotExist()
    }
}
