package ch.sonensei.cockpit.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.habits.Habit
import ch.sonensei.cockpit.habits.HabitSchedule
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Wizard states the main wizard test leaves out: the list destinations, empty and failed steps, guards, banners. */
@RunWith(AndroidJUnit4::class)
class ReviewWizardEdgeCasesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val callbacks =
        ReviewCallbacks(
            retry = { calls += "retry" },
            start = { calls += "start" },
            goTo = { calls += "goTo:${it.name}" },
            next = { calls += "next" },
            previous = { calls += "previous" },
            complete = { calls += "complete" },
            capture = { calls += "capture" },
            dismissMessage = { calls += "dismiss" },
            open = { calls += "open:${it.name}" },
        )

    private fun wizard(
        step: ReviewStep,
        state: ReviewUiState = ReviewUiState(),
    ) = state.copy(
        overview = ReviewOverview(OpenReview(UUID.fromString("00000000-0000-7000-8000-000000000029"), step, Instant.EPOCH), 6, emptyList()),
        wizard = true,
    )

    private fun show(state: ReviewUiState) = composeRule.setContent { CockpitTheme { ReviewContent(state, callbacks) } }

    private fun habit(
        name: String,
        streak: Int,
        weeks: Boolean,
        rate: Double?,
    ) = Habit(
        UUID.fromString("00000000-0000-7000-8000-000000000031"),
        name,
        if (weeks) HabitSchedule.TIMES_PER_WEEK else HabitSchedule.DAILY,
        if (weeks) 3 else null,
        emptyList(),
        1,
        LocalDate.of(2026, 11, 8),
        0,
        true,
        false,
        streak,
        weeks,
        rate,
    )

    @Test
    fun `an empty past calendar says so`() {
        show(wizard(ReviewStep.PAST_CALENDAR, ReviewUiState(pastEvents = StepData(emptyList()))))
        composeRule.onNodeWithTag(ReviewTags.EVENTS_EMPTY).assertTextEquals("No events in the last 7 days.")
        composeRule.onNodeWithTag(ReviewTags.STEP_FAILED).assertDoesNotExist()
    }

    @Test
    fun `an empty upcoming calendar says so`() {
        show(wizard(ReviewStep.UPCOMING_CALENDAR, ReviewUiState(upcomingEvents = StepData(emptyList()))))
        composeRule.onNodeWithTag(ReviewTags.EVENTS_EMPTY).assertTextEquals("No events in the next 14 days.")
    }

    @Test
    fun `a failed past calendar retries the past step, not the upcoming one`() {
        val loads = mutableListOf<ReviewStep>()
        composeRule.setContent {
            CockpitTheme {
                ReviewContent(
                    wizard(ReviewStep.PAST_CALENDAR, ReviewUiState(pastEvents = StepData(failed = true))),
                    callbacks.copy(loadStep = { loads += it }),
                )
            }
        }
        composeRule.onNodeWithTag(ReviewTags.STEP_FAILED).assertExists()
        composeRule.onNodeWithTag(ReviewTags.STEP_RETRY).performClick()
        // The first load is the step being shown, the second the retry.
        assertEquals(listOf(ReviewStep.PAST_CALENDAR, ReviewStep.PAST_CALENDAR), loads)
    }

    @Test
    fun `a calendar still loading shows neither empty nor failed`() {
        show(wizard(ReviewStep.PAST_CALENDAR, ReviewUiState(pastEvents = StepData(loading = true))))
        composeRule.onNodeWithTag(ReviewTags.EVENTS_EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(ReviewTags.STEP_FAILED).assertDoesNotExist()
        composeRule.onNodeWithTag(ReviewTags.CAPTURE_FIELD).assertExists()
    }

    @Test
    fun `a failed reload keeps the events it showed, without the failure row`() {
        val event = ReviewEvent("e9", "Team lunch", LocalDate.of(2026, 11, 4), null)
        show(wizard(ReviewStep.PAST_CALENDAR, ReviewUiState(pastEvents = StepData(listOf(event), failed = true))))
        composeRule.onNodeWithTag(ReviewTags.event(event)).assertExists()
        composeRule.onNodeWithTag(ReviewTags.STEP_FAILED).assertDoesNotExist()
    }

    @Test
    fun `the quick-add button waits for text, and the keyboard's done captures`() {
        var text by mutableStateOf("")
        composeRule.setContent {
            CockpitTheme { ReviewContent(wizard(ReviewStep.PAST_CALENDAR, ReviewUiState(captureText = text)), callbacks) }
        }
        composeRule.onNodeWithTag(ReviewTags.CAPTURE_ADD).assertIsNotEnabled()
        text = "  "
        composeRule.onNodeWithTag(ReviewTags.CAPTURE_ADD).assertIsNotEnabled()
        text = "Renew passport"
        composeRule.onNodeWithTag(ReviewTags.CAPTURE_ADD).assertIsEnabled()
        composeRule.onNodeWithTag(ReviewTags.CAPTURE_FIELD).performImeAction()
        assertEquals(listOf("capture"), calls)
        composeRule.onNodeWithTag(ReviewTags.CAPTURED).assertDoesNotExist()
    }

    @Test
    fun `habits that could not be loaded say so`() {
        show(wizard(ReviewStep.REFLECTION, ReviewUiState(habits = StepData(failed = true))))
        composeRule.onNodeWithTag(ReviewTags.HABITS_FAILED).assertTextEquals("The habits could not be loaded.")
        composeRule.onNodeWithTag(ReviewTags.REFLECTION).assertExists()
    }

    @Test
    fun `without habits it says so and the reflection can still be written`() {
        show(wizard(ReviewStep.REFLECTION, ReviewUiState(habits = StepData(emptyList()))))
        composeRule.onNodeWithTag(ReviewTags.NO_HABITS).assertTextEquals("No habits yet.")
        composeRule.onNodeWithTag(ReviewTags.REFLECTION).assertExists()
    }

    @Test
    fun `a weekly habit without due days shows only its streak in weeks`() {
        val weekly = habit("Swim", 1, weeks = true, rate = null)
        show(wizard(ReviewStep.REFLECTION, ReviewUiState(habits = StepData(listOf(weekly)))))
        composeRule.onNodeWithTag(ReviewTags.habit(weekly.id)).assertTextContains("streak 1 week")
        composeRule.onNodeWithText("in 30 days", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the rate is rounded to a whole percent`() {
        val daily = habit("Stretch", 2, weeks = false, rate = 0.666)
        show(wizard(ReviewStep.REFLECTION, ReviewUiState(habits = StepData(listOf(daily)))))
        composeRule.onNodeWithTag(ReviewTags.habit(daily.id)).assertTextContains("67% in 30 days, streak 2 days")
    }

    @Test
    fun `while a change is saved, complete, the step menu and the track wait`() {
        show(wizard(ReviewStep.REFLECTION, ReviewUiState(saving = true)))
        composeRule.onNodeWithTag(ReviewTags.FINISH).assertIsNotEnabled()
        composeRule.onNodeWithTag(ReviewTags.PREVIOUS).assertIsNotEnabled()
        composeRule.onNodeWithTag(ReviewTags.JUMP).assertIsNotEnabled()
        composeRule.onNodeWithTag(ReviewTags.segment(ReviewStep.GET_CLEAR)).assertIsNotEnabled()
        composeRule.onNodeWithTag(ReviewTags.FINISH).performClick()
        assertFalse("complete" in calls)
    }

    @Test
    fun `the current step can't be jumped to, from the track or the menu`() {
        show(wizard(ReviewStep.PROJECTS))
        composeRule.onNodeWithTag(ReviewTags.segment(ReviewStep.PROJECTS)).assertIsNotEnabled()
        composeRule.onNodeWithTag(ReviewTags.JUMP).performClick()
        composeRule.onNodeWithTag(ReviewTags.jumpTo(ReviewStep.PROJECTS)).assertIsNotEnabled()
        composeRule.onNodeWithTag(ReviewTags.jumpTo(ReviewStep.GET_CLEAR)).assertIsEnabled()
        composeRule.onNodeWithTag(ReviewTags.jumpTo(ReviewStep.REFLECTION)).performClick()
        assertEquals(listOf("goTo:REFLECTION"), calls)
        // The menu closes after a jump.
        composeRule.onNodeWithTag(ReviewTags.jumpTo(ReviewStep.GET_CLEAR)).assertDoesNotExist()
    }

    @Test
    fun `save, conflict, reflection and capture messages can be dismissed, not retried`() {
        var message by mutableStateOf(ReviewMessage.SAVE_OFFLINE)
        composeRule.setContent { CockpitTheme { ReviewContent(wizard(ReviewStep.GET_CLEAR, ReviewUiState(message = message)), callbacks) } }
        val texts =
            mapOf(
                ReviewMessage.SAVE_OFFLINE to "Not saved: can't reach the server. Try again when you're online.",
                ReviewMessage.SAVE_FAILED to "The server refused the change. Load the review again and retry.",
                ReviewMessage.CONFLICT to "The review was changed on another device. This is its current state.",
                ReviewMessage.REFLECTION_FAILED to "The reflection could not be saved in the journal, so the review is still open.",
                ReviewMessage.CAPTURE_FAILED to "That text can't be added to the inbox. Shorten it and try again.",
            )
        texts.forEach { (m, text) ->
            message = m
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(ReviewTags.MESSAGE).assertExists()
            composeRule.onNodeWithText(text).assertExists()
            composeRule.onNodeWithTag(ReviewTags.RETRY).assertDoesNotExist()
            composeRule.onNodeWithTag(ReviewTags.DISMISS).performClick()
        }
        assertEquals(List(texts.size) { "dismiss" }, calls)
    }

    @Test
    fun `the banner shows above the wizard, which stays usable`() {
        show(wizard(ReviewStep.WAITING_FOR, ReviewUiState(message = ReviewMessage.CONFLICT)))
        composeRule.onNodeWithTag(ReviewTags.MESSAGE).assertExists()
        composeRule.onNodeWithTag(ReviewTags.NEXT).performClick()
        assertEquals(listOf("next"), calls)
    }

    @Test
    fun `the continue button waits while a start is saved`() {
        show(ReviewUiState(overview = ReviewOverview(null, null, emptyList()), saving = true))
        composeRule.onNodeWithTag(ReviewTags.START).assertIsNotEnabled()
    }

    @Test
    fun `an open review outside the wizard shows the overview with continue`() {
        show(wizard(ReviewStep.SOMEDAY).copy(wizard = false))
        composeRule.onNodeWithTag(ReviewTags.WIZARD).assertDoesNotExist()
        composeRule.onNodeWithTag(ReviewTags.START).assertTextEquals("Continue review").performClick()
        assertEquals(listOf("start"), calls)
    }

    @Test
    fun `the wizard flag without an open review shows the overview`() {
        show(ReviewUiState(overview = ReviewOverview(null, 2, emptyList()), wizard = true))
        composeRule.onNodeWithTag(ReviewTags.WIZARD).assertDoesNotExist()
        composeRule.onNodeWithTag(ReviewTags.CONTENT).assertExists()
        assertEquals(1, composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }
}
