package ch.sonensei.cockpit.calendar.edit

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.core.links.ItemRef
import ch.sonensei.cockpit.core.links.LinkedItem
import ch.sonensei.cockpit.core.links.LinkedItemsTags
import ch.sonensei.cockpit.core.links.LinksRepository
import ch.sonensei.cockpit.core.links.LinksResult
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.notes.Note
import ch.sonensei.cockpit.notes.NotesRepository
import ch.sonensei.cockpit.notes.NotesResult
import ch.sonensei.cockpit.search.SearchGroup
import ch.sonensei.cockpit.search.SearchHit
import ch.sonensei.cockpit.search.SearchResult
import ch.sonensei.cockpit.ui.theme.CockpitTheme
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
import org.robolectric.shadows.ShadowLooper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * The event editor with its real links panel ([EventLinksPanel]: the shared linked-items section and its Hilt view
 * model) in the Hilt activity, with a fake links repository: the panel is for item EVENT with the event id, adds a link
 * via the search, removes one with ✕, opens a linked item in place, reloads after "Create task / note from event", and
 * shows its error with retry while the editor stays usable.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class EventEditorLinksInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val links: LinksRepository = mockk()

    @BindValue
    @JvmField
    val notes: NotesRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val eventId = id(5)
    private val event = ItemRef("EVENT", eventId)
    private val work = CalendarInfo(id(90), "Work", null, true, false, 1)
    private val day = LocalDate.parse("2026-10-21")
    private val form =
        EventForm(
            title = "Planning",
            calendarId = work.id,
            startDate = day,
            startTime = LocalTime.of(9, 0),
            endDate = day,
            endTime = LocalTime.of(10, 0),
            zone = ZoneId.of("Europe/Zurich"),
        )
    private val saved =
        EventEditorUiState(
            open = true,
            form = form,
            calendars = listOf(work),
            localCalendars = setOf(work.id),
            links = LinksUiState(eventId),
        )

    private val agenda = Note(id(20), null, "Agenda", "Agenda\n\nA fictitious meeting agenda", false, emptyList(), Instant.EPOCH, 1)
    private val toAgenda = LinkedItem(id(30), "NOTE", agenda.id, "Agenda", null, "/notes/${agenda.id}", available = true, manual = true)
    private val budgetMail = SearchHit(id(21), "EMAIL", "Budget 2027", "Ada Example", "/mail/${id(21)}")
    private val toBudget = LinkedItem(id(31), "EMAIL", budgetMail.id, "Budget 2027", null, "/mail/${budgetMail.id}", true, manual = true)
    private val createdTask = LinkedItem(id(32), "TASK", id(22), "Planning", null, "/tasks/${id(22)}", available = true, manual = true)

    private val calls = mutableListOf<String>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var setState: (EventEditorUiState) -> Unit = {}

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { links.list(any()) } returns LinksResult.Ok(emptyList())
        coEvery { notes.tags() } returns NotesResult.Ok(emptyList())
        coEvery { notes.list(null) } returns NotesResult.Ok(listOf(agenda))
        coEvery { notes.get(agenda.id) } returns NotesResult.Ok(agenda)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    /** The editor in the Hilt activity, in place of its own content, with the default (real) links panel. */
    private fun show(initial: EventEditorUiState) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity ->
            activity.setContent {
                var state by mutableStateOf(initial)
                setState = { state = it }
                CockpitTheme {
                    EventEditorContent(
                        state = state,
                        callbacks =
                            EventEditorCallbacks(
                                close = { calls += "close" },
                                update = { change -> state = state.copy(form = state.form?.let(change)) },
                                createLinked = { calls += "create:$it" },
                                reload = { calls += "reload" },
                            ),
                    )
                }
            }
        }
    }

    private fun waitForTag(tag: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `a saved event shows the shared links panel inside its links section, loaded for item EVENT with the event id`() {
        coEvery { links.list(event) } returns LinksResult.Ok(listOf(toAgenda))
        show(saved)

        waitForTag(LinkedItemsTags.link(toAgenda))
        composeRule.onNode(hasAnyAncestor(hasTestTag(EventEditorTags.LINKS)) and hasTestTag(LinkedItemsTags.PANEL)).assertExists()
        composeRule.onNodeWithTag(LinkedItemsTags.link(toAgenda)).assertTextContains("Agenda")
        coVerify(exactly = 1) { links.list(event) }
        coVerify(exactly = 0) { links.list(match { it.type != "EVENT" || it.id != eventId }) }
    }

    @Test
    fun `Add link searches all items, links the picked email to the event and shows it right away`() {
        coEvery { links.search("budget") } returns SearchResult.Ok(listOf(SearchGroup("EMAIL", listOf(budgetMail))))
        coEvery { links.link(event, ItemRef("EMAIL", budgetMail.id)) } returns LinksResult.Ok(toBudget)
        show(saved)
        waitForTag(LinkedItemsTags.EMPTY)

        composeRule.onNodeWithTag(LinkedItemsTags.ADD).performScrollTo().performClick()
        composeRule.onNodeWithTag(LinkedItemsTags.SEARCH_FIELD).performTextInput("budget")
        // Past the search debounce of the panel's view model.
        ShadowLooper.idleMainLooper(SEARCH_WAIT_MS, TimeUnit.MILLISECONDS)
        waitForTag(LinkedItemsTags.hit(budgetMail))
        composeRule.onNodeWithTag(LinkedItemsTags.hit(budgetMail)).performClick()

        waitForTag(LinkedItemsTags.link(toBudget))
        composeRule.onNodeWithTag(LinkedItemsTags.SEARCH_FIELD).assertDoesNotExist()
        composeRule.onNodeWithTag(LinkedItemsTags.link(toBudget)).assertTextContains("Budget 2027")
        coVerify(exactly = 1) { links.link(event, ItemRef("EMAIL", budgetMail.id)) }
        // Shown from the link answer, without loading the list again.
        coVerify(exactly = 1) { links.list(event) }
    }

    @Test
    fun `a linked item is removed with its cross from the event editor`() {
        coEvery { links.list(event) } returns LinksResult.Ok(listOf(toAgenda, toBudget))
        coEvery { links.remove(toBudget.linkId) } returns LinksResult.Ok(Unit)
        show(saved)
        waitForTag(LinkedItemsTags.link(toBudget))

        composeRule.onNodeWithContentDescription("Remove link to Budget 2027").performScrollTo().performClick()

        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(LinkedItemsTags.link(toBudget)).fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag(LinkedItemsTags.link(toAgenda)).assertExists()
        coVerify(exactly = 1) { links.remove(toBudget.linkId) }
        composeRule.onNodeWithTag(EventEditorTags.EDITOR).assertExists()
    }

    @Test
    fun `a failed removal is shown and keeps the link and the editor`() {
        coEvery { links.list(event) } returns LinksResult.Ok(listOf(toBudget))
        coEvery { links.remove(toBudget.linkId) } returns LinksResult.Offline
        show(saved)
        waitForTag(LinkedItemsTags.link(toBudget))

        composeRule.onNodeWithTag(LinkedItemsTags.remove(toBudget)).performScrollTo().performClick()

        waitForTag(LinkedItemsTags.ERROR)
        composeRule.onNodeWithText("No connection to the server.").assertExists()
        composeRule.onNodeWithTag(LinkedItemsTags.link(toBudget)).assertExists()
        composeRule.onNodeWithTag(EventEditorTags.EDITOR).assertExists()
    }

    @Test
    fun `a linked note opens in place of the editor, and back returns to the editor with its unsaved edits`() {
        coEvery { links.list(event) } returns LinksResult.Ok(listOf(toAgenda))
        show(saved)
        waitForTag(LinkedItemsTags.link(toAgenda))
        composeRule.onNodeWithTag(EventEditorTags.TITLE).performScrollTo().performTextReplacement("Planning v2")

        composeRule.onNodeWithTag(LinkedItemsTags.link(toAgenda)).performScrollTo().performClick()
        waitForText("fictitious meeting agenda")
        composeRule.onNodeWithTag(EventEditorTags.EDITOR).assertDoesNotExist()

        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForTag(EventEditorTags.EDITOR)
        composeRule.onNodeWithTag(EventEditorTags.TITLE).assertTextContains("Planning v2")
        coVerify(exactly = 1) { notes.get(agenda.id) }
        // Back from the note did not close the editor.
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a task created from the event reloads the panel so the task shows`() {
        coEvery { links.list(event) } returnsMany
            listOf(LinksResult.Ok(listOf(toAgenda)), LinksResult.Ok(listOf(toAgenda, createdTask)))
        show(saved)
        waitForTag(LinkedItemsTags.link(toAgenda))

        composeRule.onNodeWithTag(EventEditorTags.CREATE_TASK).performScrollTo().performClick()
        assertEquals(listOf("create:TASK"), calls)
        // What the editor view model does once the task was created and linked.
        composeRule.runOnIdle { setState(saved.copy(links = LinksUiState(eventId, refresh = 1))) }

        waitForTag(LinkedItemsTags.link(createdTask))
        coVerify(exactly = 2) { links.list(event) }
    }

    @Test
    fun `an offline panel shows its error and retry, and the editor stays usable`() {
        coEvery { links.list(event) } returnsMany listOf(LinksResult.Offline, LinksResult.Ok(listOf(toAgenda)))
        show(saved)

        waitForText("Linked items need a connection to the server.")
        composeRule.onNodeWithTag(EventEditorTags.TITLE).performScrollTo().performTextReplacement("Planning offline")
        composeRule.onNodeWithTag(EventEditorTags.TITLE).assertTextContains("Planning offline")
        composeRule.onNodeWithTag(EventEditorTags.CREATE_TASK).assertExists()

        composeRule.onNodeWithText("Retry").performScrollTo().performClick()

        waitForTag(LinkedItemsTags.link(toAgenda))
        composeRule.onNodeWithText("Linked items need a connection to the server.").assertDoesNotExist()
        coVerify(exactly = 2) { links.list(event) }
    }

    @Test
    fun `a new event shows no links panel and loads no links`() {
        show(saved.copy(creating = true, links = null))
        waitForTag(EventEditorTags.EDITOR)

        composeRule.onNodeWithTag(LinkedItemsTags.PANEL).assertDoesNotExist()
        coVerify(exactly = 0) { links.list(any()) }
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val SEARCH_WAIT_MS = 300L
    }
}
