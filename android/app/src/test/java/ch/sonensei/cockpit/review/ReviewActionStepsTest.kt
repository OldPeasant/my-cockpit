package ch.sonensei.cockpit.review

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/** Steps 4 to 7 of the wizard (waiting for, projects, next actions, someday), driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class ReviewActionStepsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val parcel =
        ReviewTask(
            id(1),
            "Parcel from the fictitious shop",
            version = 7,
            waitingForText = "Erika",
            waitingSince = LocalDate.of(2026, 10, 1),
        )
    private val quote = ReviewTask(id(2), "Quote from the painter", version = 3)
    private val garden = ReviewProject(id(10), "Redo the garden", stalled = true, version = 9)
    private val report = ReviewProject(id(11), "Annual report", stalled = false, status = ProjectStatus.ACTIVE, version = 4)
    private val bike = ReviewTask(id(3), "Fix the bike", version = 2, updatedAt = Instant.parse("2026-09-01T08:00:00Z"))
    private val lisbon = SomedayItem(id(4), project = false, "Visit Lisbon", 5)
    private val italian = SomedayItem(id(20), project = true, "Learn Italian", 6)

    private val calls = mutableListOf<String>()
    private val callbacks =
        ReviewCallbacks(
            loadStep = { calls += "load:${it.name}" },
            keepWaiting = { calls += "keepWaiting:${it.title}" },
            followUp = { task, title -> calls += "followUp:${task.title}:$title" },
            completeWaiting = { calls += "completeWaiting:${it.title}" },
            openProject = { calls += "openProject:${it.title}" },
            keepProject = { calls += "keepProject:${it.title}" },
            setProjectStatus = { project, status -> calls += "status:${project.title}:${status.name}" },
            setNextStepDraft = { project, text -> calls += "draft:${project.title}:$text" },
            addNextStep = { calls += "addNextStep:${it.title}" },
            keepNextAction = { calls += "keepNext:${it.title}" },
            moveToSomeday = { calls += "toSomeday:${it.title}" },
            deleteNextAction = { calls += "deleteNext:${it.title}" },
            activate = { calls += "activate:${it.title}" },
            keepSomeday = { calls += "keepSomeday:${it.title}" },
            deleteSomeday = { calls += "deleteSomeday:${it.title}" },
        )

    private fun wizard(
        step: ReviewStep,
        state: ReviewUiState,
    ) = state.copy(
        overview = ReviewOverview(OpenReview(UUID.fromString("00000000-0000-7000-8000-000000000009"), step, Instant.EPOCH), 6, emptyList()),
        wizard = true,
    )

    private fun show(state: ReviewUiState) =
        composeRule.setContent {
            CockpitTheme { ReviewContent(state, callbacks, inbox = { Box(it) }) }
        }

    private fun choice(
        item: UUID,
        tag: String,
    ): SemanticsNodeInteraction = composeRule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(ReviewTags.item(item))))

    /** A text inside the row of [item]. */
    private fun shows(
        item: UUID,
        text: String,
    ) = composeRule.onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(ReviewTags.item(item)))).assertIsDisplayed()

    private fun decided() = calls.filterNot { it.startsWith("load:") }

    @Test
    fun `waiting for - a row shows whom and since, and its buttons keep, follow up or complete it`() {
        show(wizard(ReviewStep.WAITING_FOR, ReviewUiState(waiting = StepData(listOf(parcel)))))

        shows(parcel.id, "Parcel from the fictitious shop")
        shows(parcel.id, "for Erika · since")
        choice(parcel.id, ReviewTags.KEEP).assertTextEquals("Still waiting").performClick()
        choice(parcel.id, ReviewTags.FOLLOW_UP).assertTextEquals("Follow up").performClick()
        choice(parcel.id, ReviewTags.DONE).assertTextEquals("Done").performClick()

        assertEquals(
            listOf(
                "keepWaiting:Parcel from the fictitious shop",
                "followUp:Parcel from the fictitious shop:Follow up with Erika: Parcel from the fictitious shop",
                "completeWaiting:Parcel from the fictitious shop",
            ),
            decided(),
        )
        assertEquals(listOf("load:WAITING_FOR"), calls.filter { it.startsWith("load:") })
    }

    @Test
    fun `waiting for - a row whose decision is being saved disables its choices`() {
        show(wizard(ReviewStep.WAITING_FOR, ReviewUiState(waiting = StepData(listOf(quote)), busy = setOf(quote.id))))

        choice(quote.id, ReviewTags.KEEP).assertIsNotEnabled()
        choice(quote.id, ReviewTags.FOLLOW_UP).assertIsNotEnabled()
        choice(quote.id, ReviewTags.DONE).assertIsNotEnabled()
        choice(quote.id, ReviewTags.FOLLOW_UP).performClick()
        assertEquals(emptyList<String>(), decided())
    }

    @Test
    fun `waiting for - the follow-up title without whom`() {
        show(wizard(ReviewStep.WAITING_FOR, ReviewUiState(waiting = StepData(listOf(quote)))))
        choice(quote.id, ReviewTags.FOLLOW_UP).performClick()
        assertEquals(listOf("followUp:Quote from the painter:Follow up: Quote from the painter"), decided())
    }

    @Test
    fun `an empty step says there is nothing left, a failed first load offers a retry`() {
        var state by mutableStateOf(wizard(ReviewStep.WAITING_FOR, ReviewUiState(waiting = StepData(emptyList()))))
        composeRule.setContent { CockpitTheme { ReviewContent(state, callbacks, inbox = { Box(it) }) } }

        composeRule.onNodeWithTag(ReviewTags.ITEMS_EMPTY).assertTextEquals("Nothing left to check here.")

        state = wizard(ReviewStep.WAITING_FOR, ReviewUiState(waiting = StepData(failed = true)))
        composeRule.onNodeWithTag(ReviewTags.STEP_FAILED).assertIsDisplayed()
        composeRule.onNodeWithTag(ReviewTags.STEP_RETRY).performClick()
        assertEquals(listOf("load:WAITING_FOR", "load:WAITING_FOR"), calls)
    }

    @Test
    fun `each step has its own empty text`() {
        var state by mutableStateOf(wizard(ReviewStep.PROJECTS, ReviewUiState(projectList = StepData(emptyList()))))
        composeRule.setContent { CockpitTheme { ReviewContent(state, callbacks, inbox = { Box(it) }) } }
        composeRule.onNodeWithTag(ReviewTags.ITEMS_EMPTY).assertTextEquals("No active projects left to review.")

        state = wizard(ReviewStep.NEXT_ACTIONS, ReviewUiState(staleNext = StepData(emptyList())))
        composeRule.onNodeWithTag(ReviewTags.ITEMS_EMPTY).assertTextEquals("No stale next actions.")

        state = wizard(ReviewStep.SOMEDAY, ReviewUiState(someday = StepData(emptyList())))
        composeRule.onNodeWithTag(ReviewTags.ITEMS_EMPTY).assertTextEquals("Nothing on Someday/Maybe.")
    }

    @Test
    fun `the decision line announces each kind of decision`() {
        val expected =
            listOf(
                ReviewDecision(ReviewDecision.Kind.STILL_WAITING, "Parcel") to "Still waiting for “Parcel”.",
                ReviewDecision(ReviewDecision.Kind.FOLLOWED_UP, "Follow up: Parcel") to "Added “Follow up: Parcel” to Next.",
                ReviewDecision(ReviewDecision.Kind.COMPLETED, "Parcel") to "Completed “Parcel”.",
                ReviewDecision(ReviewDecision.Kind.KEPT, "Fix the bike") to "Kept “Fix the bike”.",
                ReviewDecision(ReviewDecision.Kind.MOVED_TO_SOMEDAY, "Fix the bike") to "Moved “Fix the bike” to Someday.",
                ReviewDecision(ReviewDecision.Kind.DELETED, "Fix the bike") to "Moved “Fix the bike” to the trash.",
                ReviewDecision(ReviewDecision.Kind.ACTIVATED, "Visit Lisbon") to "Activated “Visit Lisbon”.",
                ReviewDecision(ReviewDecision.Kind.NEXT_STEP_ADDED, "Buy seeds", project = "Redo the garden") to
                    "Added “Buy seeds” as the next step of “Redo the garden”.",
                ReviewDecision(ReviewDecision.Kind.STATUS_CHANGED, "Redo the garden", status = ProjectStatus.ON_HOLD) to
                    "Set “Redo the garden” to On hold.",
            )
        assertEquals("every kind is covered", ReviewDecision.Kind.entries.toSet(), expected.map { it.first.kind }.toSet())
        var state by mutableStateOf(wizard(ReviewStep.WAITING_FOR, ReviewUiState(waiting = StepData(emptyList()))))
        composeRule.setContent { CockpitTheme { ReviewContent(state, callbacks, inbox = { Box(it) }) } }

        composeRule.onNodeWithTag(ReviewTags.DECISION).assertTextEquals("")
        for ((decision, text) in expected) {
            state = state.copy(decision = decision)
            composeRule.onNodeWithTag(ReviewTags.DECISION).assertTextEquals(text)
        }
    }

    @Test
    fun `projects - the title opens the project, looks good keeps it, a stalled one says so and has a next-step field`() {
        show(wizard(ReviewStep.PROJECTS, ReviewUiState(projectList = StepData(listOf(garden)))))

        shows(garden.id, "No next step")
        choice(garden.id, ReviewTags.OPEN_PROJECT)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .assertTextEquals("Redo the garden")
            .performClick()
        choice(garden.id, ReviewTags.NEXT_STEP_ADD).assertIsNotEnabled()
        choice(garden.id, ReviewTags.NEXT_STEP_FIELD).performTextInput("Buy seeds")
        choice(garden.id, ReviewTags.KEEP).assertTextEquals("Looks good").performClick()

        assertEquals(listOf("openProject:Redo the garden", "draft:Redo the garden:Buy seeds", "keepProject:Redo the garden"), decided())
    }

    @Test
    fun `projects - a typed next step is added by the button or the keyboard`() {
        show(
            wizard(
                ReviewStep.PROJECTS,
                ReviewUiState(projectList = StepData(listOf(garden)), nextStepDrafts = mapOf(garden.id to "Buy seeds")),
            ),
        )

        choice(garden.id, ReviewTags.NEXT_STEP_ADD).assertIsEnabled().performClick()
        choice(garden.id, ReviewTags.NEXT_STEP_FIELD).performImeAction()

        assertEquals(listOf("addNextStep:Redo the garden", "addNextStep:Redo the garden"), decided())
    }

    @Test
    fun `projects - a blank draft cannot be added, and a busy project disables its field and choices`() {
        var state by mutableStateOf(
            wizard(ReviewStep.PROJECTS, ReviewUiState(projectList = StepData(listOf(garden)), nextStepDrafts = mapOf(garden.id to "   "))),
        )
        composeRule.setContent { CockpitTheme { ReviewContent(state, callbacks, inbox = { Box(it) }) } }
        choice(garden.id, ReviewTags.NEXT_STEP_ADD).assertIsNotEnabled()

        state = state.copy(nextStepDrafts = mapOf(garden.id to "Buy seeds"), busy = setOf(garden.id))
        choice(garden.id, ReviewTags.NEXT_STEP_ADD).assertIsNotEnabled()
        choice(garden.id, ReviewTags.NEXT_STEP_FIELD).assertIsNotEnabled()
        choice(garden.id, ReviewTags.STATUS).assertIsNotEnabled()
        choice(garden.id, ReviewTags.KEEP).assertIsNotEnabled()
    }

    @Test
    fun `projects - the status menu offers the other statuses and saves the picked one`() {
        show(wizard(ReviewStep.PROJECTS, ReviewUiState(projectList = StepData(listOf(report)))))

        shows(report.id, "Has a next step")
        choice(report.id, ReviewTags.NEXT_STEP_FIELD).assertDoesNotExist()
        // The description replaces the visible text for TalkBack, so the current status is its state.
        choice(report.id, ReviewTags.STATUS)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Active"))
            .assertTextContains("Active", substring = true)
            .performClick()
        composeRule.onNodeWithTag(ReviewTags.status(ProjectStatus.ACTIVE)).assertIsNotEnabled()
        composeRule.onNodeWithTag(ReviewTags.status(ProjectStatus.DROPPED)).assertIsDisplayed()
        composeRule.onNodeWithTag(ReviewTags.status(ProjectStatus.ON_HOLD)).performClick()

        assertEquals(listOf("status:Annual report:ON_HOLD"), decided())
        composeRule.onNodeWithTag(ReviewTags.status(ProjectStatus.ON_HOLD)).assertDoesNotExist()
    }

    @Test
    fun `next actions - a row shows when it last changed, and keeps, moves to someday or deletes it`() {
        show(wizard(ReviewStep.NEXT_ACTIONS, ReviewUiState(staleNext = StepData(listOf(bike)))))

        shows(bike.id, "last changed")
        choice(bike.id, ReviewTags.KEEP).assertTextEquals("Keep").performClick()
        choice(bike.id, ReviewTags.TO_SOMEDAY).assertTextEquals("Move to someday").performClick()
        choice(bike.id, ReviewTags.DELETE).assertTextEquals("Delete").performClick()

        assertEquals(listOf("keepNext:Fix the bike", "toSomeday:Fix the bike", "deleteNext:Fix the bike"), decided())
    }

    @Test
    fun `someday - tasks and projects are labelled, and are activated, kept or deleted`() {
        show(wizard(ReviewStep.SOMEDAY, ReviewUiState(someday = StepData(listOf(lisbon, italian)))))

        shows(lisbon.id, "Task")
        shows(italian.id, "Project")
        choice(lisbon.id, ReviewTags.ACTIVATE).assertTextEquals("Activate").performClick()
        // The test window is small: the second row's choices may lie below the fold of the lazy list.
        composeRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasTestTag(ReviewTags.DELETE) and hasAnyAncestor(hasTestTag(ReviewTags.item(italian.id))))
        choice(italian.id, ReviewTags.KEEP).performClick()
        choice(italian.id, ReviewTags.DELETE).performClick()

        assertEquals(listOf("activate:Visit Lisbon", "keepSomeday:Learn Italian", "deleteSomeday:Learn Italian"), decided())
    }

    @Test
    fun `the action and item-changed messages are shown in the banner`() {
        var state by mutableStateOf(
            wizard(ReviewStep.SOMEDAY, ReviewUiState(someday = StepData(listOf(lisbon)), message = ReviewMessage.ACTION_FAILED)),
        )
        composeRule.setContent { CockpitTheme { ReviewContent(state, callbacks, inbox = { Box(it) }) } }
        composeRule.onNodeWithText("The server refused the change. Try again.").assertIsDisplayed()

        state = state.copy(message = ReviewMessage.ITEM_CHANGED)
        composeRule.onNodeWithText("It was changed on another device, so this step was loaded again.").assertIsDisplayed()

        state = state.copy(message = ReviewMessage.NEXT_STEP_NOT_SET)
        composeRule
            .onNodeWithText("The next action was added to the project, but not set as its next step. Open the project to set it.")
            .assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German the steps, choices and decisions are translated`() {
        show(
            wizard(
                ReviewStep.WAITING_FOR,
                ReviewUiState(waiting = StepData(listOf(parcel)), decision = ReviewDecision(ReviewDecision.Kind.COMPLETED, "Paket")),
            ),
        )

        choice(parcel.id, ReviewTags.KEEP).assertTextEquals("Warte noch")
        choice(parcel.id, ReviewTags.DONE).assertTextEquals("Erledigt")
        composeRule.onNodeWithTag(ReviewTags.DECISION).assertTextEquals("„Paket“ erledigt.")
        shows(parcel.id, "auf Erika · seit")
    }

    @Test
    fun `every review string has a German translation and no German string is orphaned`() {
        val english = names(File("src/main/res/values/strings_review.xml"))
        val german = names(File("src/main/res/values-de/strings_review.xml"))
        val keyList =
            """
            review_waiting_for_whom review_waiting_since review_keep_waiting review_waiting_done review_waiting_empty
            review_has_next_step review_open_project review_next_step review_next_step_for review_next_step_add
            review_project_status review_looks_good review_projects_empty review_last_changed review_keep
            review_to_someday review_delete review_next_empty review_kind_project review_kind_task review_activate
            review_someday_empty review_action_failed review_item_changed review_next_step_not_set review_decided_waiting
            review_decided_followed_up review_decided_completed review_decided_kept review_decided_someday
            review_decided_deleted review_decided_activated review_decided_next_step review_decided_status
            """
        val newKeys = keyList.trim().split(Regex("\\s+"))
        assertEquals(emptyList<String>(), newKeys.filterNot { it in english })
        assertEquals(english, german)
    }

    /** The names of the strings and plurals in a resource file. */
    private fun names(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return listOf("string", "plurals")
            .flatMap { tag ->
                val nodes = doc.getElementsByTagName(tag)
                (0 until nodes.length).map { index -> name(nodes.item(index)) }
            }.toSet()
    }

    private fun name(node: Node): String = (node as Element).getAttribute("name")
}
