package ch.sonensei.cockpit.core.links

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.search.SearchGroup
import ch.sonensei.cockpit.search.SearchHit
import ch.sonensei.cockpit.search.SearchResult
import ch.sonensei.cockpit.search.SearchTarget
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import kotlinx.coroutines.flow.flowOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.UUID

/** The linked-items panel alone (states the tests control), with a real view model, and where a tapped link leads. */
@RunWith(AndroidJUnit4::class)
class LinkedItemsPanelTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val task = ItemRef("TASK", id(1))
    private val server = "https://cockpit.example.org/".toHttpUrl()

    private val packing = LinkedItem(id(11), "NOTE", id(3), "Packing list", "Travel", "/notes/${id(3)}", available = true, manual = true)
    private val flight = LinkedItem(id(12), "EMAIL", id(4), "Flight booking", null, "/mail/${id(4)}", available = true, manual = false)
    private val gone = LinkedItem(id(13), "TASK", id(5), null, null, null, available = false, manual = true)
    private val hit = SearchHit(id(6), "TASK", "Book the hotel", "Next actions", "/tasks/${id(6)}")

    private val calls = mutableListOf<String>()

    private val callbacks =
        LinkedItemsCallbacks(
            open = { calls += "open ${it.title}" },
            remove = { calls += "remove ${it.title}" },
            retry = { calls += "retry" },
            openSearch = { calls += "openSearch" },
            search = { calls += "search $it" },
            add = { calls += "add ${it.title}" },
            closeSearch = { calls += "closeSearch" },
            startCreate = { calls += "startCreate $it" },
            create = { calls += "create $it" },
            cancelCreate = { calls += "cancelCreate" },
        )

    private fun show(state: LinkedItemsUiState) = composeRule.setContent { CockpitTheme { LinkedItemsPanel(state, callbacks) } }

    private fun loaded(vararg links: LinkedItem) = LinkedItemsUiState(item = task, links = links.toList(), server = server)

    // --- list ---

    @Test
    fun `lists the links with type, subtitle and an untitled or unavailable mark`() {
        show(loaded(packing, flight, gone))

        composeRule.onNodeWithText("Linked items").assertExists()
        composeRule.onNodeWithTag(LinkedItemsTags.link(packing)).assertTextContains("Packing list")
        composeRule.onNodeWithTag(LinkedItemsTags.link(packing)).assertTextContains("Notes · Travel")
        composeRule.onNodeWithTag(LinkedItemsTags.link(flight)).assertTextContains("Flight booking")
        composeRule.onNodeWithTag(LinkedItemsTags.link(gone)).assertTextContains("Untitled")
        composeRule.onNodeWithTag(LinkedItemsTags.link(gone)).assertTextContains("Tasks · unavailable")
        composeRule.onNodeWithTag(LinkedItemsTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `tapping an available link opens it, an unavailable one does nothing`() {
        show(loaded(packing, gone))

        composeRule.onNodeWithTag(LinkedItemsTags.link(packing)).performClick()
        composeRule.onNodeWithTag(LinkedItemsTags.link(gone)).assertIsNotEnabled()
        composeRule.onNodeWithTag(LinkedItemsTags.link(gone)).performClick()

        assertEquals(listOf("open Packing list"), calls)
    }

    @Test
    fun `manual links can be removed, sync links cannot`() {
        show(loaded(packing, flight))

        composeRule.onNodeWithContentDescription("Remove link to Packing list").performClick()
        composeRule.onNodeWithTag(LinkedItemsTags.remove(flight)).assertDoesNotExist()

        assertEquals(listOf("remove Packing list"), calls)
    }

    @Test
    fun `a link whose removal runs cannot be removed again`() {
        show(loaded(packing).copy(removing = setOf(packing.linkId)))

        composeRule.onNodeWithTag(LinkedItemsTags.remove(packing)).assertIsNotEnabled()
    }

    @Test
    fun `no links says so`() {
        show(loaded())
        composeRule.onNodeWithTag(LinkedItemsTags.EMPTY).assertTextContains("No linked items yet.")
    }

    @Test
    fun `while loading there is no empty text`() {
        show(LinkedItemsUiState(item = task))
        composeRule.onNodeWithTag(LinkedItemsTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(LinkedItemsTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun `a failed load says why and retries`() {
        show(LinkedItemsUiState(item = task, loadError = LinksError.Offline))

        composeRule.onNodeWithText("Linked items need a connection to the server.").assertExists()
        composeRule.onNodeWithText("Retry").performClick()

        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `a load that was refused says it could not be loaded`() {
        show(LinkedItemsUiState(item = task, loadError = LinksError.Failed))
        composeRule.onNodeWithText("Linked items could not be loaded.").assertExists()
    }

    @Test
    fun `a failed action is shown below the links`() {
        show(loaded(packing).copy(actionError = LinksError.Offline))
        composeRule.onNodeWithTag(LinkedItemsTags.ERROR).assertExists()
        composeRule.onNodeWithText("No connection to the server.").assertExists()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    // --- actions ---

    @Test
    fun `the actions open the search and the create dialogs`() {
        show(loaded())

        composeRule.onNodeWithTag(LinkedItemsTags.ADD).performClick()
        composeRule.onNodeWithTag(LinkedItemsTags.NEW_TASK).performClick()
        composeRule.onNodeWithTag(LinkedItemsTags.NEW_NOTE).performClick()

        assertEquals(listOf("openSearch", "startCreate Task", "startCreate Note"), calls)
    }

    @Test
    fun `the actions are disabled while busy`() {
        show(loaded().copy(busy = true))
        composeRule.onNodeWithTag(LinkedItemsTags.ADD).assertIsNotEnabled()
        composeRule.onNodeWithTag(LinkedItemsTags.NEW_TASK).assertIsNotEnabled()
        composeRule.onNodeWithTag(LinkedItemsTags.NEW_NOTE).assertIsNotEnabled()
    }

    @Test
    fun `without an item the actions are disabled`() {
        show(LinkedItemsUiState())
        composeRule.onNodeWithTag(LinkedItemsTags.ADD).assertIsNotEnabled()
    }

    // --- search dialog ---

    @Test
    fun `the search dialog searches as you type and adds a hit`() {
        show(
            loaded().copy(
                adding = true,
                query = "book",
                resultsQuery = "book",
                results = listOf(SearchGroup("TASK", listOf(hit))),
            ),
        )

        composeRule.onNodeWithTag(LinkedItemsTags.SEARCH_FIELD).performTextInput("s")
        composeRule.onNodeWithText("Tasks").assertExists()
        composeRule.onNodeWithTag(LinkedItemsTags.hit(hit)).assertTextContains("Book the hotel")
        composeRule.onNodeWithTag(LinkedItemsTags.hit(hit)).assertTextContains("Next actions")
        composeRule.onNodeWithTag(LinkedItemsTags.hit(hit)).performClick()

        // The field's cursor starts at the front, as in the other screen tests.
        assertEquals(listOf("search sbook", "add Book the hotel"), calls)
    }

    @Test
    fun `hits cannot be added while a link is being created`() {
        val results = listOf(SearchGroup("TASK", listOf(hit)))
        show(loaded().copy(adding = true, query = "book", resultsQuery = "book", results = results, busy = true))

        composeRule.onNodeWithTag(LinkedItemsTags.hit(hit)).assertIsNotEnabled()
    }

    @Test
    fun `a failed add is shown in the search dialog`() {
        show(loaded().copy(adding = true, query = "book", resultsQuery = "book", actionError = LinksError.Failed))

        // In the dialog and below the links.
        composeRule.onAllNodesWithText("That did not work. Please try again.").assertCountEquals(2)
    }

    @Test
    fun `nothing found is said only once the search for the text answered`() {
        show(loaded().copy(adding = true, query = "zzz", resultsQuery = "zzz"))
        composeRule.onNodeWithTag(LinkedItemsTags.NO_RESULTS).assertTextContains("Nothing found.")
    }

    @Test
    fun `nothing found is not said while the search runs`() {
        show(loaded().copy(adding = true, query = "zzzq", resultsQuery = "zzz"))
        composeRule.onNodeWithTag(LinkedItemsTags.NO_RESULTS).assertDoesNotExist()
    }

    @Test
    fun `a failed search says so instead of nothing found, and close closes the search`() {
        show(loaded().copy(adding = true, query = "zzz", resultsQuery = "zzz", searchError = LinksError.Failed))

        composeRule.onNodeWithText("That did not work. Please try again.").assertExists()
        composeRule.onNodeWithTag(LinkedItemsTags.NO_RESULTS).assertDoesNotExist()
        composeRule.onNodeWithText("Close").performClick()

        assertEquals(listOf("closeSearch"), calls)
    }

    // --- create dialog ---

    @Test
    fun `the create dialog asks for a title and creates with it`() {
        show(loaded().copy(creating = LinkedCreateKind.Task))

        // The panel's button and the dialog title.
        composeRule.onAllNodesWithText("New linked task").assertCountEquals(2)
        composeRule.onNodeWithTag(LinkedItemsTags.CREATE_CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithTag(LinkedItemsTags.CREATE_FIELD).performTextInput("Call Anna")
        composeRule.onNodeWithTag(LinkedItemsTags.CREATE_CONFIRM).assertIsEnabled()
        composeRule.onNodeWithTag(LinkedItemsTags.CREATE_CONFIRM).performClick()

        assertEquals(listOf("create Call Anna"), calls)
    }

    @Test
    fun `the note dialog can be cancelled`() {
        show(loaded().copy(creating = LinkedCreateKind.Note))

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(listOf("cancelCreate"), calls)
    }

    @Test
    fun `while creating the dialog cannot confirm and shows a failure`() {
        show(loaded().copy(creating = LinkedCreateKind.Note, busy = true, actionError = LinksError.Offline))

        composeRule.onNodeWithTag(LinkedItemsTags.CREATE_FIELD).performTextInput("Meeting notes")
        composeRule.onNodeWithTag(LinkedItemsTags.CREATE_CONFIRM).assertIsNotEnabled()
        // In the dialog and below the links.
        composeRule.onAllNodesWithText("No connection to the server.").assertCountEquals(2)
    }

    @Test
    @Config(qualifiers = "de")
    fun `the panel speaks German`() {
        show(loaded().copy(creating = LinkedCreateKind.Task))

        composeRule.onNodeWithText("Verknüpfte Elemente").assertExists()
        composeRule.onAllNodesWithText("Neue verknüpfte Aufgabe").assertCountEquals(2)
        composeRule.onNodeWithText("Verknüpfen").assertExists()
        composeRule.onNodeWithText("Abbrechen").assertExists()
    }

    // --- where a link leads ---

    @Test
    fun `a task opens in the app, a note in the app, an event on the web`() {
        assertEquals(SearchTarget.InApp("TASK", id(5)), linkedItemTarget(gone, null))
        assertEquals(SearchTarget.InApp("NOTE", id(3)), linkedItemTarget(packing, server))
        val event = packing.copy(type = "EVENT", itemId = id(7), deepLink = "/calendar/events/${id(7)}")
        assertEquals(SearchTarget.Web("https://cockpit.example.org/calendar/events/${id(7)}"), linkedItemTarget(event, server))
    }

    @Test
    fun `an item without a web page or server URL falls back to its Android list screen`() {
        val event = packing.copy(type = "EVENT", itemId = id(7), deepLink = "/calendar/events/${id(7)}")
        assertTrue(linkedItemTarget(event, null) is SearchTarget.Screen)
        assertTrue(linkedItemTarget(event.copy(deepLink = null), server) is SearchTarget.Screen)
    }

    // --- with a view model ---

    @Test
    fun `the section loads the item's links, opens in-app targets and sends web targets to the browser`() {
        val event = LinkedItem(id(14), "EVENT", id(7), "Team lunch", null, "/calendar/events/${id(7)}", available = true, manual = true)
        val source = FixedLinks(listOf(packing, event))
        val vm = LinkedItemsViewModel(source, flowOf(server))
        val opened = mutableListOf<SearchTarget>()
        composeRule.setContent { CockpitTheme { LinkedItemsSection(task, { opened += it }, viewModel = vm) } }
        composeRule.waitForIdle()

        assertEquals(listOf(task), source.listed)
        composeRule.onNodeWithTag(LinkedItemsTags.link(packing)).performClick()
        assertEquals(listOf<SearchTarget>(SearchTarget.InApp("NOTE", id(3))), opened)

        composeRule.onNodeWithTag(LinkedItemsTags.link(event)).performClick()
        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://cockpit.example.org/calendar/events/${id(7)}", started.dataString)
        assertEquals("the browser, not the app's own navigation", 1, opened.size)
    }

    @Test
    fun `without a browser a web link says it cannot be opened`() {
        val event = LinkedItem(id(14), "EVENT", id(7), "Team lunch", null, "/calendar/events/${id(7)}", available = true, manual = true)
        val vm = LinkedItemsViewModel(FixedLinks(listOf(event)), flowOf(server))
        val opened = mutableListOf<SearchTarget>()
        shadowOf(composeRule.activity.application).checkActivities(true)
        composeRule.setContent { CockpitTheme { LinkedItemsSection(task, { opened += it }, viewModel = vm) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(LinkedItemsTags.link(event)).performClick()

        assertEquals("No app can open this link.", ShadowToast.getTextOfLatestToast())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `the section removes a manual link through the view model`() {
        val source = FixedLinks(listOf(packing))
        val vm = LinkedItemsViewModel(source, flowOf(server))
        composeRule.setContent { CockpitTheme { LinkedItemsSection(task, {}, viewModel = vm) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(LinkedItemsTags.remove(packing)).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(packing.linkId), source.removed)
        composeRule.onNodeWithTag(LinkedItemsTags.EMPTY).assertExists()
    }

    @Test
    fun `without Hilt and without a view model the section shows nothing`() {
        composeRule.setContent { CockpitTheme { LinkedItemsSection(task, {}) } }
        composeRule.onNodeWithTag(LinkedItemsTags.PANEL).assertDoesNotExist()
    }

    /** Answers at once with fixed links; records what was asked. */
    private class FixedLinks(
        private val links: List<LinkedItem>,
    ) : LinksSource {
        val listed = mutableListOf<ItemRef>()
        val removed = mutableListOf<UUID>()

        override suspend fun list(item: ItemRef): LinksResult<List<LinkedItem>> {
            listed += item
            return LinksResult.Ok(links)
        }

        override suspend fun link(
            source: ItemRef,
            target: ItemRef,
        ): LinksResult<LinkedItem> = LinksResult.Failed(400)

        override suspend fun remove(linkId: UUID): LinksResult<Unit> {
            removed += linkId
            return LinksResult.Ok(Unit)
        }

        override suspend fun search(query: String): SearchResult = SearchResult.Ok(emptyList())

        override suspend fun createTask(title: String): LinksResult<UUID> = LinksResult.Failed(400)

        override suspend fun createNote(title: String): LinksResult<UUID> = LinksResult.Failed(400)
    }
}
