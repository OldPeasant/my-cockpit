package ch.sonensei.cockpit.gtd.lists

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import org.robolectric.annotation.Config
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

    @Test
    fun `tasks on the same date share one header, which is a heading with the full date`() {
        show(loaded)
        val later = today.plusDays(10)
        composeRule.onAllNodesWithTag(ScheduledTags.day(later)).assertCountEquals(1)
        composeRule.onNodeWithTag(ScheduledTags.day(later)).assertTextEquals("Friday, October 30, 2026")
        composeRule.onNodeWithTag(ScheduledTags.day(later)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithTag(TaskListTags.row(tyres.id)).assertIsDisplayed()
        composeRule.onNodeWithTag(TaskListTags.row(plants.id)).assertIsDisplayed()
        composeRule.onAllNodesWithTag(ScheduledTags.NO_DATE).assertCountEquals(1)
    }

    @Test
    fun `rows have no extra action button`() {
        show(loaded)
        listOf(dentist, tax, tyres, plants, loose).forEach { task ->
            composeRule.onNodeWithTag(LiveListTags.action(task.id)).assertDoesNotExist()
        }
    }

    @Test
    fun `a busy task cannot be completed again`() {
        show(loaded.copy(busy = setOf(tax.id)))
        composeRule.onNodeWithTag(TaskListTags.complete(tax.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(TaskListTags.complete(dentist.id)).assertIsEnabled()
    }

    @Test
    fun `loading, then a first load error with retry`() {
        show(TaskListUiState(today = today))
        composeRule.onNodeWithTag(LiveListTags.LOADING).assertIsDisplayed()
        state = TaskListUiState(loading = false, error = LoadError.OFFLINE, today = today)
        composeRule.onNodeWithTag(LiveListTags.ERROR).assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    @Config(qualifiers = "de")
    fun `german day headers and empty state`() {
        show(loaded.copy(tasks = listOf(late(), dentist, tax, loose)))
        composeRule.onNodeWithText("Gestern").assertIsDisplayed()
        composeRule.onNodeWithText("Heute").assertIsDisplayed()
        composeRule.onNodeWithText("Morgen").assertIsDisplayed()
        composeRule.onNodeWithText("Kein Datum").assertIsDisplayed()
        state = TaskListUiState(loading = false, loaded = true, today = today)
        composeRule
            .onNodeWithText("Nichts geplant. Plane einen Eingangseintrag ein, damit er an diesem Datum unter „Nächste“ erscheint.")
            .assertIsDisplayed()
    }

    private fun late() = GtdTask(id(6), "Return parcel", TaskList.SCHEDULED, 5, 1, scheduledDate = today.minusDays(1))
}
