package ch.sonensei.cockpit.bookmarks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/** The Bookmarks screen alone, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class BookmarksScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val article =
        Bookmark(
            id(1),
            "https://www.example.org/article",
            "Slow reading",
            "Slow reading",
            "A long read about attention",
            "https://example.org/favicon.ico",
            listOf("reading", "focus"),
            true,
            null,
            Instant.EPOCH,
            1,
        )
    private val read =
        article.copy(
            id = id(2),
            url = "https://blog.example.net/x",
            title = null,
            displayTitle = "blog.example.net",
            description = null,
            tags = emptyList(),
            readAt = Instant.EPOCH,
        )
    private val calls = mutableListOf<String>()

    private val callbacks =
        BookmarksCallbacks(
            open = { calls += "open ${it.id}" },
            selectView = { calls += "view $it" },
            selectTag = { calls += "tag $it" },
            search = { calls += "search $it" },
            refresh = { calls += "refresh" },
            retry = { calls += "retry" },
            toggleRead = { calls += "read ${it.id}" },
            edit = { calls += "edit ${it.id}" },
            delete = { calls += "delete ${it.id}" },
            save = { calls += "save $it" },
            cancelEdit = { calls += "cancel" },
            undo = { calls += "undo ${it.bookmark.id}" },
            dismiss = { calls += "dismiss" },
        )

    private fun show(state: BookmarksUiState) =
        composeRule.setContent {
            CockpitTheme { BookmarksContent(state, callbacks) }
        }

    @Test
    fun `rows show title, site, notes, tags and the site's initial, and open on tap`() {
        show(BookmarksUiState(bookmarks = listOf(article), tags = listOf(BookmarkTag("reading", 1)), loaded = true))

        val row = composeRule.onNodeWithTag(BookmarksTags.bookmark(article.id))
        row.assertTextContains("Slow reading", substring = true)
        row.assertTextContains("example.org", substring = true)
        row.assertTextContains("A long read about attention", substring = true)
        row.assertTextContains("#reading  #focus", substring = true)
        composeRule.onNodeWithText("E", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(BookmarksTags.view(BookmarkView.QUEUE)).assertIsSelected()

        row.performClick()
        composeRule.onNodeWithTag(BookmarksTags.tag("reading")).performClick()
        composeRule.onNodeWithTag(BookmarksTags.ALL_TAGS).performClick()
        composeRule.onNodeWithTag(BookmarksTags.view(BookmarkView.ALL)).performClick()
        assertEquals(listOf("open ${article.id}", "tag reading", "tag null", "view ALL"), calls)
    }

    @Test
    fun `in all bookmarks a read one says so, and its menu offers to mark it unread`() {
        show(BookmarksUiState(bookmarks = listOf(read), view = BookmarkView.ALL, loaded = true))

        composeRule.onNodeWithTag(BookmarksTags.bookmark(read.id)).assertTextContains("blog.example.net · Read", substring = true)
        composeRule.onNodeWithContentDescription("Actions for blog.example.net").performClick()
        composeRule.onNodeWithText("Mark as unread").performClick()
        assertEquals(listOf("read ${read.id}"), calls)
    }

    @Test
    fun `the menu marks read, edits and deletes`() {
        show(BookmarksUiState(bookmarks = listOf(article), loaded = true))

        composeRule.onNodeWithTag(BookmarksTags.menu(article.id)).performClick()
        composeRule.onNodeWithText("Mark as read").performClick()
        composeRule.onNodeWithTag(BookmarksTags.menu(article.id)).performClick()
        composeRule.onNodeWithTag(BookmarksTags.ACTION_EDIT).performClick()
        composeRule.onNodeWithTag(BookmarksTags.menu(article.id)).performClick()
        composeRule.onNodeWithTag(BookmarksTags.ACTION_DELETE).performClick()
        assertEquals(listOf("read ${article.id}", "edit ${article.id}", "delete ${article.id}"), calls)
    }

    @Test
    fun `a busy bookmark's menu is disabled`() {
        show(BookmarksUiState(bookmarks = listOf(article), loaded = true, busy = setOf(article.id)))
        composeRule.onNodeWithTag(BookmarksTags.menu(article.id)).performClick()
        composeRule.onNodeWithTag(BookmarksTags.ACTION_READ).assertDoesNotExist()
    }

    @Test
    fun `the search field reports what is typed and can be cleared`() {
        var state by mutableStateOf(BookmarksUiState(bookmarks = listOf(article), loaded = true))
        composeRule.setContent {
            CockpitTheme {
                BookmarksContent(
                    state,
                    callbacks.copy(search = {
                        calls += "search $it"
                        state = state.copy(query = it)
                    }),
                )
            }
        }
        composeRule.onNodeWithTag(BookmarksTags.SEARCH).performTextInput("zzz")
        composeRule.onNodeWithTag(BookmarksTags.EMPTY).assertTextContains("No bookmarks match “zzz”.")
        composeRule.onNodeWithTag(BookmarksTags.SEARCH_CLEAR).performClick()
        composeRule.onNodeWithTag(BookmarksTags.bookmark(article.id)).assertExists()
        assertEquals(listOf("search zzz", "search "), calls)
    }

    @Test
    fun `empty states follow the view and tag`() {
        var state by mutableStateOf(BookmarksUiState(loaded = true))
        composeRule.setContent { CockpitTheme { BookmarksContent(state, callbacks) } }
        composeRule.onNodeWithTag(BookmarksTags.EMPTY).assertTextContains("Nothing to read later", substring = true)
        state = state.copy(view = BookmarkView.ALL)
        composeRule.onNodeWithTag(BookmarksTags.EMPTY).assertTextContains("No bookmarks yet", substring = true)
        state = state.copy(tag = "rust")
        composeRule.onNodeWithTag(BookmarksTags.EMPTY).assertTextContains("No bookmarks tagged rust.")
        // Nothing loaded yet: no empty text, the progress bar instead.
        state = BookmarksUiState(loading = true)
        composeRule.onNodeWithTag(BookmarksTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(BookmarksTags.LOADING).assertExists()
    }

    @Test
    fun `a load problem is shown with a retry`() {
        show(BookmarksUiState(bookmarks = listOf(article), loaded = true, message = BookmarksMessage.OFFLINE))
        composeRule.onNodeWithText("Can't reach the server", substring = true).assertExists()
        composeRule.onNodeWithTag(BookmarksTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `offline with nothing loaded for the view does not claim to show the last bookmarks`() {
        var state by mutableStateOf(BookmarksUiState(view = BookmarkView.ALL, loaded = false, message = BookmarksMessage.OFFLINE))
        composeRule.setContent { CockpitTheme { BookmarksContent(state, callbacks) } }
        composeRule.onNodeWithText("Can't reach the server.").assertExists()
        composeRule.onNodeWithText("Showing the last loaded bookmarks", substring = true).assertDoesNotExist()

        state = state.copy(bookmarks = listOf(article), loaded = true)
        composeRule.onNodeWithText("Can't reach the server. Showing the last loaded bookmarks.").assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun `offline with nothing loaded is translated to German`() {
        show(BookmarksUiState(loaded = false, message = BookmarksMessage.OFFLINE))
        composeRule.onNodeWithText("Der Server ist nicht erreichbar.").assertExists()
    }

    @Test
    fun `the editor opened again after a failed save shows the unsaved draft`() {
        val draft = BookmarkDraft("Mine", "deep", "Notes typed before the save failed")
        show(BookmarksUiState(bookmarks = listOf(article), loaded = true, editing = article, draft = draft))
        composeRule.onNodeWithTag(BookmarksTags.EDIT_TITLE).assertTextContains("Mine")
        composeRule.onNodeWithTag(BookmarksTags.EDIT_TAGS).assertTextContains("deep")
        composeRule.onNodeWithTag(BookmarksTags.EDIT_NOTES).assertTextContains("Notes typed before the save failed")
        composeRule.onNodeWithTag(BookmarksTags.EDIT_SAVE).performClick()
        assertEquals(listOf("save $draft"), calls)
    }

    @Test
    fun `an equal failure after another is shown again and reports its own end`() {
        val dismissed = mutableListOf<Long>()
        var state by mutableStateOf(BookmarksUiState(loaded = true, snackbar = BookmarksSnackbar.Failed(BookmarkFailure.OFFLINE, id = 1)))
        composeRule.setContent { CockpitTheme { BookmarksContent(state, callbacks.copy(dismiss = { dismissed += it.id })) } }
        composeRule.onNodeWithText("Can't reach the server. Nothing was changed.").assertExists()

        state = state.copy(snackbar = BookmarksSnackbar.Failed(BookmarkFailure.OFFLINE, id = 2))
        composeRule.onNodeWithText("Can't reach the server. Nothing was changed.").assertExists()
        composeRule.mainClock.advanceTimeBy(15_000)
        composeRule.waitForIdle()
        assertEquals(2L, dismissed.last())
    }

    @Test
    fun `the editor shows the bookmark and saves the draft`() {
        show(BookmarksUiState(bookmarks = listOf(article), loaded = true, editing = article))
        composeRule.onNodeWithTag(BookmarksTags.EDIT_TITLE).performTextReplacement("Attention")
        composeRule.onNodeWithTag(BookmarksTags.EDIT_TAGS).assertTextContains("reading focus")
        composeRule.onNodeWithTag(BookmarksTags.EDIT_TAGS).performTextReplacement("focus, #deep")
        composeRule.onNodeWithTag(BookmarksTags.EDIT_NOTES).assertTextContains("A long read about attention")
        composeRule.onNodeWithTag(BookmarksTags.EDIT_SAVE).performClick()
        assertEquals(listOf("save ${BookmarkDraft("Attention", "focus, #deep", "A long read about attention")}"), calls)
    }

    @Test
    fun `the editor can be cancelled`() {
        show(BookmarksUiState(editing = article))
        composeRule.onNodeWithTag(BookmarksTags.EDIT_CANCEL).performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun `a deleted bookmark's snackbar offers undo`() {
        show(BookmarksUiState(loaded = true, snackbar = BookmarksSnackbar.Trashed(article)))
        composeRule.onNodeWithText("Moved “Slow reading” to the Trash").assertExists()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("undo ${article.id}"), calls)
    }

    @Test
    fun `a failed change and a missing browser are told in a snackbar`() {
        var state by mutableStateOf(BookmarksUiState(loaded = true, snackbar = BookmarksSnackbar.Failed(BookmarkFailure.CONFLICT)))
        composeRule.setContent { CockpitTheme { BookmarksContent(state, callbacks) } }
        composeRule.onNodeWithText("The bookmark was changed elsewhere", substring = true).assertExists()
        state = state.copy(snackbar = BookmarksSnackbar.Failed(BookmarkFailure.OFFLINE))
        composeRule.onNodeWithText("Can't reach the server. Nothing was changed.").assertExists()
        state = state.copy(snackbar = BookmarksSnackbar.Failed(BookmarkFailure.FAILED))
        composeRule.onNodeWithText("The bookmark could not be changed.").assertExists()
        state = state.copy(snackbar = BookmarksSnackbar.NoBrowser)
        composeRule.onNodeWithText("No app on this device opens web pages.").assertExists()
    }

    @Test
    fun `a snackbar that times out without undo reports itself dismissed`() {
        show(BookmarksUiState(loaded = true, snackbar = BookmarksSnackbar.Trashed(article)))
        composeRule.onNodeWithText("Moved “Slow reading” to the Trash").assertExists()
        composeRule.mainClock.advanceTimeBy(15_000)
        composeRule.waitForIdle()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun `a failed load says so, and retry waits while a load runs`() {
        show(BookmarksUiState(loaded = false, loading = true, message = BookmarksMessage.FAILED))
        composeRule.onNodeWithTag(BookmarksTags.MESSAGE).assertExists()
        composeRule.onNodeWithText("The bookmarks could not be loaded.").assertExists()
        composeRule.onNodeWithTag(BookmarksTags.RETRY).assertIsNotEnabled()
        composeRule.onNodeWithTag(BookmarksTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `pulling the list down asks to refresh, and a pulled refresh shows no extra progress bar`() {
        var state by mutableStateOf(BookmarksUiState(bookmarks = listOf(article), loaded = true))
        composeRule.setContent { CockpitTheme { BookmarksContent(state, callbacks) } }
        composeRule.onNodeWithTag(BookmarksTags.LIST).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertEquals(listOf("refresh"), calls)

        state = state.copy(loading = true, refreshing = true)
        composeRule.onNodeWithTag(BookmarksTags.LOADING).assertDoesNotExist()
    }

    @Test
    fun `an untitled bookmark is saved with a blank title, tags and notes`() {
        show(BookmarksUiState(bookmarks = listOf(read), view = BookmarkView.ALL, loaded = true, editing = read))
        composeRule.onNodeWithTag(BookmarksTags.EDIT_SAVE).performClick()
        assertEquals(listOf("save ${BookmarkDraft("", "", "")}"), calls)
    }

    @Test
    fun `the tag filter marks the chosen tag`() {
        show(
            BookmarksUiState(
                bookmarks = listOf(article),
                tags = listOf(BookmarkTag("reading", 1), BookmarkTag("focus", 1)),
                tag = "Focus",
                loaded = true,
            ),
        )
        composeRule.onNodeWithTag(BookmarksTags.tag("focus")).assertIsSelected()
        composeRule.onNodeWithTag(BookmarksTags.tag("reading")).assertIsNotSelected()
        composeRule.onNodeWithTag(BookmarksTags.ALL_TAGS).assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "de")
    fun `the screen is translated to German`() {
        show(BookmarksUiState(bookmarks = listOf(read), view = BookmarkView.ALL, loaded = true))
        composeRule.onNodeWithText("Später lesen").assertExists()
        composeRule.onNodeWithText("Alle Lesezeichen").assertExists()
        composeRule.onNodeWithTag(BookmarksTags.bookmark(read.id)).assertTextContains("blog.example.net · Gelesen", substring = true)
    }
}
