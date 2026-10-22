package ch.sonensei.cockpit.journal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
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
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Inside the Hilt activity the Journal destination is the real screen (not the placeholder), wired to its view model:
 * today's entry is loaded, edited text is saved when the editor is left, and text typed last is still saved when the
 * shell's back arrow leaves the screen.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class JournalRouteInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val repo: JournalRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private val id = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val loaded = JournalEntry(id, LocalDate.of(2026, 10, 22), "Walked by the lake", null, emptyList(), Instant.EPOCH, 3)

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        // The view model asks for the device's today, which the test does not fix.
        coEvery { repo.get(any()) } returns JournalResult.Ok(loaded)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun openJournal() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Journal"))
        composeRule.onNodeWithText("Journal").performClick()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(JournalTags.VIEW).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the Journal screen shows today's entry and saves edited text when the editor is left`() {
        val saved = AtomicBoolean(false)
        coEvery { repo.save(any(), loaded, "Walked by the lake, then tea") } answers {
            saved.set(true)
            JournalSaveResult.Saved(loaded.copy(bodyMd = "Walked by the lake, then tea", version = 4))
        }
        openJournal()

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Journal)).assertIsDisplayed()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertDoesNotExist()
        composeRule.onNodeWithText("Walked by the lake").assertIsDisplayed()

        composeRule.onNodeWithTag(JournalTags.TOGGLE).performClick()
        composeRule.onNodeWithTag(JournalTags.BODY).performTextReplacement("Walked by the lake, then tea")
        composeRule.onNodeWithTag(JournalTags.TOGGLE).performClick()

        composeRule.waitUntil(WAIT_MS) { saved.get() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(JournalTags.STATUS).assertTextContains("Saved")
        composeRule.onNodeWithText("Walked by the lake, then tea").assertIsDisplayed()
        coVerify(exactly = 1) { repo.save(any(), any(), any()) }
    }

    @Test
    fun `the shell's back arrow leaves the Journal and still saves the text typed last`() {
        val saved = AtomicBoolean(false)
        coEvery { repo.save(any(), loaded, "Typed just before leaving") } answers {
            saved.set(true)
            JournalSaveResult.Saved(loaded.copy(bodyMd = "Typed just before leaving", version = 4))
        }
        openJournal()

        composeRule.onNodeWithTag(JournalTags.TOGGLE).performClick()
        composeRule.onNodeWithTag(JournalTags.BODY).performTextReplacement("Typed just before leaving")
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithTag(screenTag(CockpitDestination.More)).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.waitUntil(WAIT_MS) { saved.get() }
        coVerify(exactly = 1) { repo.save(any(), loaded, "Typed just before leaving") }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
