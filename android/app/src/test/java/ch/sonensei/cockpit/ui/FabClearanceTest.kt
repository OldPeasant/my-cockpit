package ch.sonensei.cockpit.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.capture.entry.CaptureButtonTags
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.lists.ActionOutcome
import ch.sonensei.cockpit.gtd.lists.ActionSnackbar
import ch.sonensei.cockpit.gtd.lists.LiveListCallbacks
import ch.sonensei.cockpit.gtd.lists.LiveListTags
import ch.sonensei.cockpit.gtd.lists.SomedayContent
import ch.sonensei.cockpit.gtd.lists.TaskListUiState
import ch.sonensei.cockpit.gtd.next.Completion
import ch.sonensei.cockpit.gtd.next.CompletionSnackbar
import ch.sonensei.cockpit.gtd.next.NextCallbacks
import ch.sonensei.cockpit.gtd.next.NextContent
import ch.sonensei.cockpit.gtd.next.NextTags
import ch.sonensei.cockpit.gtd.next.NextUiState
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.gtd.projects.ProjectDetailState
import ch.sonensei.cockpit.gtd.projects.ProjectsCallbacks
import ch.sonensei.cockpit.gtd.projects.ProjectsContent
import ch.sonensei.cockpit.gtd.projects.ProjectsTags
import ch.sonensei.cockpit.gtd.projects.ProjectsUiState
import ch.sonensei.cockpit.gtd.projects.groupByArea
import ch.sonensei.cockpit.gtd.today.TodayCallbacks
import ch.sonensei.cockpit.gtd.today.TodayContent
import ch.sonensei.cockpit.gtd.today.TodaySection
import ch.sonensei.cockpit.gtd.today.TodaySectionKind
import ch.sonensei.cockpit.gtd.today.TodayTags
import ch.sonensei.cockpit.gtd.today.TodayUiState
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FabClearanceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setShell(content: @Composable (CockpitDestination, Modifier) -> Unit) {
        composeRule.setContent {
            navController = rememberNavController()
            CockpitTheme { CockpitShellFrame(navController, prompts = {}) { destination, _, modifier -> content(destination, modifier) } }
        }
    }

    private fun open(destination: CockpitDestination) {
        composeRule.runOnUiThread { navController.navigate(destination.route) }
        composeRule.waitForIdle()
    }

    @Test
    fun `screens under the shell FAB get its clearance and screens with their own FAB get none`() {
        val seen = mutableMapOf<CockpitDestination, Dp>()
        setShell { destination, modifier ->
            seen[destination] = LocalFabClearance.current
            Box(modifier.fillMaxSize())
        }
        CockpitDestination.entries.forEach { open(it) }

        CockpitDestination.entries.forEach { destination ->
            val expected = if (destination in OWN_FAB) 0.dp else ShellFabClearance
            assertEquals("clearance on $destination", expected, seen[destination])
        }
        assertEquals(0.dp, seen[CockpitDestination.Notes])
        assertTrue(seen.getValue(CockpitDestination.Today) > 0.dp)
    }

    @Test
    fun `a screen's own snackbar padded by the clearance sits above the shell FAB`() {
        setShell { _, modifier ->
            val padded = remember { SnackbarHostState() }
            val unpadded = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { padded.showSnackbar("Task completed", "Undo", duration = SnackbarDuration.Indefinite) }
            LaunchedEffect(Unit) { unpadded.showSnackbar("Task completed", "Undo", duration = SnackbarDuration.Indefinite) }
            Box(modifier.fillMaxSize()) {
                // As in the GTD screens (TodayScreen, NextScreen, ListUi, ProjectsScreen).
                SnackbarHost(padded, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalFabClearance.current).testTag(PADDED))
                SnackbarHost(unpadded, Modifier.align(Alignment.BottomCenter).testTag(UNPADDED))
            }
        }
        composeRule.waitForIdle()

        val fab = composeRule.onNodeWithTag(CaptureButtonTags.FAB).getBoundsInRoot()
        val snackbar = composeRule.onNodeWithTag(PADDED).getBoundsInRoot()
        val control = composeRule.onNodeWithTag(UNPADDED).getBoundsInRoot()

        assertTrue("the snackbar is shown", snackbar.bottom > snackbar.top)
        // Without the clearance the FAB covers the snackbar's end (its Undo action): the test would catch a regression.
        assertTrue("control overlaps the FAB: $control vs $fab", control.overlaps(fab))
        assertTrue("snackbar $snackbar must not overlap FAB $fab", !snackbar.overlaps(fab))
        assertTrue("snackbar $snackbar ends above FAB $fab", snackbar.bottom <= fab.top)
    }

    @Test
    fun `on a real GTD list in the shell, neither the Undo action nor the last row's action lies under the FAB`() {
        val tasks = (1..30).map { GtdTask(id(it), "Someday $it", TaskList.SOMEDAY, it, 1) }
        val completed = CompletionSnackbar.Undo(Completion(id(99), "Done task", 2))
        val state =
            TaskListUiState(tasks = tasks, loading = false, loaded = true, completion = completed, today = LocalDate.of(2026, 11, 1))
        setShell { destination, modifier ->
            if (destination == CockpitDestination.start) SomedayContent(state, LiveListCallbacks(), activate = {}, modifier = modifier)
        }
        composeRule.waitForIdle()
        val fab = composeRule.onNodeWithTag(CaptureButtonTags.FAB).getBoundsInRoot()

        val undo = composeRule.onNodeWithText(context.getString(R.string.tasks_undo)).getBoundsInRoot()
        assertTrue("Undo $undo must not lie under FAB $fab", !undo.overlaps(fab))

        composeRule.onNodeWithTag(LiveListTags.LIST).performScrollToIndex(tasks.lastIndex)
        composeRule.waitForIdle()
        val lastAction = composeRule.onNodeWithTag(LiveListTags.action(tasks.last().id)).getBoundsInRoot()
        assertTrue("last row's action $lastAction must not lie under FAB $fab", !lastAction.overlaps(fab))
    }

    @Test
    fun `on Today in the shell, the Undo action and the last row lie above the FAB`() {
        val today = LocalDate.of(2026, 11, 1)
        val tasks = (1..30).map { GtdTask(id(it), "Due $it", TaskList.NEXT, it, 1, dueDate = today) }
        val state =
            TodayUiState(
                sections = listOf(TodaySection(TodaySectionKind.DUE, tasks)),
                refreshedAt = REFRESHED,
                snackbar = CompletionSnackbar.Undo(Completion(id(99), "Done task", 2)),
                today = today,
            )
        setShell { destination, modifier ->
            if (destination == CockpitDestination.start) TodayContent(state, TodayCallbacks(), modifier)
        }

        assertTextAboveFab(context.getString(R.string.tasks_undo))
        assertLastRowAboveFab(TodayTags.LIST, lastIndex = tasks.size, TaskListTags.row(tasks.last().id))
    }

    @Test
    fun `on Next in the shell, the Undo action and the last row lie above the FAB`() {
        val tasks = (1..30).map { GtdTask(id(it), "Next $it", TaskList.NEXT, it, 1) }
        val state =
            NextUiState(
                tasks = tasks,
                total = tasks.size,
                refreshedAt = REFRESHED,
                snackbar = CompletionSnackbar.Undo(Completion(id(99), "Done task", 2)),
                today = LocalDate.of(2026, 11, 1),
            )
        setShell { destination, modifier ->
            if (destination == CockpitDestination.start) NextContent(state, NextCallbacks(), modifier)
        }

        assertTextAboveFab(context.getString(R.string.tasks_undo))
        assertLastRowAboveFab(NextTags.LIST, lastIndex = tasks.lastIndex, TaskListTags.row(tasks.last().id))
    }

    @Test
    fun `on Projects in the shell, the snackbar and the last project lie above the FAB`() {
        val projects = (1..30).map { GtdProject(id(it), "Project $it", ProjectStatus.ACTIVE, stalled = false, version = 1) }
        val state =
            ProjectsUiState(
                groups = groupByArea(projects, emptyList()),
                refreshedAt = REFRESHED,
                message = ActionSnackbar(ActionSnackbar.Kind.NEXT_STEP, "Fictitious step", ActionOutcome.CONFLICT),
                today = LocalDate.of(2026, 11, 1),
            )
        setShell { destination, modifier ->
            if (destination == CockpitDestination.start) ProjectsContent(state, ProjectsCallbacks(), modifier)
        }

        assertTextAboveFab(context.getString(R.string.tasks_message_conflict))
        // One area header ("No area") precedes the projects, which the screen sorts by title.
        val sorted = state.groups[0].projects
        assertEquals(1, state.groups.size)
        assertLastRowAboveFab(ProjectsTags.LIST, lastIndex = projects.size, ProjectsTags.project(sorted.last().id))
    }

    @Test
    fun `on a project's detail in the shell, the last task's make-next-step button lies above the FAB`() {
        val project = GtdProject(id(500), "Fictitious move", ProjectStatus.ACTIVE, stalled = true, version = 1)
        val tasks = (1..30).map { GtdTask(id(it), "Step $it", TaskList.NEXT, it, 1, projectId = project.id) }
        val state =
            ProjectsUiState(
                groups = groupByArea(listOf(project), emptyList()),
                refreshedAt = REFRESHED,
                detail = ProjectDetailState(project, tasks = tasks, loading = false, loaded = true),
                today = LocalDate.of(2026, 11, 1),
            )
        setShell { destination, modifier ->
            if (destination == CockpitDestination.start) ProjectsContent(state, ProjectsCallbacks(), modifier)
        }

        // The trailing button sits at the row's end, right where the FAB is.
        assertLastRowAboveFab(LiveListTags.LIST, lastIndex = tasks.lastIndex, ProjectsTags.makeNext(tasks.last().id))
    }

    @Test
    fun `outside the shell a screen gets no FAB clearance`() {
        var clearance: Dp? = null
        composeRule.setContent { CockpitTheme { clearance = LocalFabClearance.current } }
        composeRule.waitForIdle()

        assertEquals(0.dp, clearance)
    }

    /** Waits for the snackbar [text] and checks it ends above the FAB's top edge (it would end below it unpadded). */
    private fun assertTextAboveFab(text: String) {
        var bounds: Rect? = null
        composeRule.waitUntil(TIMEOUT) {
            val shown = composeRule.onAllNodesWithText(text).fetchSemanticsNodes()
            bounds = shown.firstOrNull()?.boundsInRoot
            bounds != null
        }
        val fab = fabBounds()
        assertTrue("'$text' $bounds must end above FAB $fab", bounds!!.bottom <= fab.top + EPSILON_PX)
    }

    /** Scrolls [list] to its end and checks the whole [rowTag] node (the last row or its trailing control) ends above the FAB. */
    private fun assertLastRowAboveFab(
        list: String,
        lastIndex: Int,
        rowTag: String,
    ) {
        composeRule.onNodeWithTag(list).performScrollToIndex(lastIndex)
        composeRule.waitForIdle()
        val row = composeRule.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot
        val fab = fabBounds()
        assertTrue("last row $row must end above FAB $fab", row.bottom <= fab.top + EPSILON_PX)
    }

    private fun fabBounds(): Rect = composeRule.onNodeWithTag(CaptureButtonTags.FAB).fetchSemanticsNode().boundsInRoot

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun DpRect.overlaps(other: DpRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    private companion object {
        const val PADDED = "test:snackbar-padded"
        const val UNPADDED = "test:snackbar-unpadded"
        const val TIMEOUT = 5_000L
        const val EPSILON_PX = 1f
        val REFRESHED: Instant = Instant.parse("2026-11-01T08:00:00Z")
        val OWN_FAB =
            setOf(
                CockpitDestination.Notes,
                CockpitDestination.Files,
                CockpitDestination.Mail,
                CockpitDestination.Calendar,
                CockpitDestination.Contacts,
            )
    }
}
