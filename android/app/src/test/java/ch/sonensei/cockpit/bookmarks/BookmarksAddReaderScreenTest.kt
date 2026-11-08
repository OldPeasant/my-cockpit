package ch.sonensei.cockpit.bookmarks

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/** The add dialog, the read-later and Inbox actions and the reader, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class BookmarksAddReaderScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val article =
        Bookmark(
            id(1),
            "https://www.example.org/article",
            "Slow reading",
            "Slow reading",
            "A long read about attention",
            null,
            listOf("reading"),
            true,
            null,
            Instant.EPOCH,
            1,
        )
    private val notLater = article.copy(id = id(2), title = "Fast news", displayTitle = "Fast news", readLater = false)
    private val calls = mutableListOf<String>()

    private val callbacks =
        BookmarksCallbacks(
            open = { calls += "open ${it.id}" },
            toggleRead = { calls += "read ${it.id}" },
            toggleReadLater = { calls += "later ${it.id}" },
            toInbox = { calls += "inbox ${it.id}" },
            read = { calls += "reader ${it.id}" },
            closeReader = { calls += "close" },
            retryReader = { calls += "retry" },
            saveSnapshot = { calls += "snapshot" },
            startAdd = { calls += "start" },
            add = { calls += "add $it" },
            cancelAdd = { calls += "cancel" },
        )

    private fun show(state: BookmarksUiState) =
        composeRule.setContent {
            CockpitTheme { BookmarksContent(state, callbacks) }
        }

    // --- Add bookmark ---

    @Test
    fun `the add button asks to open the dialog`() {
        show(BookmarksUiState(bookmarks = listOf(article), loaded = true))
        composeRule.onNodeWithContentDescription("Add bookmark").performClick()
        assertEquals(listOf("start"), calls)
    }

    @Test
    fun `the add dialog saves the address, title, tags and read later as typed`() {
        show(BookmarksUiState(loaded = true, adding = AddBookmarkState(NewBookmark("", readLater = true))))

        composeRule.onNodeWithText("Add bookmark", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(AdderTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(AdderTags.READ_LATER).assertIsOn()
        composeRule.onNodeWithTag(AdderTags.URL).performTextInput("https://example.org/slow")
        composeRule.onNodeWithTag(AdderTags.TITLE).performTextInput("Slow web")
        composeRule.onNodeWithTag(AdderTags.TAGS).performTextInput("reading, #web")
        // Robolectric's small default screen (320x470dp) squeezes the dialog's last row to zero height, so a touch
        // would miss it: its click action is invoked instead.
        composeRule.onNodeWithTag(AdderTags.READ_LATER).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(AdderTags.READ_LATER).assertIsOff()
        composeRule.onNodeWithTag(AdderTags.SAVE).assertIsEnabled().performClick()

        assertEquals(listOf("add ${NewBookmark("https://example.org/slow", "Slow web", "reading, #web", readLater = false)}"), calls)
    }

    @Test
    fun `the add dialog cannot save a blank address and can be cancelled`() {
        show(BookmarksUiState(loaded = true, adding = AddBookmarkState()))
        composeRule.onNodeWithTag(AdderTags.READ_LATER).assertIsOff()
        composeRule.onNodeWithTag(AdderTags.URL).performTextInput("   ")
        composeRule.onNodeWithTag(AdderTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(AdderTags.CANCEL).performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun `the add dialog opened again after a failure shows the input and the reason`() {
        var state by mutableStateOf(
            BookmarksUiState(
                loaded = true,
                adding = AddBookmarkState(NewBookmark("example.org", "Kept title", "kept"), failure = AddFailure.INVALID_URL),
            ),
        )
        composeRule.setContent { CockpitTheme { BookmarksContent(state, callbacks) } }

        composeRule.onNodeWithTag(AdderTags.URL).assertTextContains("example.org")
        composeRule.onNodeWithTag(AdderTags.TITLE).assertTextContains("Kept title")
        composeRule.onNodeWithTag(AdderTags.TAGS).assertTextContains("kept")
        composeRule
            .onNodeWithTag(AdderTags.ERROR)
            .assertTextContains("Enter a full web address starting with http:// or https://.")

        state = state.copy(adding = state.adding!!.copy(failure = AddFailure.INVALID_TAGS))
        composeRule.onNodeWithTag(AdderTags.ERROR).assertTextContains("Use at most 50 tags, each at most 50 characters long.")
        state = state.copy(adding = state.adding!!.copy(failure = AddFailure.OFFLINE))
        composeRule.onNodeWithTag(AdderTags.ERROR).assertTextContains("Can't reach the server", substring = true)
        state = state.copy(adding = state.adding!!.copy(failure = AddFailure.FAILED))
        composeRule.onNodeWithTag(AdderTags.ERROR).assertTextContains("The bookmark could not be saved.")

        composeRule.onNodeWithTag(AdderTags.URL).performTextReplacement("https://example.org")
        composeRule.onNodeWithTag(AdderTags.SAVE).performClick()
        assertEquals(listOf("add ${NewBookmark("https://example.org", "Kept title", "kept")}"), calls)
    }

    @Test
    fun `the add dialog marks the field the failure is about, and no field for other failures`() {
        val hasError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
        var state by mutableStateOf(
            BookmarksUiState(adding = AddBookmarkState(NewBookmark("example.org", tags = "kept"), failure = AddFailure.INVALID_URL)),
        )
        composeRule.setContent { CockpitTheme { BookmarksContent(state, callbacks) } }

        composeRule.onNodeWithTag(AdderTags.URL).assert(hasError)
        composeRule.onNodeWithTag(AdderTags.TAGS).assert(!hasError)

        state = state.copy(adding = state.adding!!.copy(failure = AddFailure.INVALID_TAGS))
        composeRule.onNodeWithTag(AdderTags.URL).assert(!hasError)
        composeRule.onNodeWithTag(AdderTags.TAGS).assert(hasError)

        for (other in listOf(AddFailure.OFFLINE, AddFailure.FAILED)) {
            state = state.copy(adding = state.adding!!.copy(failure = other))
            composeRule.onNodeWithTag(AdderTags.URL).assert(!hasError)
            composeRule.onNodeWithTag(AdderTags.TAGS).assert(!hasError)
        }
    }

    @Test
    fun `while it saves the add dialog cannot be changed, saved again or cancelled`() {
        show(BookmarksUiState(loaded = true, adding = AddBookmarkState(NewBookmark("https://example.org"), saving = true)))
        composeRule.onNodeWithTag(AdderTags.URL).assertIsNotEnabled()
        composeRule.onNodeWithTag(AdderTags.TITLE).assertIsNotEnabled()
        composeRule.onNodeWithTag(AdderTags.TAGS).assertIsNotEnabled()
        composeRule.onNodeWithTag(AdderTags.READ_LATER).assertIsNotEnabled()
        composeRule.onNodeWithTag(AdderTags.SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(AdderTags.CANCEL).assertIsNotEnabled()
        composeRule.onNodeWithTag(AdderTags.ERROR).assertDoesNotExist()
    }

    // --- Row actions ---

    @Test
    fun `the row menu toggles read later, opens the reader and makes an Inbox task`() {
        show(BookmarksUiState(bookmarks = listOf(article, notLater), view = BookmarkView.ALL, loaded = true))

        composeRule.onNodeWithTag(BookmarksTags.menu(article.id)).performClick()
        composeRule.onNodeWithTag(BookmarksTags.ACTION_READ_LATER).assertTextContains("Remove from read later").performClick()
        composeRule.onNodeWithTag(BookmarksTags.menu(notLater.id)).performClick()
        composeRule.onNodeWithTag(BookmarksTags.ACTION_READ_LATER).assertTextContains("Read later").performClick()
        composeRule.onNodeWithTag(BookmarksTags.menu(article.id)).performClick()
        composeRule.onNodeWithTag(BookmarksTags.ACTION_OPEN_READER).assertTextContains("Read saved copy").performClick()
        composeRule.onNodeWithTag(BookmarksTags.menu(article.id)).performClick()
        composeRule.onNodeWithTag(BookmarksTags.ACTION_INBOX).assertTextContains("To Inbox task").performClick()

        assertEquals(listOf("later ${article.id}", "later ${notLater.id}", "reader ${article.id}", "inbox ${article.id}"), calls)
    }

    @Test
    fun `an Inbox task is confirmed in a snackbar, also when it could not be linked`() {
        var state by mutableStateOf(BookmarksUiState(loaded = true, snackbar = BookmarksSnackbar.AddedToInbox(linked = true, id = 1)))
        composeRule.setContent { CockpitTheme { BookmarksContent(state, callbacks) } }
        composeRule.onNodeWithText("Added to the Inbox").assertExists()
        state = state.copy(snackbar = BookmarksSnackbar.AddedToInbox(linked = false, id = 2))
        composeRule.onNodeWithText("Added to the Inbox, but it could not be linked to the bookmark").assertExists()
    }

    // --- Reader ---

    @Test
    fun `the reader replaces the list and shows the readable copy as text`() {
        val reading = ReaderState(article, snapshot = BookmarkSnapshot("<h1>Attention</h1><p>Read <b>slowly</b>.</p>", truncated = false))
        show(BookmarksUiState(bookmarks = listOf(article), loaded = true, reading = reading))

        composeRule.onNodeWithTag(BookmarksTags.LIST).assertDoesNotExist()
        composeRule.onNodeWithTag(AdderTags.ADD).assertDoesNotExist()
        composeRule.onNodeWithTag(ReaderTags.TITLE).assertTextContains("Slow reading")
        composeRule.onNodeWithTag(ReaderTags.ORIGINAL).assertTextContains("Open original (example.org)")
        val text = composeRule.onNodeWithTag(ReaderTags.TEXT)
        text.assertTextContains("Attention", substring = true)
        text.assertTextContains("Read slowly.", substring = true)
        // The markup itself is never shown.
        composeRule.onNodeWithText("<b>", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(ReaderTags.TRUNCATED).assertDoesNotExist()
        composeRule.onNodeWithTag(ReaderTags.SAVE_COPY).assertTextContains("Save a new copy")
        composeRule.onNodeWithTag(ReaderTags.LOADING).assertDoesNotExist()
    }

    @Test
    fun `the reader's buttons go back, open the original, change the bookmark and save a copy`() {
        show(BookmarksUiState(reading = ReaderState(article, snapshot = BookmarkSnapshot("<p>Text</p>", truncated = true))))

        composeRule.onNodeWithTag(ReaderTags.TRUNCATED).assertExists()
        composeRule.onNodeWithTag(ReaderTags.READ).assertTextContains("Mark as read").performClick()
        composeRule.onNodeWithTag(ReaderTags.READ_LATER).assertTextContains("Remove from read later").performClick()
        composeRule.onNodeWithTag(ReaderTags.INBOX).performClick()
        composeRule.onNodeWithTag(ReaderTags.ORIGINAL).performClick()
        composeRule.onNodeWithTag(ReaderTags.SAVE_COPY).performClick()
        composeRule.onNodeWithContentDescription("Back to the bookmarks").performClick()

        assertEquals(
            listOf("read ${article.id}", "later ${article.id}", "inbox ${article.id}", "open ${article.id}", "snapshot", "close"),
            calls,
        )
    }

    @Test
    fun `the device's back closes the reader`() {
        show(BookmarksUiState(reading = ReaderState(article)))
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `while a change of the bookmark runs, the reader's actions are disabled`() {
        show(BookmarksUiState(reading = ReaderState(article), busy = setOf(article.id)))
        composeRule.onNodeWithTag(ReaderTags.READ).assertIsNotEnabled()
        composeRule.onNodeWithTag(ReaderTags.READ_LATER).assertIsNotEnabled()
        composeRule.onNodeWithTag(ReaderTags.INBOX).assertIsNotEnabled()
        composeRule.onNodeWithTag(ReaderTags.SAVE_COPY).assertIsNotEnabled()
    }

    @Test
    fun `a bookmark without a copy offers to save one, and a read one to mark it unread`() {
        val read = notLater.copy(readAt = Instant.EPOCH)
        show(BookmarksUiState(reading = ReaderState(read)))

        composeRule.onNodeWithTag(ReaderTags.EMPTY).assertTextContains("No readable copy was saved for this page.")
        composeRule.onNodeWithTag(ReaderTags.READ).assertTextContains("Mark as unread")
        composeRule.onNodeWithTag(ReaderTags.READ_LATER).assertTextContains("Read later")
        composeRule.onNodeWithTag(ReaderTags.SAVE_COPY).assertTextContains("Save readable copy").performClick()
        assertEquals(listOf("snapshot"), calls)
    }

    @Test
    fun `while the reader loads or saves it shows progress and no empty text`() {
        var state by mutableStateOf(BookmarksUiState(reading = ReaderState(article, loading = true)))
        composeRule.setContent { CockpitTheme { BookmarksContent(state, callbacks) } }
        composeRule.onNodeWithTag(ReaderTags.LOADING).assertExists()
        composeRule.onNodeWithTag(ReaderTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(ReaderTags.SAVE_COPY).assertDoesNotExist()

        state = state.copy(reading = ReaderState(article, saving = true))
        composeRule.onNodeWithTag(ReaderTags.LOADING).assertExists()
        composeRule.onNodeWithTag(ReaderTags.SAVE_COPY).assertIsNotEnabled()
    }

    @Test
    fun `a reader that could not load says why and offers to retry`() {
        var state by mutableStateOf(BookmarksUiState(reading = ReaderState(article, failure = ReaderFailure.OFFLINE)))
        composeRule.setContent { CockpitTheme { BookmarksContent(state, callbacks) } }
        composeRule.onNodeWithTag(ReaderTags.ERROR).assertTextContains("Can't reach the server.")
        composeRule.onNodeWithTag(ReaderTags.SAVE_COPY).assertDoesNotExist()
        composeRule.onNodeWithTag(ReaderTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)

        state = state.copy(reading = ReaderState(article, failure = ReaderFailure.FAILED))
        composeRule.onNodeWithTag(ReaderTags.ERROR).assertTextContains("The readable copy could not be loaded.")
        composeRule.onNodeWithTag(ReaderTags.RETRY).assertIsEnabled()
    }

    @Test
    fun `an unreadable page says so, keeps the earlier copy and offers to try saving again`() {
        var state by mutableStateOf(
            BookmarksUiState(
                reading =
                    ReaderState(
                        article,
                        snapshot = BookmarkSnapshot("<p>Earlier copy</p>", false),
                        failure = ReaderFailure.UNREADABLE,
                    ),
            ),
        )
        composeRule.setContent { CockpitTheme { BookmarksContent(state, callbacks) } }
        composeRule
            .onNodeWithTag(ReaderTags.ERROR)
            .assertTextContains("The page could not be fetched or has no readable text.")
        composeRule.onNodeWithTag(ReaderTags.TEXT).assertTextContains("Earlier copy", substring = true)
        // Trying again saves a copy anew (the page may be reachable now); reloading the old one would not help.
        composeRule.onNodeWithTag(ReaderTags.RETRY).assertDoesNotExist()
        composeRule
            .onNodeWithTag(ReaderTags.SAVE_COPY)
            .assertTextContains("Retry")
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf("snapshot"), calls)

        state = state.copy(reading = ReaderState(article, failure = ReaderFailure.UNREADABLE))
        composeRule.onNodeWithTag(ReaderTags.TEXT).assertDoesNotExist()
        composeRule.onNodeWithTag(ReaderTags.ERROR).assertExists()
        composeRule.onNodeWithTag(ReaderTags.SAVE_COPY).assertIsEnabled()

        // While another change of the bookmark runs, it waits.
        state = state.copy(busy = setOf(article.id))
        composeRule.onNodeWithTag(ReaderTags.SAVE_COPY).assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "de")
    fun `the add dialog and the reader are translated to German`() {
        var state by mutableStateOf(BookmarksUiState(adding = AddBookmarkState(failure = AddFailure.INVALID_URL)))
        composeRule.setContent { CockpitTheme { BookmarksContent(state, callbacks) } }
        composeRule.onNodeWithText("Webadresse", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Titel (optional)", useUnmergedTree = true).assertExists()
        composeRule
            .onNodeWithTag(AdderTags.ERROR)
            .assertTextContains("Gib eine vollständige Webadresse ein, die mit http:// oder https:// beginnt.")
        state = state.copy(adding = AddBookmarkState(failure = AddFailure.INVALID_TAGS))
        composeRule.onNodeWithTag(AdderTags.ERROR).assertTextContains("Verwende höchstens 50 Tags mit je höchstens 50 Zeichen.")

        state = BookmarksUiState(reading = ReaderState(article))
        composeRule.onNodeWithTag(ReaderTags.EMPTY).assertTextContains("Für diese Seite wurde keine lesbare Kopie gespeichert.")
        composeRule.onNodeWithTag(ReaderTags.SAVE_COPY).assertTextContains("Lesbare Kopie speichern")
        composeRule.onNodeWithTag(ReaderTags.INBOX).assertTextContains("Als Aufgabe in den Eingang")
        composeRule.onNodeWithTag(ReaderTags.ORIGINAL).assertTextContains("Original öffnen (example.org)")
        composeRule.onNodeWithContentDescription("Zurück zu den Lesezeichen").assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun `the row menu, the add button and the Inbox confirmations are translated to German`() {
        var state by mutableStateOf(
            BookmarksUiState(
                bookmarks = listOf(article, notLater),
                view = BookmarkView.ALL,
                loaded = true,
                snackbar = BookmarksSnackbar.AddedToInbox(linked = true, id = 1),
            ),
        )
        composeRule.setContent { CockpitTheme { BookmarksContent(state, callbacks) } }

        composeRule.onNodeWithContentDescription("Lesezeichen hinzufügen").assertExists()
        composeRule.onNodeWithText("Zum Eingang hinzugefügt").assertExists()
        composeRule.onNodeWithTag(BookmarksTags.menu(article.id)).performClick()
        composeRule.onNodeWithTag(BookmarksTags.ACTION_READ_LATER).assertTextContains("Aus „Später lesen“ entfernen")
        composeRule.onNodeWithTag(BookmarksTags.ACTION_OPEN_READER).assertTextContains("Gespeicherte Kopie lesen")
        composeRule.onNodeWithTag(BookmarksTags.ACTION_INBOX).assertTextContains("Als Aufgabe in den Eingang").performClick()
        composeRule.onNodeWithTag(BookmarksTags.menu(notLater.id)).performClick()
        composeRule.onNodeWithTag(BookmarksTags.ACTION_READ_LATER).assertTextContains("Später lesen")

        state = state.copy(snackbar = BookmarksSnackbar.AddedToInbox(linked = false, id = 2))
        composeRule.onNodeWithText("Zum Eingang hinzugefügt, aber nicht mit dem Lesezeichen verknüpft").assertExists()
    }
}
