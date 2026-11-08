package ch.sonensei.cockpit.review

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
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
import ch.sonensei.cockpit.gtd.inbox.InboxRepository
import ch.sonensei.cockpit.gtd.projects.ProjectOpenRequests
import ch.sonensei.cockpit.mail.list.MailRepository
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
 * The review wizard in the Hilt activity with a fake repository: start opens the wizard, system back returns to the
 * overview without leaving the screen (the review stays open), and the Mail link and a project of the projects step navigate in the shell.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ReviewWizardRouteInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val repo: ReviewRepository = mockk()

    @BindValue
    @JvmField
    val actions: ReviewActions = mockk()

    @BindValue
    @JvmField
    val inbox: InboxRepository = mockk(relaxed = true)

    @BindValue
    @JvmField
    val mail: MailRepository = mockk(relaxed = true)

    @Inject lateinit var tokenStore: PersistentTokenStore

    private val reviewId = UUID.fromString("00000000-0000-7000-8000-000000000039")

    private fun open(step: ReviewStep) = OpenReview(reviewId, step, Instant.EPOCH, 1)

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { repo.events(any()) } returns ReviewResult.Ok(emptyList())
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun waitForTag(tag: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun systemBack() = scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun openReview() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Review"))
        composeRule.onNodeWithText("Review").performClick()
        waitForTag(ReviewTags.START)
    }

    @Test
    fun `start opens the wizard, and system back returns to the overview with the review still open`() {
        coEvery { repo.overview() } returns ReviewResult.Ok(ReviewOverview(null, 9, emptyList()))
        coEvery { repo.start() } returns ReviewResult.Ok(open(ReviewStep.PAST_CALENDAR))
        openReview()
        composeRule.onNodeWithTag(ReviewTags.START).assertTextEquals("Start review").performClick()
        waitForTag(ReviewTags.WIZARD)
        composeRule.onNodeWithTag(ReviewTags.STEP_TITLE, useUnmergedTree = true).assertTextEquals("Review past calendar")

        systemBack()
        waitForTag(ReviewTags.CONTENT)
        composeRule.onNodeWithTag(ReviewTags.WIZARD).assertDoesNotExist()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Review)).assertExists()
        composeRule.onNodeWithTag(ReviewTags.PROGRESS).assertTextEquals("Review in progress: step 2 of 8")
        composeRule.onNodeWithTag(ReviewTags.START).assertTextEquals("Continue review").performClick()
        waitForTag(ReviewTags.WIZARD)
        coVerify(exactly = 1) { repo.start() }
        coVerify(exactly = 0) { repo.complete(any()) }
    }

    @Test
    fun `continuing at get clear embeds the real inbox screen and links to Mail`() {
        coEvery { repo.overview() } returns ReviewResult.Ok(ReviewOverview(open(ReviewStep.GET_CLEAR), 9, emptyList()))
        openReview()
        composeRule.onNodeWithTag(ReviewTags.START).assertTextEquals("Continue review").performClick()
        waitForTag(ReviewTags.INBOX)
        // The inbox screen (InboxRoute with its own view model) renders inside the step, not a placeholder.
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(hasAnyAncestor(hasTestTag(ReviewTags.INBOX))).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertDoesNotExist()
        composeRule.onNodeWithTag(ReviewTags.OPEN_MAIL).assertTextEquals("Open Mail")
        coVerify(exactly = 0) { repo.start() }
    }

    @Test
    fun `the Mail link of get clear opens Mail, and back returns to the wizard`() {
        coEvery { repo.overview() } returns ReviewResult.Ok(ReviewOverview(open(ReviewStep.GET_CLEAR), 9, emptyList()))
        openReview()
        composeRule.onNodeWithTag(ReviewTags.START).performClick()
        waitForTag(ReviewTags.OPEN_MAIL)
        composeRule.onNodeWithTag(ReviewTags.INBOX).assertExists()

        composeRule.onNodeWithTag(ReviewTags.OPEN_MAIL).performClick()
        waitForTag(screenTag(CockpitDestination.Mail))
        composeRule.onNodeWithTag(ReviewTags.WIZARD).assertDoesNotExist()

        // PARITY.md decision 6 ("back returns to where the user came from") and the step's own hint ("Turn them into
        // tasks in Mail, then come back"): back from Mail returns to the review, not to the Dashboard.
        systemBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertDoesNotExist()
        waitForTag(ReviewTags.WIZARD)
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Review)).assertExists()
    }

    @Test
    fun `a project of the projects step opens its detail in Projects, and back returns to the wizard`() {
        val project = ReviewProject(UUID.fromString("00000000-0000-7000-8000-000000000040"), "Fictitious garden", stalled = true)
        coEvery { repo.overview() } returns ReviewResult.Ok(ReviewOverview(open(ReviewStep.PROJECTS), 9, emptyList()))
        coEvery { actions.projects() } returns ReviewResult.Ok(listOf(project))
        openReview()
        composeRule.onNodeWithTag(ReviewTags.START).performClick()
        waitForTag(ReviewTags.OPEN_PROJECT)

        composeRule.onNodeWithTag(ReviewTags.OPEN_PROJECT).assertTextEquals("Fictitious garden").performClick()
        waitForTag(screenTag(CockpitDestination.Projects))
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Review)).assertDoesNotExist()
        // The Projects screen took the request, so it shows that project's detail.
        composeRule.waitUntil(WAIT_MS) { ProjectOpenRequests.requests.value == null }

        systemBack()
        waitForTag(ReviewTags.WIZARD)
        composeRule.onNodeWithTag(ReviewTags.STEP_TITLE, useUnmergedTree = true).assertTextEquals("Projects")
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
