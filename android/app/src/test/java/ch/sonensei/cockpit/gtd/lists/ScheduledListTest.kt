package ch.sonensei.cockpit.gtd.lists

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.gtd.task.LocalTaskOpener
import ch.sonensei.cockpit.gtd.task.TaskOpener
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/** The Scheduled screen alone, driven by a state the test controls, and its order. */
@RunWith(AndroidJUnit4::class)
class ScheduledListTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 20)
    private val dentist = GtdTask(id(1), "Call dentist", TaskList.SCHEDULED, 0, 1, scheduledDate = today)
    private val tax = GtdTask(id(2), "File tax return", TaskList.SCHEDULED, 1, 1, scheduledDate = today.plusDays(1))
    private val tyres = GtdTask(id(3), "Change tyres", TaskList.SCHEDULED, 2, 1, scheduledDate = today.plusDays(10))
    private val plants = GtdTask(id(4), "Water plants", TaskList.SCHEDULED, 3, 1, scheduledDate = today.plusDays(10))
    private val loose = GtdTask(id(5), "Undated", TaskList.SCHEDULED, 4, 1)
    private val loaded =
        TaskListUiState(tasks = byScheduledDate(listOf(tyres, loose, tax, plants, dentist)), loading = false, loaded = true, today = today)

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(loaded)
    private val callbacks =
        LiveListCallbacks(retry = { calls += "retry" }, complete = { calls += "complete ${it.title}" })

    private fun show(
        initial: TaskListUiState,
        opener: TaskOpener? = null,
    ) {
        state = initial
        composeRule.setContent {
            CockpitTheme {
                CompositionLocalProvider(LocalTaskOpener provides opener) {
                    ScheduledContent(state, callbacks)
                }
            }
        }
    }

    @Test
    fun `orders by scheduled date, undated last, ties in list order`() {
        assertEquals(listOf(dentist, tax, tyres, plants, loose), loaded.tasks)
    }

    @Test
    fun `groups tasks under a header per date`() {
        show(loaded)
        composeRule.onNodeWithTag(ScheduledTags.day(today)).assertIsDisplayed()
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("Tomorrow").assertIsDisplayed()
        val later = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(today.plusDays(10))
        composeRule.onNodeWithText(later).assertIsDisplayed()
        composeRule.onNodeWithTag(ScheduledTags.NO_DATE).assertIsDisplayed()
        composeRule.onNodeWithText("No date").assertIsDisplayed()
    }

    @Test
    fun `completes a task with its checkbox`() {
        show(loaded)
        composeRule.onNodeWithTag(TaskListTags.complete(tax.id)).performClick()
        assertEquals(listOf("complete File tax return"), calls)
    }

    @Test
    fun `tapping a task opens its detail`() {
        show(loaded, opener = { id, snapshot, _ -> calls += "open $id ${snapshot?.title}" })
        composeRule.onNodeWithTag(TaskListTags.row(tyres.id)).performClick()
        assertEquals(listOf("open ${tyres.id} Change tyres"), calls)
    }

    @Test
    fun `a past date is shown as yesterday`() {
        val late = dentist.copy(scheduledDate = today.minusDays(1))
        show(loaded.copy(tasks = listOf(late)))
        composeRule.onNodeWithText("Yesterday").assertIsDisplayed()
    }

    @Test
    fun `empty, and read-only after a failed reload`() {
        show(TaskListUiState(loading = false, loaded = true, today = today))
        composeRule
            .onNodeWithText("Nothing scheduled. Schedule an inbox item to have it show up in Next on its date.")
            .assertIsDisplayed()
        state = loaded.copy(error = LoadError.OFFLINE)
        composeRule.onNodeWithTag(LiveListTags.STALE).assertIsDisplayed()
        composeRule.onNodeWithTag(TaskListTags.complete(dentist.id)).assertIsNotEnabled()
    }
}
