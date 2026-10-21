package ch.sonensei.cockpit.notes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** Inside the Hilt activity the Notes screen is the real list, and New note opens the editor and comes back. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class NotesScreenInAppTest {
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

    @Test
    fun `the Notes screen is the real list, a new note opens the editor and back returns to the list`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Notes"))
        composeRule.onNodeWithText("Notes").performClick()

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Notes)).assertIsDisplayed()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertDoesNotExist()
        composeRule.onNodeWithTag(NotesTags.NEW).performClick()

        // A new note opens straight in the plain editor.
        composeRule.onNodeWithTag(NoteEditorTags.TITLE).assertIsDisplayed()
        composeRule.onNodeWithTag(NoteEditorTags.BODY).assertIsDisplayed()
        composeRule.onNodeWithTag(NotesTags.NEW).assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Back to notes").performClick()
        composeRule.onNodeWithTag(NotesTags.NEW).assertIsDisplayed()
        composeRule.onNodeWithTag(NoteEditorTags.BODY).assertDoesNotExist()
    }
}
