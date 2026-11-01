package ch.sonensei.cockpit.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/** The search screen alone, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val trip = SearchHit(id(1), "TASK", "Plan the trip", "Next actions", "/tasks/${id(1)}")
    private val budget = SearchHit(id(2), "TASK", "Plan the budget", null, null)
    private val note = SearchHit(id(3), "NOTE", "Plans for autumn", "Notebook", "/notes/${id(3)}")
    private val calls = mutableListOf<String>()

    private val searched = SearchKey("plan", emptySet())
    private val results =
        SearchUiState(
            query = "plan",
            groups = listOf(SearchGroup("TASK", listOf(trip, budget)), SearchGroup("NOTE", listOf(note))),
            shownFor = searched,
        )

    private fun show(state: SearchUiState) =
        composeRule.setContent {
            CockpitTheme {
                SearchContent(
                    state,
                    SearchCallbacks(
                        query = { calls += "query $it" },
                        toggle = { calls += "toggle $it" },
                        submit = { calls += "submit" },
                        retry = { calls += "retry" },
                        open = { calls += "open ${it.title}" },
                    ),
                )
            }
        }

    @Test
    fun `before typing the screen shows a hint`() {
        show(SearchUiState())

        composeRule.onNodeWithTag(SearchTags.HINT).assertIsDisplayed()
        composeRule.onNodeWithText("Search everything").assertIsDisplayed()
        composeRule.onNodeWithTag(SearchTags.LOADING).assertDoesNotExist()
        composeRule.onNodeWithTag(SearchTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(SearchTags.CLEAR).assertDoesNotExist()
    }

    @Test
    fun `a running search shows a loading indicator`() {
        show(SearchUiState(query = "plan", loading = true))

        composeRule.onNodeWithTag(SearchTags.LOADING).assertExists()
        composeRule.onNodeWithTag(SearchTags.HINT).assertDoesNotExist()
        composeRule.onNodeWithTag(SearchTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `an empty answer for the current text says nothing was found`() {
        show(SearchUiState(query = "zzz", shownFor = SearchKey("zzz", emptySet())))

        composeRule.onNodeWithTag(SearchTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("Nothing found").assertIsDisplayed()
    }

    @Test
    fun `nothing found is not said while the answer for the current text is still missing`() {
        show(SearchUiState(query = "zzzq", loading = true, shownFor = SearchKey("zzz", emptySet())))

        composeRule.onNodeWithTag(SearchTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `offline says so and offers a retry`() {
        show(results.copy(message = SearchMessage.OFFLINE))

        composeRule.onNodeWithTag(SearchTags.MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithText("No connection to the server. Search needs the server.").assertIsDisplayed()
        composeRule.onNodeWithTag(SearchTags.RESULTS).assertDoesNotExist()
        composeRule.onNodeWithTag(SearchTags.RETRY).performClick()

        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `a failed search says so and offers a retry`() {
        show(SearchUiState(query = "plan", message = SearchMessage.FAILED))

        composeRule.onNodeWithText("The search failed.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `results show group headings, titles, subtitles and type icons`() {
        show(results)

        composeRule.onNodeWithTag(SearchTags.group("TASK")).assertIsDisplayed()
        composeRule.onNode(hasTestTag(SearchTags.group("TASK")) and hasText("Tasks")).assertExists()
        composeRule.onNodeWithText("Plan the trip", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Next actions", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Plan the budget", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(SearchTags.group("NOTE")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Plans for autumn", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Notebook", useUnmergedTree = true).assertExists()
        // The row's icon names the type for TalkBack.
        composeRule.onNodeWithContentDescription("Notes", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(SearchTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(SearchTags.HINT).assertDoesNotExist()
    }

    @Test
    fun `tapping a result opens it`() {
        show(results)

        composeRule.onNodeWithTag(SearchTags.hit(budget.id)).performClick()
        composeRule.onNodeWithTag(SearchTags.hit(note.id)).performScrollTo().performClick()

        assertEquals(listOf("open Plan the budget", "open Plans for autumn"), calls)
    }

    @Test
    fun `every filter chip is shown and toggles its filter`() {
        show(results.copy(filters = setOf(SearchFilter.Files)))

        composeRule.onNodeWithTag(SearchTags.filter(SearchFilter.Files)).assertIsSelected()
        composeRule.onNodeWithTag(SearchTags.filter(SearchFilter.Tasks)).assertIsNotSelected()
        SearchFilter.entries.forEach { filter ->
            composeRule.onNodeWithTag(SearchTags.filter(filter)).performScrollTo().performClick()
        }

        assertEquals(SearchFilter.entries.map { "toggle $it" }, calls)
    }

    @Test
    fun `typing reports the text`() {
        show(SearchUiState())

        composeRule.onNodeWithTag(SearchTags.FIELD).performTextInput("trip")

        assertEquals(listOf("query trip"), calls)
    }

    @Test
    fun `the keyboard's search action submits`() {
        show(results)

        composeRule.onNodeWithTag(SearchTags.FIELD).performImeAction()

        assertEquals(listOf("submit"), calls)
    }

    @Test
    fun `the clear button empties the text`() {
        show(results)

        composeRule.onNodeWithTag(SearchTags.CLEAR).performClick()

        assertEquals(listOf("query "), calls)
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German the states, chips and headings are German`() {
        show(results.copy(filters = setOf(SearchFilter.Emails)))

        composeRule.onNodeWithText("E-Mails").assertExists()
        composeRule.onNode(hasTestTag(SearchTags.group("TASK")) and hasText("Aufgaben")).assertExists()
        composeRule.onNodeWithText("Termine").assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German nothing found is German`() {
        show(SearchUiState(query = "zzz", shownFor = SearchKey("zzz", emptySet())))
        composeRule.onNodeWithText("Nichts gefunden").assertIsDisplayed()
    }

    @Test
    fun `the field shows the typed text`() {
        show(results)

        composeRule.onNode(hasTestTag(SearchTags.FIELD)).assertIsDisplayed()
        composeRule.onNodeWithText("plan").assertIsDisplayed()
    }
}
