package ch.sonensei.cockpit.gtd.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import ch.sonensei.cockpit.gtd.lists.LiveListTags
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Inside the Hilt activity the Projects, Waiting and Someday tabs show the real screens (their view models built by
 * Hilt), not the placeholder.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ProjectsScreensInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @Inject lateinit var tokenStore: PersistentTokenStore

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

    private fun openFromMore(destination: CockpitDestination) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val label = destination.name
        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText(label))
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithTag(screenTag(destination)).assertIsDisplayed()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertDoesNotExist()
    }

    private fun waitForAny(vararg tags: String) =
        composeRule.waitUntil(WAIT_MS) {
            tags.any { tag -> composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        }

    @Test
    fun `the Projects tab is the real Projects view`() {
        openFromMore(CockpitDestination.Projects)
        waitForAny(ProjectsTags.LOADING, ProjectsTags.EMPTY, ProjectsTags.LIST)
    }

    @Test
    fun `the Waiting tab is the real Waiting For view`() {
        openFromMore(CockpitDestination.Waiting)
        waitForAny(LiveListTags.LOADING, LiveListTags.EMPTY, LiveListTags.LIST, LiveListTags.ERROR)
    }

    @Test
    fun `the Someday tab is the real Someday view`() {
        openFromMore(CockpitDestination.Someday)
        waitForAny(LiveListTags.LOADING, LiveListTags.EMPTY, LiveListTags.LIST, LiveListTags.ERROR)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
