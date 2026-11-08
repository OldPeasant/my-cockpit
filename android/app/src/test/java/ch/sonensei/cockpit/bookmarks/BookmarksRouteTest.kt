package ch.sonensei.cockpit.bookmarks

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.util.UUID

/**
 * The route with a real view model: it loads when shown and again whenever the app comes back to it, so a link
 * shared from another app meanwhile appears without a restart; a tap opens the bookmark in the browser.
 */
@RunWith(AndroidJUnit4::class)
class BookmarksRouteTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** The lifecycle the route sees; the test moves it to the background and back. */
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun bookmark(
        n: Int,
        title: String,
    ) = Bookmark(id(n), "https://example.org/$n", title, title, null, null, emptyList(), true, null, Instant.EPOCH, 1)

    private val repo = mockk<BookmarksRepository>()
    private val owner = TestOwner()
    private val shared = bookmark(2, "Shared from another app")

    @Before
    fun setUp() {
        coEvery { repo.tags() } returns BookmarksResult.Ok(emptyList())
        coEvery { repo.list(BookmarkView.QUEUE, null) } returns BookmarksResult.Ok(listOf(bookmark(1, "Saved earlier"))) andThen
            BookmarksResult.Ok(listOf(shared, bookmark(1, "Saved earlier")))
        owner.registry.currentState = Lifecycle.State.RESUMED
    }

    private fun showRoute(): BookmarksViewModel {
        val viewModel = BookmarksViewModel(repo, MutableStateFlow(0))
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                CockpitTheme { BookmarksRoute(viewModel = viewModel) }
            }
        }
        composeRule.waitForIdle()
        return viewModel
    }

    @Test
    fun `the list loads once when shown and again when the app comes back to it`() {
        showRoute()
        coVerify(exactly = 1) { repo.list(BookmarkView.QUEUE, null) }
        composeRule.onNodeWithTag(BookmarksTags.bookmark(id(1))).assertExists()
        composeRule.onNodeWithTag(BookmarksTags.bookmark(shared.id)).assertDoesNotExist()

        // The user leaves for another app, shares a link to Cockpit there and comes back.
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.waitForIdle()

        coVerify(exactly = 2) { repo.list(BookmarkView.QUEUE, null) }
        composeRule.onNodeWithTag(BookmarksTags.bookmark(shared.id)).assertExists()
    }

    @Test
    fun `tapping a bookmark opens its address in the browser`() {
        showRoute()
        composeRule.onNodeWithTag(BookmarksTags.bookmark(id(1))).performClick()
        composeRule.waitForIdle()

        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.org/1", started.dataString)
    }

    @Test
    fun `without a browser on the device a tap says so instead of crashing`() {
        val viewModel = showRoute()
        // Robolectric then refuses to start activities nothing on the device handles.
        shadowOf(composeRule.activity.application).checkActivities(true)
        composeRule.onNodeWithTag(BookmarksTags.bookmark(id(1))).performClick()
        composeRule.waitForIdle()

        assertEquals(BookmarksSnackbar.NoBrowser, viewModel.uiState.value.snackbar)
        composeRule.onNodeWithText("No app on this device opens web pages.").assertExists()
    }

    @Test
    fun `a bookmark added in the dialog is saved and shown in the queue`() {
        val added = bookmark(3, "Added by hand").copy(createdAt = Instant.parse("2026-10-02T09:00:00Z"))
        coEvery { repo.create(NewBookmark("https://example.org/3", readLater = true)) } returns BookmarksResult.Ok(added)
        showRoute()

        composeRule.onNodeWithTag(AdderTags.ADD).performClick()
        composeRule.onNodeWithTag(AdderTags.URL).performTextInput("https://example.org/3")
        composeRule.onNodeWithTag(AdderTags.SAVE).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AdderTags.DIALOG).assertDoesNotExist()
        composeRule.onNodeWithTag(BookmarksTags.bookmark(added.id)).assertExists()
    }

    /** Opens the reader on bookmark 1 through its row menu, with [html] as its readable copy. */
    private fun openReaderWith(html: String) {
        val withCopy = bookmark(1, "Saved earlier").copy(url = "https://example.org/articles/1", snapshotFileId = id(90))
        coEvery { repo.get(id(1)) } returns BookmarksResult.Ok(withCopy)
        coEvery { repo.snapshot(id(90)) } returns BookmarksResult.Ok(BookmarkSnapshot(html, truncated = false))
        showRoute()
        composeRule.onNodeWithTag(BookmarksTags.menu(id(1))).performClick()
        composeRule.onNodeWithTag(BookmarksTags.ACTION_OPEN_READER).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ReaderTags.TEXT).assertExists()
    }

    @Test
    fun `a relative link in the readable copy opens in the browser, resolved against the page`() {
        openReaderWith("""<a href="next">Next part</a>""")

        composeRule.onNodeWithTag(ReaderTags.TEXT).performClick()
        composeRule.waitForIdle()

        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.org/articles/next", started.dataString)
    }

    @Test
    fun `a script link in the readable copy opens nothing`() {
        openReaderWith("""<a href="javascript:alert(1)">Click me</a>""")

        composeRule.onNodeWithTag(ReaderTags.TEXT).performClick()
        composeRule.waitForIdle()

        assertNull(shadowOf(composeRule.activity).nextStartedActivity)
    }
}
