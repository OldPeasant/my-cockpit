package ch.sonensei.cockpit.review

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.habits.Habit
import ch.sonensei.cockpit.habits.HabitSchedule
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** The wizard of an open review, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class ReviewWizardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val callbacks =
        ReviewCallbacks(
            start = { calls += "start" },
            close = { calls += "close" },
            goTo = { calls += "goTo:${it.name}" },
            next = { calls += "next" },
            previous = { calls += "previous" },
            complete = { calls += "complete" },
            loadStep = { calls += "load:${it.name}" },
            setCaptureText = { calls += "text:$it" },
            capture = { calls += "capture" },
            setReflection = { calls += "reflection:$it" },
            open = { calls += "open:${it.name}" },
        )

    private fun wizard(
        step: ReviewStep,
        state: ReviewUiState = ReviewUiState(),
    ) = state.copy(
        overview = ReviewOverview(OpenReview(UUID.fromString("00000000-0000-7000-8000-000000000009"), step, Instant.EPOCH), 6, emptyList()),
        wizard = true,
    )

    private fun show(state: ReviewUiState) =
        composeRule.setContent {
            CockpitTheme {
                ReviewContent(state, callbacks, inbox = { Box(it) { Text("Fake inbox", Modifier.testTag("fake-inbox")) } })
            }
        }

    @Test
    fun `the header shows the step and the track marks done, current and ahead`() {
        show(wizard(ReviewStep.UPCOMING_CALENDAR))
        composeRule.onNodeWithText("Step 3 of 8").assertIsDisplayed()
        composeRule.onNodeWithTag(ReviewTags.STEP_TITLE, useUnmergedTree = true).assertTextEquals("Review upcoming calendar")
        composeRule.onNodeWithTag(ReviewTags.segment(ReviewStep.GET_CLEAR)).assertContentDescriptionEquals("1. Get clear, done")
        composeRule
            .onNodeWithTag(ReviewTags.segment(ReviewStep.UPCOMING_CALENDAR))
            .assertContentDescriptionEquals("3. Review upcoming calendar, current step")
        composeRule.onNodeWithTag(ReviewTags.segment(ReviewStep.PROJECTS)).assertContentDescriptionEquals("5. Projects")
        assertEquals(listOf("load:UPCOMING_CALENDAR"), calls.filter { it.startsWith("load") })
    }

    @Test
    fun `next, previous, a track segment and the step menu move the review`() {
        show(wizard(ReviewStep.PROJECTS))
        composeRule.onNodeWithTag(ReviewTags.NEXT).performClick()
        composeRule.onNodeWithTag(ReviewTags.PREVIOUS).performClick()
        composeRule.onNodeWithTag(ReviewTags.segment(ReviewStep.GET_CLEAR)).performClick()
        composeRule.onNodeWithTag(ReviewTags.JUMP).performClick()
        composeRule.onNodeWithTag(ReviewTags.jumpTo(ReviewStep.SOMEDAY)).performClick()
        composeRule.onNodeWithTag(ReviewTags.CLOSE).performClick()
        assertEquals(listOf("next", "previous", "goTo:GET_CLEAR", "goTo:SOMEDAY", "close"), calls.filterNot { it.startsWith("load") })
    }

    @Test
    fun `the first step has no previous, and the buttons wait while a move is saved`() {
        show(wizard(ReviewStep.GET_CLEAR, ReviewUiState(saving = true)))
        composeRule.onNodeWithTag(ReviewTags.PREVIOUS).assertIsNotEnabled()
        composeRule.onNodeWithTag(ReviewTags.NEXT).assertIsNotEnabled()
    }

    @Test
    fun `get clear embeds the inbox and links to Mail`() {
        show(wizard(ReviewStep.GET_CLEAR))
        composeRule.onNodeWithTag("fake-inbox").assertIsDisplayed()
        composeRule.onNodeWithTag(ReviewTags.OPEN_MAIL).performClick()
        assertEquals("open:Mail", calls.last())
    }

    @Test
    fun `the calendar step lists the events by day and captures into the inbox`() {
        val dentist = ReviewEvent("e1", "Dentist", LocalDate.of(2026, 11, 3), LocalTime.of(9, 30))
        val holiday = ReviewEvent("e2", "Holiday", LocalDate.of(2026, 11, 3), null)
        show(
            wizard(
                ReviewStep.PAST_CALENDAR,
                ReviewUiState(pastEvents = StepData(listOf(dentist, holiday)), captureText = "Send invoice", captured = "Book flights"),
            ),
        )
        composeRule.onNodeWithTag(ReviewTags.event(dentist)).assertTextContains("Dentist", substring = true)
        // The test window is small: the second event may lie below the fold of the lazy list.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(ReviewTags.event(holiday)))
        composeRule.onNodeWithTag(ReviewTags.event(holiday)).assertTextContains("All day", substring = true)
        composeRule.onNodeWithTag(ReviewTags.CAPTURED).assertTextEquals("Added “Book flights” to the inbox.")
        composeRule.onNodeWithTag(ReviewTags.CAPTURE_FIELD).performTextInput("!")
        composeRule.onNodeWithTag(ReviewTags.CAPTURE_ADD).performClick()
        assertEquals(listOf("text:!Send invoice", "capture"), calls.filter { it.startsWith("text") || it == "capture" })
    }

    @Test
    fun `the quick-add button is disabled while a capture is stored`() {
        show(wizard(ReviewStep.PAST_CALENDAR, ReviewUiState(captureText = "Send invoice", capturing = true)))
        composeRule.onNodeWithTag(ReviewTags.CAPTURE_ADD).assertIsNotEnabled()
    }

    @Test
    fun `an empty or failed calendar says so, with retry`() {
        show(wizard(ReviewStep.UPCOMING_CALENDAR, ReviewUiState(upcomingEvents = StepData(failed = true))))
        composeRule.onNodeWithTag(ReviewTags.STEP_FAILED).assertIsDisplayed()
        composeRule.onNodeWithTag(ReviewTags.STEP_RETRY).performClick()
        assertEquals(listOf("load:UPCOMING_CALENDAR", "load:UPCOMING_CALENDAR"), calls)
    }

    @Test
    fun `the reflection step shows the habit stats, the text, and completes instead of next`() {
        val habit =
            Habit(
                UUID.fromString("00000000-0000-7000-8000-000000000003"),
                "Read",
                HabitSchedule.DAILY,
                null,
                emptyList(),
                1,
                LocalDate.of(2026, 11, 8),
                0,
                true,
                false,
                4,
                false,
                0.8,
            )
        show(wizard(ReviewStep.REFLECTION, ReviewUiState(habits = StepData(listOf(habit)), reflection = "Calm")))
        composeRule.onNodeWithTag(ReviewTags.habit(habit.id)).assertTextContains("80% in 30 days, streak 4 days", substring = true)
        composeRule.onNodeWithTag(ReviewTags.NEXT).assertDoesNotExist()
        composeRule.onNodeWithTag(ReviewTags.REFLECTION).performTextClearance()
        composeRule.onNodeWithTag(ReviewTags.FINISH).performClick()
        assertEquals(listOf("reflection:", "complete"), calls.filter { it.startsWith("reflection") || it == "complete" })
    }

    @Test
    fun `after completing, the overview says so and offers a new review`() {
        show(ReviewUiState(overview = ReviewOverview(null, 0, emptyList()), completed = true))
        composeRule.onNodeWithTag(ReviewTags.COMPLETED).assertTextEquals("Weekly review completed.")
        composeRule.onNodeWithTag(ReviewTags.START).assertTextEquals("Start review").performClick()
        assertEquals(listOf("start"), calls)
    }
}
