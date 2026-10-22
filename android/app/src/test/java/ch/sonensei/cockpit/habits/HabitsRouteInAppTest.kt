package ch.sonensei.cockpit.habits

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/** Inside the Hilt activity the Habits destination is the real check-off list, wired to its view model. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class HabitsRouteInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val repo: HabitsRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private val water =
        Habit(
            UUID.fromString("00000000-0000-7000-8000-000000000001"),
            "Drink water",
            HabitSchedule.DAILY,
            null,
            emptyList(),
            1,
            LocalDate.of(2026, 10, 22),
            0,
            true,
            false,
            2,
            false,
            0.5,
        )

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { repo.list() } returns HabitsResult.Ok(listOf(water))
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun `the Habits screen is the real list and a tap checks a habit off for today`() {
        coEvery { repo.check(water, done = true) } returns
            HabitsResult.Ok(water.copy(todayCount = 1, doneToday = true, currentStreak = 3))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Habits"))
        composeRule.onNodeWithText("Habits").performClick()
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithTag(HabitsTags.habit(water.id)).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Habits)).assertIsDisplayed()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertDoesNotExist()
        composeRule.onNodeWithTag(HabitsTags.habit(water.id)).assertIsOff().performClick()

        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(hasText("3-day streak", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(HabitsTags.habit(water.id)).assertIsOn()
        coVerify(exactly = 1) { repo.check(water, done = true) }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
