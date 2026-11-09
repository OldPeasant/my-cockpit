package ch.sonensei.cockpit.gtd.projects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.links.LinkedItemsTags
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The project detail's "Linked items" toggle (SPEC.md §4.3). The panel itself is the shared `LinkedItemsSection`, which
 * needs a Hilt activity for its view model; outside one (as here) it composes nothing, so these tests check the toggle,
 * its labels and that it never shows a panel without Hilt. The panel's own behavior is in `LinkedItemsPanelTest`.
 */
@RunWith(AndroidJUnit4::class)
class ProjectLinksToggleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val home = Area(id(90), "Home", 0)
    private val book = GtdTask(id(1), "Book the flights", TaskList.NEXT, 0, 1, projectId = id(10))
    private val seeds = GtdTask(id(2), "Buy seeds", TaskList.NEXT, 0, 1, projectId = id(11))
    private val holiday =
        GtdProject(id(10), "Plan holiday", ProjectStatus.ACTIVE, stalled = false, version = 2, areaId = home.id, nextTaskId = book.id)
    private val garden =
        GtdProject(id(11), "Redo the garden", ProjectStatus.ACTIVE, stalled = false, version = 2, areaId = home.id, nextTaskId = seeds.id)
    private val list =
        ProjectsUiState(
            groups = groupByArea(listOf(holiday, garden), listOf(home)),
            refreshedAt = Instant.parse("2026-10-20T09:00:00Z"),
            today = LocalDate.of(2026, 10, 20),
        )

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(list)

    private fun detail(
        project: GtdProject,
        tasks: List<GtdTask>,
    ) = list.copy(detail = ProjectDetailState(project, home, tasks, loading = false, loaded = true))

    private fun show(initial: ProjectsUiState) {
        state = initial
        val callbacks =
            ProjectsCallbacks(
                open = { calls += "open ${it.title}" },
                close = { calls += "close" },
            )
        composeRule.setContent { CockpitTheme { ProjectsContent(state, callbacks) } }
    }

    @Test
    fun `the project detail offers its linked items, and the toggle switches between show and hide`() {
        show(detail(holiday, listOf(book)))
        composeRule.onNodeWithTag(ProjectsTags.LINKS_TOGGLE).assertIsDisplayed().assertContentDescriptionEquals("Linked items")

        composeRule.onNodeWithTag(ProjectsTags.LINKS_TOGGLE).performClick()
        composeRule.onNodeWithTag(ProjectsTags.LINKS_TOGGLE).assertContentDescriptionEquals("Hide linked items")
        // The tasks stay in view below the panel.
        composeRule.onNodeWithText("Next step: Book the flights").assertIsDisplayed()

        composeRule.onNodeWithTag(ProjectsTags.LINKS_TOGGLE).performClick()
        composeRule.onNodeWithTag(ProjectsTags.LINKS_TOGGLE).assertContentDescriptionEquals("Linked items")
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `without a Hilt activity no linked-items panel is shown, even when toggled on`() {
        show(detail(holiday, listOf(book)))
        composeRule.onNodeWithTag(LinkedItemsTags.PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectsTags.LINKS_TOGGLE).performClick()
        composeRule.onNodeWithTag(LinkedItemsTags.PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(ProjectsTags.DETAIL).assertIsDisplayed()
    }

    @Test
    fun `another project opens with its linked items hidden`() {
        show(detail(holiday, listOf(book)))
        composeRule.onNodeWithTag(ProjectsTags.LINKS_TOGGLE).performClick()
        composeRule.onNodeWithTag(ProjectsTags.LINKS_TOGGLE).assertContentDescriptionEquals("Hide linked items")

        state = detail(garden, listOf(seeds))
        composeRule.onNodeWithText("Next step: Buy seeds").assertIsDisplayed()
        composeRule.onNodeWithTag(ProjectsTags.LINKS_TOGGLE).assertContentDescriptionEquals("Linked items")
    }

    @Test
    fun `the project list has no linked-items toggle`() {
        show(list)
        composeRule.onNodeWithTag(ProjectsTags.LINKS_TOGGLE).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German, the toggle is German`() {
        show(detail(holiday, listOf(book)))
        composeRule.onNodeWithTag(ProjectsTags.LINKS_TOGGLE).assertContentDescriptionEquals("Verknüpfte Elemente")
        composeRule.onNodeWithTag(ProjectsTags.LINKS_TOGGLE).performClick()
        composeRule.onNodeWithTag(ProjectsTags.LINKS_TOGGLE).assertContentDescriptionEquals("Verknüpfte Elemente ausblenden")
    }
}
