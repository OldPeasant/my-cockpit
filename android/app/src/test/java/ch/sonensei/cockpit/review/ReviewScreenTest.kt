package ch.sonensei.cockpit.review

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** The review screen alone, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class ReviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val moveFlat = ReviewProject(id(1), "Move flat", stalled = true)
    private val taxReturn = ReviewProject(id(2), "Tax return", stalled = false)
    private val atWaitingFor = OpenReview(id(9), ReviewStep.WAITING_FOR, Instant.parse("2026-10-20T18:00:00Z"))
    private var retries = 0

    private fun show(state: ReviewUiState) = composeRule.setContent { CockpitTheme { ReviewContent(state, onRetry = { retries++ }) } }

    private fun overview(
        current: OpenReview? = atWaitingFor,
        days: Long? = 6,
        projects: List<ReviewProject> = listOf(moveFlat, taxReturn),
    ) = ReviewUiState(overview = ReviewOverview(current, days, projects))

    private fun step(step: ReviewStep) = composeRule.onNodeWithTag(ReviewTags.step(step))

    private fun scrollTo(tag: String) = composeRule.onNodeWithTag(ReviewTags.CONTENT).performScrollToNode(hasTestTag(tag))

    @Test
    fun `all eight steps show in order, the ones before the current step done`() {
        show(overview())
        composeRule.onNodeWithTag(ReviewTags.PROGRESS).assertTextEquals("Review in progress: step 4 of 8")
        composeRule.onNodeWithTag(ReviewTags.LAST).assertTextEquals("Last weekly review: 6 days ago")
        step(ReviewStep.GET_CLEAR).assertContentDescriptionEquals("1. Get clear, done")
        step(ReviewStep.UPCOMING_CALENDAR).assertContentDescriptionEquals("3. Review upcoming calendar, done")
        step(ReviewStep.WAITING_FOR).assertContentDescriptionEquals("4. Waiting For, current step")
        step(ReviewStep.PROJECTS).assertContentDescriptionEquals("5. Projects")
        scrollTo(ReviewTags.step(ReviewStep.REFLECTION))
        step(ReviewStep.REFLECTION).assertContentDescriptionEquals("8. Habits & journal reflection")
    }

    @Test
    fun `without an open review no step is done or current`() {
        show(overview(current = null, days = null))
        composeRule.onNodeWithTag(ReviewTags.PROGRESS).assertTextEquals("No review in progress. Start one on the web.")
        composeRule.onNodeWithTag(ReviewTags.LAST).assertTextEquals("No weekly review completed yet.")
        ReviewStep.entries.forEach {
            scrollTo(ReviewTags.step(it))
            step(it).assertContentDescriptionEquals("${it.number}. " + label(it))
        }
    }

    @Test
    fun `the last review reads as today or one day ago`() {
        show(overview(days = 0))
        composeRule.onNodeWithTag(ReviewTags.LAST).assertTextEquals("Last weekly review: today")
    }

    @Test
    fun `one day ago is singular`() {
        show(overview(days = 1))
        composeRule.onNodeWithTag(ReviewTags.LAST).assertTextEquals("Last weekly review: 1 day ago")
    }

    @Test
    fun `only stalled projects are listed`() {
        show(overview())
        scrollTo(ReviewTags.project(moveFlat))
        composeRule.onNodeWithTag(ReviewTags.project(moveFlat)).assertIsDisplayed()
        composeRule.onNodeWithTag(ReviewTags.project(taxReturn)).assertDoesNotExist()
        composeRule.onNodeWithTag(ReviewTags.NO_STALLED).assertDoesNotExist()
    }

    @Test
    fun `without stalled projects it says every project has a next step`() {
        show(overview(projects = listOf(taxReturn)))
        scrollTo(ReviewTags.NO_STALLED)
        composeRule.onNodeWithTag(ReviewTags.NO_STALLED).assertTextEquals("Every active project has a next step.")
    }

    @Test
    fun `the screen is read only, nothing on it can be tapped`() {
        show(overview())
        assertEquals(0, composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `a failed load shows the message with retry, and keeps what was shown`() {
        show(overview().copy(message = ReviewMessage.OFFLINE))
        composeRule.onNodeWithTag(ReviewTags.MESSAGE).assertIsDisplayed()
        step(ReviewStep.GET_CLEAR).assertIsDisplayed()
        composeRule.onNodeWithTag(ReviewTags.RETRY).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `retry is disabled while loading`() {
        show(ReviewUiState(loading = true, message = ReviewMessage.FAILED))
        composeRule.onNodeWithTag(ReviewTags.RETRY).assertIsNotEnabled()
        assertEquals(0, composeRule.onAllNodesWithTag(ReviewTags.CONTENT).fetchSemanticsNodes().size)
    }

    private fun label(step: ReviewStep) =
        when (step) {
            ReviewStep.GET_CLEAR -> "Get clear"
            ReviewStep.PAST_CALENDAR -> "Review past calendar"
            ReviewStep.UPCOMING_CALENDAR -> "Review upcoming calendar"
            ReviewStep.WAITING_FOR -> "Waiting For"
            ReviewStep.PROJECTS -> "Projects"
            ReviewStep.NEXT_ACTIONS -> "Next Actions"
            ReviewStep.SOMEDAY -> "Someday/Maybe"
            ReviewStep.REFLECTION -> "Habits & journal reflection"
        }
}
