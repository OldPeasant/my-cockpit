package ch.sonensei.cockpit.habits

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

/** The habit list alone, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class HabitsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 22)
    private val water = Habit(id(1), "Drink water", HabitSchedule.DAILY, null, emptyList(), 3, today, 1, true, false, 4, false, 0.75)
    private val run = Habit(id(2), "Run", HabitSchedule.TIMES_PER_WEEK, 3, emptyList(), 1, today, 1, true, true, 2, true, 1.0)
    private val piano =
        Habit(
            id(3),
            "Piano",
            HabitSchedule.WEEKDAYS,
            null,
            listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            1,
            today,
            0,
            false,
            false,
            0,
            false,
            null,
        )
    private val calls = mutableListOf<String>()

    private fun show(state: HabitsUiState) =
        composeRule.setContent {
            CockpitTheme {
                HabitsContent(
                    state,
                    HabitsCallbacks(toggle = { calls += "toggle $it" }, reload = { calls += "reload" }, dismissMessage = {
                        calls +=
                            "dismiss"
                    }),
                )
            }
        }

    @Test
    fun `habits due today show their state, streak and 30-day rate`() {
        show(HabitsUiState(habits = listOf(water, run, piano), loading = false, loaded = true))

        composeRule.onNodeWithText("Due today").assertIsDisplayed()
        composeRule.onNodeWithText("Not due today").assertIsDisplayed()
        composeRule
            .onNodeWithTag(HabitsTags.habit(water.id))
            .assertIsOff()
            .assertTextContains("Daily, today 1/3, 4-day streak, 75% of the last 30 days", substring = true)
        composeRule
            .onNodeWithTag(HabitsTags.habit(run.id))
            .assertIsOn()
            .assertTextContains("3× a week, 2-week streak, 100% of the last 30 days", substring = true)
        composeRule.onNodeWithTag(HabitsTags.habit(piano.id)).assertTextContains("Mon Fri", substring = true)
    }

    @Test
    fun `a tap checks a habit off`() {
        show(HabitsUiState(habits = listOf(water, run), loading = false, loaded = true))

        composeRule.onNodeWithTag(HabitsTags.habit(water.id)).performClick()
        composeRule.onNodeWithTag(HabitsTags.habit(run.id)).performClick()

        assertEquals(listOf("toggle ${water.id}", "toggle ${run.id}"), calls)
    }

    @Test
    fun `a habit being checked off cannot be tapped again`() {
        show(HabitsUiState(habits = listOf(water), loading = false, loaded = true, checking = setOf(water.id)))

        composeRule.onNodeWithTag(HabitsTags.habit(water.id)).assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a done habit with a target above one shows no partial progress`() {
        show(HabitsUiState(habits = listOf(water.copy(todayCount = 3, doneToday = true)), loading = false, loaded = true))

        composeRule
            .onNodeWithTag(HabitsTags.habit(water.id))
            .assertIsOn()
            .assertTextContains("Daily, 4-day streak, 75% of the last 30 days", substring = true)
    }

    @Test
    fun `while loading for the first time only the progress bar shows`() {
        show(HabitsUiState())

        composeRule.onNodeWithTag(HabitsTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithTag(HabitsTags.LIST).assertDoesNotExist()
        composeRule.onNodeWithTag(HabitsTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `without habits the screen says where to add them`() {
        show(HabitsUiState(loading = false, loaded = true))

        composeRule.onNodeWithTag(HabitsTags.EMPTY).assertTextContains("No habits yet", substring = true)
    }

    @Test
    fun `a failed load offers a retry`() {
        show(HabitsUiState(loading = false, message = HabitsMessage.LOAD_OFFLINE))
        composeRule.onNodeWithTag(HabitsTags.RETRY).performClick()
        assertEquals(listOf("reload"), calls)
    }

    @Test
    fun `a failed check-off can be dismissed`() {
        show(HabitsUiState(habits = listOf(water), loading = false, loaded = true, message = HabitsMessage.CHECK_OFFLINE))
        composeRule.onNodeWithText("Not checked off: no connection.").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), calls)
    }
}
