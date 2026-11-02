package ch.sonensei.cockpit.calendar.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.calendar.agenda.AgendaCache
import ch.sonensei.cockpit.calendar.agenda.CachedAgenda
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.CalendarRepository
import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.CalendarTags
import ch.sonensei.cockpit.calendar.agenda.CalendarView
import ch.sonensei.cockpit.calendar.agenda.CalendarViewModel
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
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
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * Deleting from the calendar views: once the editor closes after a delete, the agenda, day and week views (and the
 * agenda cache) no longer hold the event, without a manual refresh; a delete that fails offline removes nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class CalendarWithEditorDeleteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")

    // Wednesday 21 October 2026, 10:20 UTC.
    private val clock = Clock.fixed(Instant.parse("2026-10-21T10:20:00Z"), utc)
    private val today = LocalDate.parse("2026-10-21")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)
    private val teamDay = Occurrence(id(1), work.id, "Team day", null, true, null, null, today, today.plusDays(1), version = 2)

    private val calendarRepo = mockk<CalendarRepository>()
    private val editRepo = mockk<EventEditRepository>()

    /** Whether the server has deleted the event: from then on it no longer lists it. */
    private var deletedOnServer = false

    /** An in-memory agenda cache, to see what the views stored. */
    private val cache =
        object : AgendaCache {
            var stored: CachedAgenda? = null

            override suspend fun session() = 0L

            override suspend fun store(
                session: Long,
                agenda: CachedAgenda,
            ) {
                stored = agenda
            }

            override suspend fun append(
                session: Long,
                from: LocalDate,
                to: LocalDate,
                occurrences: List<Occurrence>,
            ) = Unit

            override suspend fun read() = stored
        }

    @Before
    fun setUp() {
        coEvery { calendarRepo.calendars() } returns CalendarResult.Ok(listOf(work))
        coEvery { calendarRepo.occurrences(any(), any(), any()) } answers {
            val from = firstArg<LocalDate>()
            val to = secondArg<LocalDate>()
            val listed = if (deletedOnServer) emptyList() else listOf(teamDay)
            CalendarResult.Ok(listed.filter { it.startDate!! in from..<to })
        }
        coEvery { editRepo.event(teamDay.eventId) } returns
            CalendarResult.Ok(
                EventDetails(
                    id = teamDay.eventId,
                    calendarId = work.id,
                    title = "Team day",
                    location = null,
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
        coEvery { editRepo.delete(any(), any(), any()) } answers {
            deletedOnServer = true
            CalendarResult.Ok(Unit)
        }
    }

    private fun show(): Pair<CalendarViewModel, EventEditorViewModel> {
        val calendar = CalendarViewModel(calendarRepo, clock, Locale.US, cache)
        val editor = EventEditorViewModel(editRepo, clock)
        composeRule.setContent {
            CockpitTheme { CalendarWithEditor(calendarViewModel = calendar, editor = editor) }
        }
        composeRule.waitForIdle()
        return calendar to editor
    }

    private fun deleteTeamDay() {
        composeRule.onNodeWithTag(CalendarTags.entry(teamDay.key, today)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EventEditorTags.DELETE).performClick()
        composeRule.onNodeWithText("Delete this event?").assertIsDisplayed()
        composeRule.onNodeWithTag(EventEditorTags.DELETE_CONFIRM).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `a deleted event disappears from the agenda and its cache once the editor closes`() {
        val (calendar, editor) = show()
        composeRule.onNodeWithTag(CalendarTags.entry(teamDay.key, today)).assertIsDisplayed()
        assertEquals(listOf(teamDay), cache.stored?.occurrences)

        deleteTeamDay()

        coVerify(exactly = 1) { editRepo.delete(teamDay, 2, null) }
        assertFalse(editor.uiState.value.open)
        composeRule.onNodeWithTag(EventEditorTags.EDITOR).assertDoesNotExist()
        composeRule.onNodeWithTag(CalendarTags.entry(teamDay.key, today)).assertDoesNotExist()
        assertTrue(
            calendar.uiState.value.occurrences
                .none { it.eventId == teamDay.eventId },
        )
        assertEquals(emptyList<Occurrence>(), cache.stored?.occurrences)
    }

    @Test
    fun `a deleted event disappears from the week view without a manual refresh`() {
        val (calendar, _) = show()
        composeRule.runOnIdle { calendar.select(CalendarView.WEEK) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CalendarTags.entry(teamDay.key, today)).assertIsDisplayed()

        deleteTeamDay()

        assertEquals(CalendarView.WEEK, calendar.uiState.value.view)
        composeRule.onNodeWithTag(CalendarTags.entry(teamDay.key, today)).assertDoesNotExist()
    }

    @Test
    fun `a deleted event disappears from the day view without a manual refresh`() {
        val (calendar, _) = show()
        composeRule.runOnIdle { calendar.openDay(today) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CalendarTags.entry(teamDay.key, today)).assertIsDisplayed()

        deleteTeamDay()

        assertEquals(CalendarView.DAY, calendar.uiState.value.view)
        composeRule.onNodeWithTag(CalendarTags.entry(teamDay.key, today)).assertDoesNotExist()
    }

    @Test
    fun `an offline delete keeps the editor open with its message and removes nothing locally`() {
        coEvery { editRepo.delete(any(), any(), any()) } returns CalendarResult.Offline
        val (calendar, editor) = show()

        deleteTeamDay()

        assertTrue(editor.uiState.value.open)
        composeRule.onNodeWithText("Can't reach the server. Try again.").assertIsDisplayed()
        assertEquals(listOf(teamDay), calendar.uiState.value.occurrences)
        assertEquals(listOf(teamDay), cache.stored?.occurrences)

        composeRule.onNodeWithTag(EventEditorTags.CLOSE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CalendarTags.entry(teamDay.key, today)).assertIsDisplayed()
    }
}
