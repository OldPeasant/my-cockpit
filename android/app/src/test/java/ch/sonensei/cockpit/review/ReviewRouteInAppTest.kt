package ch.sonensei.cockpit.review

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
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
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * The Review destination in the Hilt activity with a fake repository: it is the real read-only review (not the
 * placeholder), loads when shown, and its retry loads again after a failure.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ReviewRouteInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val repo: ReviewRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private val stalled = ReviewProject(UUID.fromString("00000000-0000-7000-8000-000000000001"), "Plan the garden", true)
    private val overview =
        ReviewOverview(
            current = OpenReview(UUID.fromString("00000000-0000-7000-8000-000000000009"), ReviewStep.PROJECTS, Instant.EPOCH),
            daysSinceLastReview = 3,
            projects = listOf(stalled),
        )

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun waitForTag(tag: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun openReview() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Review"))
        composeRule.onNodeWithText("Review").performClick()
    }

    @Test
    fun `the Review screen is the real progress view, not the placeholder`() {
        coEvery { repo.overview() } returns ReviewResult.Ok(overview)
        openReview()
        waitForTag(ReviewTags.CONTENT)

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Review)).assertIsDisplayed()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertDoesNotExist()
        composeRule.onNodeWithTag(ReviewTags.PROGRESS).assertTextEquals("Review in progress: step 5 of 8")
        composeRule.onNodeWithTag(ReviewTags.CONTENT).performScrollToNode(hasTestTag(ReviewTags.step(ReviewStep.PROJECTS)))
        composeRule.onNodeWithTag(ReviewTags.step(ReviewStep.PROJECTS)).assertContentDescriptionEquals("5. Projects, current step")
        composeRule.onNodeWithTag(ReviewTags.CONTENT).performScrollToNode(hasTestTag(ReviewTags.project(stalled)))
        composeRule.onNodeWithTag(ReviewTags.project(stalled)).assertExists()
        coVerify(exactly = 1) { repo.overview() }
    }

    @Test
    fun `after a failed first load, retry loads again and shows the review`() {
        coEvery { repo.overview() } returns ReviewResult.Offline andThen ReviewResult.Ok(overview)
        openReview()
        waitForTag(ReviewTags.RETRY)
        composeRule.onNodeWithTag(ReviewTags.CONTENT).assertDoesNotExist()

        composeRule.onNodeWithTag(ReviewTags.RETRY).performClick()
        waitForTag(ReviewTags.CONTENT)
        composeRule.onNodeWithTag(ReviewTags.MESSAGE).assertDoesNotExist()
        coVerify(exactly = 2) { repo.overview() }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
