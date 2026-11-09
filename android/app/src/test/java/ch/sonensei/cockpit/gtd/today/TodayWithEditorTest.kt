package ch.sonensei.cockpit.gtd.today

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.CalendarRepository
import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.calendar.edit.Attendee
import ch.sonensei.cockpit.calendar.edit.AttendeeStatus
import ch.sonensei.cockpit.calendar.edit.EventDetails
import ch.sonensei.cockpit.calendar.edit.EventEditRepository
import ch.sonensei.cockpit.calendar.edit.EventEditorTags
import ch.sonensei.cockpit.calendar.edit.EventEditorViewModel
import ch.sonensei.cockpit.calendar.edit.EventExtrasRepository
import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.next.TaskCompletionRepository
import ch.sonensei.cockpit.habits.HabitsRepository
import ch.sonensei.cockpit.habits.HabitsResult
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.ZoneOffset
import java.util.UUID

/** Today with the event editor: a tapped event opens the editor, a birthday its contact, and closing the editor reloads. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class TodayWithEditorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val loadedAt = Instant.parse("2026-10-20T09:00:00Z")
    private val clock = Clock.fixed(loadedAt, ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 10, 20)

    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)
    private val holiday = Occurrence(id(1), work.id, "Company day", null, true, null, null, today, today.plusDays(1))

    // The virtual Birthdays calendar: its occurrences have no event, eventId and seriesId are the contact.
    private val birthdays = CalendarInfo(id(91), "Birthdays", 0xFFE91E63L, true, true, 1, virtual = true)
    private val adaId = id(50)
    private val birthday =
        Occurrence(
            adaId,
            birthdays.id,
            "Ada Lovelace",
            null,
            true,
            null,
            null,
            today,
            today.plusDays(1),
            seriesId = adaId,
            occurrence = today.toString(),
        )

    private val tasks =
        mockk<TaskRepository> {
            every { observe(TaskView.TODAY) } returns
                MutableStateFlow(Cached(emptyList<GtdTask>(), offline = false, refreshedAt = loadedAt))
            every { observe(TaskView.NEXT) } returns
                MutableStateFlow(Cached(emptyList<GtdTask>(), offline = false, refreshedAt = loadedAt))
        }
    private val contexts =
        mockk<ContextRepository> {
            every { observe() } returns MutableStateFlow(Cached(emptyList(), offline = false, refreshedAt = loadedAt))
        }
    private val completion = mockk<TaskCompletionRepository>()
    private val calendarRepo = mockk<CalendarRepository>()
    private val habitsRepo = mockk<HabitsRepository>()
    private val editRepo = mockk<EventEditRepository>()

    @Before
    fun setUp() {
        coEvery { calendarRepo.calendars() } returns CalendarResult.Ok(listOf(work, birthdays))
        coEvery { calendarRepo.occurrences(today, today.plusDays(1), ZoneOffset.UTC) } returns
            CalendarResult.Ok(listOf(holiday, birthday))
        coEvery { habitsRepo.list() } returns HabitsResult.Ok(emptyList())
        coEvery { editRepo.event(holiday.eventId) } returns
            CalendarResult.Ok(
                EventDetails(
                    id = holiday.eventId,
                    calendarId = work.id,
                    title = "Company day",
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

    /** The contact a tapped birthday opened, and how to go back from it. */
    private var openedContact: UUID? = null
    private var backFromContact: (() -> Unit)? = null

    private fun show(editor: EventEditorViewModel = EventEditorViewModel(editRepo, clock)): EventEditorViewModel {
        val taskList = TodayViewModel(tasks, contexts, completion, clock)
        val extras = TodayExtrasViewModel(calendarRepo, habitsRepo, clock)
        composeRule.setContent {
            CockpitTheme {
                TodayWithEditor(
                    openNext = {},
                    viewModel = taskList,
                    extras = extras,
                    editor = editor,
                    contact = { id, back ->
                        openedContact = id
                        backFromContact = back
                        Text("Contact $id", Modifier.testTag("contact"))
                    },
                )
            }
        }
        composeRule.waitForIdle()
        return editor
    }

    @Test
    fun `a tapped event opens it in the editor, and closing the editor loads the events and habits again`() {
        val editor = show()
        coVerify(exactly = 1) { habitsRepo.list() }
        coVerify(exactly = 1) { calendarRepo.occurrences(today, today.plusDays(1), ZoneOffset.UTC) }

        composeRule.onNodeWithTag(TodayExtrasTags.event(holiday)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edit event").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.TITLE).assertTextContains("Company day")
        val state = editor.uiState.value
        assertTrue(state.open && !state.creating)
        assertEquals(listOf(work, birthdays), state.calendars)

        composeRule.onNodeWithTag(EventEditorTags.CLOSE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EventEditorTags.EDITOR).assertDoesNotExist()
        composeRule.onNodeWithTag(TodayExtrasTags.event(holiday)).assertIsDisplayed()
        assertFalse(editor.uiState.value.open)
        coVerify(exactly = 2) { habitsRepo.list() }
        coVerify(exactly = 2) { calendarRepo.occurrences(today, today.plusDays(1), ZoneOffset.UTC) }
    }

    @Test
    fun `an invitation opened from Today can be answered in the editor`() {
        // The holiday is in a Google calendar and has guests, so the Today editor offers Accept / Maybe / Decline.
        val me = Attendee("me@example.org", "Me", AttendeeStatus.NEEDS_ACTION)
        val ada = Attendee("ada@example.org", "Ada Example", AttendeeStatus.ACCEPTED)
        coEvery { editRepo.event(holiday.eventId) } returns CalendarResult.Ok(holidayDetails(listOf(ada, me), version = 2))
        coEvery { editRepo.respond(holiday.eventId, 2, AttendeeStatus.DECLINED) } returns
            CalendarResult.Ok(holidayDetails(listOf(ada, me.copy(status = AttendeeStatus.DECLINED)), version = 3))
        val sources = mockk<EventExtrasRepository>()
        coEvery { sources.localCalendars() } returns CalendarResult.Ok(emptySet())
        coEvery { sources.links(any()) } returns CalendarResult.Ok(emptyList())
        coEvery { sources.invitationCalendars() } returns CalendarResult.Ok(setOf(work.id))
        coEvery { sources.ownAddresses() } returns CalendarResult.Ok(setOf("me@example.org"))

        val editor = show(EventEditorViewModel(editRepo, clock, MutableStateFlow(null), sources))
        composeRule.onNodeWithTag(TodayExtrasTags.event(holiday)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EventEditorTags.respond(AttendeeStatus.DECLINED)).performScrollTo().performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { editRepo.respond(holiday.eventId, 2, AttendeeStatus.DECLINED) }
        assertEquals(AttendeeStatus.DECLINED, editor.uiState.value.ownStatus)
        composeRule.onNodeWithTag(EventEditorTags.respond(AttendeeStatus.DECLINED)).performScrollTo().assertIsSelected()
    }

    private fun holidayDetails(
        attendees: List<Attendee>,
        version: Long,
    ) = EventDetails(
        id = holiday.eventId,
        calendarId = work.id,
        title = "Company day",
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
        version = version,
        attendees = attendees,
    )

    @Test
    fun `a tapped birthday opens its contact, not the editor, and back returns to Today`() {
        val editor = show()
        composeRule.onNodeWithTag(TodayExtrasTags.event(birthday)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("contact").assertTextContains("Contact $adaId")
        assertEquals(adaId, openedContact)
        assertFalse(editor.uiState.value.open)
        composeRule.onNodeWithTag(EventEditorTags.EDITOR).assertDoesNotExist()
        coVerify(exactly = 0) { editRepo.event(any()) }

        composeRule.runOnIdle { backFromContact!!() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("contact").assertDoesNotExist()
        composeRule.onNodeWithTag(TodayExtrasTags.event(birthday)).assertIsDisplayed()
    }
}
