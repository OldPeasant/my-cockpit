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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.core.links.ItemRef
import ch.sonensei.cockpit.core.links.LinkedItem
import ch.sonensei.cockpit.core.links.LinkedItemsTags
import ch.sonensei.cockpit.core.links.LinksRepository
import ch.sonensei.cockpit.core.links.LinksResult
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
 * The note editor's linked-items panel in the Hilt activity, with fake notes and links repositories: the panel lists the
 * open note's links, a linked note opens in place of the note (after saving it), and closing it returns to the note.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class NoteEditorLinksRouteInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val repo: NotesRepository = mockk()

    @BindValue
    @JvmField
    val links: LinksRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val trip = Note(id(1), null, "Trip", "Trip\n\n- [ ] Pack the bags", false, emptyList(), Instant.EPOCH, 3)
    private val packing = Note(id(2), null, "Packing list", "Packing list\n\nA fictitious map", false, emptyList(), Instant.EPOCH, 1)
    private val toPacking = LinkedItem(id(10), "NOTE", packing.id, "Packing list", null, "/notes/${id(2)}", true, manual = true)

    /** An item type without an Android screen and, with no server known, without a web link: a Screen target. */
    private val toEvent = LinkedItem(id(11), "EVENT", id(3), "Flight", null, "/calendar/${id(3)}", available = true, manual = false)

    /** What happened, in order: "saved" when a save of the trip note answered, "get {title}" when a note was loaded. */
    private val events = CopyOnWriteArrayList<String>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { repo.tags() } returns NotesResult.Ok(emptyList())
        coEvery { repo.list(null) } returns NotesResult.Ok(listOf(trip, packing))
        coEvery { repo.get(trip.id) } answers {
            events += "get Trip"
            NotesResult.Ok(trip)
        }
        coEvery { repo.get(packing.id) } answers {
            events += "get Packing list"
            NotesResult.Ok(packing)
        }
        coEvery { links.list(any()) } returns LinksResult.Ok(emptyList())
        coEvery { links.list(ItemRef("NOTE", trip.id)) } returns LinksResult.Ok(listOf(toPacking, toEvent))
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    /** Opens the Notes screen and the trip note in it, in the view with its links. */
    private fun openTrip() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Notes"))
        composeRule.onNodeWithText("Notes").performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(NotesTags.note(trip.id)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(NotesTags.note(trip.id)).performClick()
        waitForTag(LinkedItemsTags.link(toPacking))
    }

    private fun waitForTag(tag: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `the opened note shows the links panel with its linked items, loaded for that note`() {
        openTrip()

        composeRule.onNodeWithTag(NoteEditorTags.VIEW).assertExists()
        composeRule.onNodeWithTag(LinkedItemsTags.PANEL).assertExists()
        composeRule.onNodeWithTag(LinkedItemsTags.link(toEvent)).assertExists()
        coVerify(exactly = 1) { links.list(ItemRef("NOTE", trip.id)) }
    }

    @Test
    fun `the links panel is gone while the note is edited`() {
        openTrip()

        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.BODY).assertExists()
        composeRule.onNodeWithTag(LinkedItemsTags.PANEL).assertDoesNotExist()
    }

    @Test
    fun `a linked note opens in place, and its close returns to the note instead of the list`() {
        openTrip()

        composeRule.onNodeWithTag(LinkedItemsTags.link(toPacking)).performScrollTo().performClick()
        waitForText("fictitious map")
        composeRule.onNodeWithText("Pack the bags", substring = true).assertDoesNotExist()

        // The linked note's own back arrow closes only it.
        composeRule.onNodeWithContentDescription("Back to notes").performClick()
        waitForText("Pack the bags")
        composeRule.onNodeWithTag(LinkedItemsTags.link(toPacking)).assertExists()
        composeRule.onNodeWithTag(NotesTags.NEW).assertDoesNotExist()
        coVerify(exactly = 1) { repo.get(packing.id) }
    }

    @Test
    fun `the system back from a linked note returns to the note`() {
        openTrip()
        composeRule.onNodeWithTag(LinkedItemsTags.link(toPacking)).performScrollTo().performClick()
        waitForText("fictitious map")

        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForText("Pack the bags")

        composeRule.onNodeWithTag(LinkedItemsTags.PANEL).assertExists()
        composeRule.onNodeWithTag(NotesTags.NEW).assertDoesNotExist()
    }

    @Test
    fun `a change not saved yet is saved before the linked note is opened`() {
        val ticked = trip.copy(bodyMd = "Trip\n\n- [x] Pack the bags", version = 4)
        coEvery { repo.save(trip, NoteDraft("", ticked.bodyMd)) } answers {
            events += "saved"
            SaveResult.Saved(ticked)
        }
        openTrip()
        events.clear()

        // Ticking a checklist item in the view waits for the autosave; opening the link must not wait for it.
        composeRule.onNodeWithText("Pack the bags").performClick()
        composeRule.onNodeWithTag(LinkedItemsTags.link(toPacking)).performScrollTo().performClick()
        waitForText("fictitious map")

        assertEquals(listOf("saved", "get Packing list"), events.toList())
    }

    @Test
    fun `a linked item without an Android screen keeps the note open`() {
        openTrip()

        composeRule.onNodeWithTag(LinkedItemsTags.link(toEvent)).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(NoteEditorTags.VIEW).assertExists()
        composeRule.onNodeWithText("Pack the bags", substring = true).assertExists()
        coVerify(exactly = 0) { repo.get(packing.id) }
    }

    /** A task the note links to inline once its body has `[[task:…]]`. */
    private val toTask = LinkedItem(id(12), "TASK", id(4), "Book the flight", null, "/tasks/${id(4)}", true, manual = false)

    @Test
    fun `a new version of the note saved with an inline link loads the panel's links again`() {
        val linked = "Trip\n\n- [ ] Pack the bags\n\n[[task:${id(4)}]]"
        val saved = trip.copy(bodyMd = linked, version = 4)
        coEvery { repo.save(trip, NoteDraft("", linked)) } returns SaveResult.Saved(saved)
        coEvery { links.list(ItemRef("NOTE", trip.id)) } returnsMany
            listOf(LinksResult.Ok(listOf(toPacking, toEvent)), LinksResult.Ok(listOf(toPacking, toEvent, toTask)))
        openTrip()
        composeRule.onNodeWithTag(LinkedItemsTags.link(toTask)).assertDoesNotExist()

        // Edit the body, then back to the view: that saves the note, and the server syncs its inline links.
        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).performClick()
        composeRule.onNodeWithTag(NoteEditorTags.BODY).performTextReplacement(linked)
        composeRule.onNodeWithTag(NoteEditorTags.TOGGLE).performClick()
        waitForTag(LinkedItemsTags.link(toTask))

        coVerify(exactly = 1) { repo.save(trip, NoteDraft("", linked)) }
        coVerify(exactly = 2) { links.list(ItemRef("NOTE", trip.id)) }
    }

    @Test
    fun `the note opened again loads its links again`() {
        coEvery { links.list(ItemRef("NOTE", trip.id)) } returnsMany
            listOf(LinksResult.Ok(listOf(toPacking, toEvent)), LinksResult.Ok(listOf(toPacking, toEvent, toTask)))
        openTrip()

        composeRule.onNodeWithContentDescription("Back to notes").performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(NotesTags.note(trip.id)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(NotesTags.note(trip.id)).performClick()
        waitForTag(LinkedItemsTags.link(toTask))

        coVerify(exactly = 2) { links.list(ItemRef("NOTE", trip.id)) }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
