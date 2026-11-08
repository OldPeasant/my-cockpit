package ch.sonensei.cockpit.gtd.task

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The task detail route with a real view model: the menu actions convert the task, the snackbar says where it went, and
 * "Open" leaves the (trashed) task for the note or the Calendar tab.
 */
@RunWith(AndroidJUnit4::class)
class TaskDetailConvertRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val task = GtdTask(id(1), "Dentist appointment", TaskList.NEXT, 0, version = 3)
    private val source = FakeTaskDetailSource(TaskDetail(task))
    private val home = TargetCalendar(id(91), "Home", isDefault = true)
    private val note = ConvertedItem(ConvertedType.NOTE, id(70), "Dentist appointment")
    private val event = ConvertedItem(ConvertedType.EVENT, id(71), "Dentist appointment", LocalDate.of(2026, 10, 20))
    private val clock = Clock.fixed(Instant.parse("2026-10-20T08:15:00Z"), ZoneId.of("Europe/Zurich"))

    private val navigated = mutableListOf<String>()
    private val navigation =
        TaskDetailNavigation(
            close = { navigated += "close" },
            openTask = { navigated += "openTask $it" },
            openItem = { type, id -> navigated += "openItem $type $id" },
            openScreen = { navigated += "openScreen ${it.route}" },
        )

    private fun show() {
        val vm = TaskDetailViewModel(source, flowOf(emptyList()), flowOf(emptyList()), flowOf(null), null, clock)
        composeRule.setContent { CockpitTheme { TaskDetailRoute(task.id, navigation, viewModel = vm) } }
        composeRule.onNodeWithText("Dentist appointment").assertIsDisplayed()
    }

    @Test
    fun `to reference from the menu converts once confirmed, and Open closes the task and opens the note`() {
        source.convertResult = TaskResult.Ok(note)
        show()

        composeRule.onNodeWithTag(TaskDetailTags.MORE).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.TO_REFERENCE).performClick()
        assertTrue(source.calls.isEmpty())
        composeRule.onNodeWithTag(TaskDetailTags.REFERENCE_CONFIRM).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("toNote"), source.calls)
        composeRule.onNodeWithText("Moved to reference as note “Dentist appointment”").assertIsDisplayed()
        composeRule.onNodeWithText("Open").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("close", "openItem NOTE ${note.id}"), navigated)
    }

    @Test
    fun `to calendar from the menu converts with the dialog's plan, and Open closes the task and opens the Calendar tab`() {
        source.calendarsResult = TaskResult.Ok(listOf(home))
        source.convertResult = TaskResult.Ok(event)
        show()

        composeRule.onNodeWithTag(TaskDetailTags.MORE).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.TO_CALENDAR).performClick()
        composeRule.onNodeWithTag(ToCalendarTags.DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(ToCalendarTags.calendar(home)).assertIsDisplayed()
        composeRule.onNodeWithTag(ToCalendarTags.CONFIRM).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("calendars", "toEvent"), source.calls)
        val request = source.eventRequests.single()
        assertEquals(home.id, request.calendarId)
        // 10:15 in Zurich: the default start is 11:00 there, for one hour.
        assertEquals("2026-10-20T11:00+02:00", request.start.toString())
        assertEquals("2026-10-20T12:00+02:00", request.end.toString())
        composeRule.onNodeWithTag(ToCalendarTags.DIALOG).assertDoesNotExist()
        composeRule.onNodeWithText("Open").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("close", "openScreen ${CockpitDestination.Calendar.route}"), navigated)
    }

    @Test
    fun `a snackbar that times out only closes the converted task`() {
        source.convertResult = TaskResult.Ok(note)
        show()
        composeRule.onNodeWithTag(TaskDetailTags.MORE).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.TO_REFERENCE).performClick()
        composeRule.onNodeWithTag(TaskDetailTags.REFERENCE_CONFIRM).performClick()
        composeRule.waitForIdle()

        composeRule.mainClock.advanceTimeBy(SNACKBAR_TIMEOUT_MS)
        composeRule.waitForIdle()

        assertEquals(listOf("close"), navigated)
    }

    private companion object {
        /** Longer than a long snackbar (10 s). */
        const val SNACKBAR_TIMEOUT_MS = 12_000L
    }
}
