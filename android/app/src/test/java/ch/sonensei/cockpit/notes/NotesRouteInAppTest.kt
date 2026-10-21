package ch.sonensei.cockpit.notes

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

/**
 * The Notes route in the Hilt activity with a fake repository: leaving the editor hands its save to the list, which
 * reloads only after it, and a note opened again from the list is loaded again.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class NotesRouteInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val repo: NotesRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private val id = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val loaded = Note(id, null, "Shopping", "Shopping", false, emptyList(), Instant.EPOCH, 3)
    private val edited = loaded.copy(bodyMd = "Shopping\nMilk", version = 4)

    /** What happened, in order: "list" for each list load, "saved" when a save answered. */
    private val events = CopyOnWriteArrayList<String>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { repo.tags() } returns NotesResult.Ok(emptyList())
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun openNotes() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Notes"))
        composeRule.onNodeWithText("Notes").performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(NotesTags.note(id)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `leaving the editor lets the list reload only once the save is done, so it shows the new text`() {
        coEvery { repo.list(null) } answers {
            events += "list"
            NotesResult.Ok(listOf(if ("saved" in events) edited else loaded))
        }
        coEvery { repo.get(id) } returns NotesResult.Ok(loaded)
        val save = CompletableDeferred<SaveResult>()
        coEvery { repo.save(loaded, NoteDraft("", "Shopping\nMilk")) } coAnswers {
            save.await().also { events += "saved" }
        }
        openNotes()

        composeRule.onNodeWithTag(NotesTags.note(id)).performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(NoteEditorTags.TOGGLE).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.BODY).performTextReplacement("Shopping\nMilk")
        composeRule.onNodeWithContentDescription("Back to notes").performClick()
        composeRule.waitForIdle()

        // The list is back, but its reload waits for the save.
        composeRule.onNodeWithTag(NotesTags.NEW).assertExists()
        assertEquals(listOf("list"), events.toList())

        save.complete(SaveResult.Saved(edited))
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText("Milk").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("list", "saved", "list"), events.toList())
    }

    @Test
    fun `a note opened again from the list is loaded again and shows the server's newer text`() {
        coEvery { repo.list(null) } returns NotesResult.Ok(listOf(loaded))
        val changed = loaded.copy(bodyMd = "Changed on the web", version = 9)
        coEvery { repo.get(id) } returns NotesResult.Ok(loaded) andThen NotesResult.Ok(changed)
        openNotes()

        composeRule.onNodeWithTag(NotesTags.note(id)).performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(NoteEditorTags.VIEW).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithContentDescription("Back to notes").performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(NotesTags.note(id)).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithTag(NotesTags.note(id)).performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText("Changed on the web").fetchSemanticsNodes().isNotEmpty() }
        coVerify(exactly = 2) { repo.get(id) }
        coVerify(exactly = 0) { repo.save(any(), any()) }
    }

    @Test
    fun `the shell's back arrow leaves the Notes screen and still saves the text typed last`() {
        coEvery { repo.list(null) } returns NotesResult.Ok(listOf(loaded))
        coEvery { repo.get(id) } returns NotesResult.Ok(loaded)
        coEvery { repo.save(loaded, NoteDraft("", "Shopping\nMilk")) } answers {
            events += "saved"
            SaveResult.Saved(edited)
        }
        openNotes()

        composeRule.onNodeWithTag(NotesTags.note(id)).performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(NoteEditorTags.TOGGLE).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.BODY).performTextReplacement("Shopping\nMilk")
        // The top bar's own arrow (not the editor's "Back to notes"): it pops the whole Notes screen.
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(
            WAIT_MS,
        ) { composeRule.onAllNodesWithTag(screenTag(CockpitDestination.More)).fetchSemanticsNodes().isNotEmpty() }

        composeRule.waitUntil(WAIT_MS) { "saved" in events }
        coVerify(exactly = 1) { repo.save(loaded, NoteDraft("", "Shopping\nMilk")) }
    }

    @Test
    fun `a new note left with unsaved text after it was created shows that text when opened from the list`() {
        val createdId = UUID.fromString("00000000-0000-7000-8000-000000000002")
        val created = Note(createdId, null, "Idea", "Idea", false, emptyList(), Instant.EPOCH, 1)
        coEvery { repo.list(null) } returns NotesResult.Ok(listOf(loaded, created))
        coEvery { repo.save(null, NoteDraft("", "Idea")) } returns SaveResult.Saved(created)
        coEvery { repo.save(created, NoteDraft("", "Idea and more")) } returns SaveResult.Offline
        openNotes()

        composeRule.onNodeWithTag(NotesTags.NEW).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.BODY).performTextReplacement("Idea")
        // To the rendered view: saved at once, so the note is created.
        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(NoteEditorTags.VIEW).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.BODY).performTextReplacement("Idea and more")
        composeRule.onNodeWithContentDescription("Back to notes").performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(NotesTags.note(createdId)).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithTag(NotesTags.note(createdId)).performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText("Idea and more").fetchSemanticsNodes().isNotEmpty() }
        coVerify(exactly = 0) { repo.get(createdId) }
    }

    @Test
    fun `a new note that could not be created is opened again by New note, with its text`() {
        coEvery { repo.list(null) } returns NotesResult.Ok(listOf(loaded))
        coEvery { repo.save(null, NoteDraft("", "Offline idea")) } returns SaveResult.Offline
        openNotes()

        composeRule.onNodeWithTag(NotesTags.NEW).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.BODY).performTextReplacement("Offline idea")
        composeRule.onNodeWithContentDescription("Back to notes").performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(NotesTags.NEW).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithTag(NotesTags.NEW).performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText("Offline idea").fetchSemanticsNodes().isNotEmpty() }
        // Tried when leaving, and once more when the list was shown.
        coVerify(atLeast = 2) { repo.save(null, NoteDraft("", "Offline idea")) }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
