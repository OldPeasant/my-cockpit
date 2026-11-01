package ch.sonensei.cockpit.search

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.contacts.ContactsTags
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.mail.read.MailReadTags
import ch.sonensei.cockpit.ui.SEARCH_ACTION_TAG
import ch.sonensei.cockpit.ui.SEARCH_SCREEN_TAG
import ch.sonensei.cockpit.ui.TASK_DETAIL_SCREEN_TAG
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.Collections
import java.util.UUID
import javax.inject.Inject

/**
 * Search in the running app, wired through the real Hilt graph and HTTP client against a fake server: the app bar
 * opens it, typing searches `/api/v1/search`, and a tapped hit opens in its Android view, in the browser or on its
 * list screen.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class SearchRouteInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @Inject lateinit var tokenStore: PersistentTokenStore

    @Inject lateinit var serverUrls: ServerUrlRepository

    private val server = MockWebServer()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.url
                    requests += url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
                    return when {
                        request.method == "GET" && url.encodedPath == "/api/v1/search" -> json(200, SEARCH_JSON)
                        request.method == "GET" && url.encodedPath == "/api/v1/contacts/$CONTACT_ID" -> json(200, CONTACT_JSON)
                        // Everything else (GTD sync, the opened mail, the contact's related items) is unknown here.
                        else -> json(404, PROBLEM)
                    }
                }
            }
        server.start()
        // The URL first: setting it while logged in would log out.
        runBlocking { serverUrls.setServerUrl(server.url("/").toString()) }
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
    }

    @After
    fun tearDown() {
        scenario?.close()
        server.close()
    }

    private fun activity(): Activity {
        var activity: Activity? = null
        scenario!!.onActivity { activity = it }
        return activity!!
    }

    private fun waitForTag(tag: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun searchRequests() = requests.filter { it.startsWith("/api/v1/search") }

    /** Opens search from the app bar and searches for "plan" with the keyboard's search action. */
    private fun searchForPlan() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNodeWithTag(SEARCH_ACTION_TAG).performClick()
        waitForTag(SEARCH_SCREEN_TAG)
        composeRule.onNodeWithTag(SearchTags.FIELD).performTextInput("plan")
        composeRule.onNodeWithTag(SearchTags.FIELD).performImeAction()
        waitForTag(SearchTags.hit(TASK_ID))
    }

    private fun tap(id: UUID) {
        // The results are a lazy list and the Robolectric screen is small: scroll the hit into view first.
        composeRule.onNodeWithTag(SearchTags.RESULTS).performScrollToNode(hasTestTag(SearchTags.hit(id)))
        composeRule.onNodeWithTag(SearchTags.hit(id)).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `typing searches the server and shows the hits with their type headings`() {
        searchForPlan()

        val asked = searchRequests()
        assertTrue("the search was asked: $requests", asked.isNotEmpty())
        assertTrue(asked.all { it.contains("q=plan") })
        composeRule.onNodeWithTag(SearchTags.group("TASK")).assertIsDisplayed()
        composeRule.onNode(hasText("Plan the fictitious trip"), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasText("Next actions"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a filter chip searches for its types right away`() {
        searchForPlan()

        composeRule.onNodeWithTag(SearchTags.filter(SearchFilter.Files)).performScrollTo().performClick()
        composeRule.waitUntil(WAIT_MS) { searchRequests().any { it.contains("types=FILE") } }

        assertTrue(searchRequests().any { it.contains("types=FILE&types=FOLDER") })
    }

    @Test
    fun `an event with a deep link opens in the browser below the server`() {
        searchForPlan()

        tap(EVENT_ID)

        val started = shadowOf(activity()).nextStartedActivity
        assertNotNull("the browser was not started", started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(server.url("/calendar/events/$EVENT_ID").toString(), started.dataString)
    }

    @Test
    fun `a task opens the Android task detail screen, not the browser`() {
        searchForPlan()

        tap(TASK_ID)
        waitForTag(TASK_DETAIL_SCREEN_TAG)

        assertNull(shadowOf(activity()).nextStartedActivity)
    }

    @Test
    fun `without a browser on the phone, the tap says so and starts nothing`() {
        searchForPlan()
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)

        tap(EVENT_ID)

        assertEquals("No browser on this phone can open the item.", ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(activity()).nextStartedActivity)
        composeRule.onNodeWithTag(SEARCH_SCREEN_TAG).assertExists()
    }

    @Test
    fun `an item without a deep link opens the screen listing its type`() {
        searchForPlan()

        tap(PROJECT_ID)
        waitForTag(screenTag(CockpitDestination.Projects))

        composeRule.onNodeWithTag(SEARCH_SCREEN_TAG).assertDoesNotExist()
        assertNull(shadowOf(activity()).nextStartedActivity)
    }

    @Test
    fun `a contact opens in the Android contact view and back returns to the results`() {
        searchForPlan()

        tap(CONTACT_ID)
        waitForTag(ContactsTags.DETAIL)
        composeRule.waitUntil(WAIT_MS) { requests.contains("/api/v1/contacts/$CONTACT_ID") }
        composeRule.onNode(hasText("Paula Planer"), useUnmergedTree = true).assertExists()
        assertNull("no browser for a contact", shadowOf(activity()).nextStartedActivity)

        composeRule.onNodeWithTag(ContactsTags.BACK).performClick()
        waitForTag(SearchTags.RESULTS)
        composeRule.onNodeWithTag(SearchTags.hit(CONTACT_ID)).assertExists()
    }

    @Test
    fun `an email opens in the Android mail view and back returns to the results`() {
        searchForPlan()

        tap(MAIL_ID)
        waitForTag(MailReadTags.BACK)
        composeRule.waitUntil(WAIT_MS) { requests.contains("/api/v1/mail/messages/$MAIL_ID") }
        composeRule.onNodeWithTag(SearchTags.RESULTS).assertDoesNotExist()

        composeRule.onNodeWithTag(MailReadTags.BACK).performClick()
        waitForTag(SearchTags.RESULTS)
        composeRule.onNodeWithTag(SearchTags.hit(MAIL_ID)).assertExists()
    }

    @Test
    fun `a note opens in the Android note editor, not in the browser`() {
        searchForPlan()

        tap(NOTE_ID)
        composeRule.waitUntil(WAIT_MS) { requests.contains("/api/v1/notes/$NOTE_ID") }

        composeRule.onNodeWithTag(SearchTags.RESULTS).assertDoesNotExist()
        assertNull("no browser for a note", shadowOf(activity()).nextStartedActivity)
    }

    @Test
    fun `the app bar back arrow leaves search`() {
        searchForPlan()

        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForTag(screenTag(CockpitDestination.Dashboard))

        composeRule.onNodeWithTag(SEARCH_SCREEN_TAG).assertDoesNotExist()
    }

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", if (code < 400) "application/json" else "application/problem+json")
        .body(body)
        .build()

    private companion object {
        const val WAIT_MS = 5_000L
        val TASK_ID: UUID = UUID.fromString("0192f0a4-7b1c-7cde-8f00-00000000b001")
        val PROJECT_ID: UUID = UUID.fromString("0192f0a4-7b1c-7cde-8f00-00000000b002")
        val CONTACT_ID: UUID = UUID.fromString("0192f0a4-7b1c-7cde-8f00-00000000b003")
        val MAIL_ID: UUID = UUID.fromString("0192f0a4-7b1c-7cde-8f00-00000000b004")
        val NOTE_ID: UUID = UUID.fromString("0192f0a4-7b1c-7cde-8f00-00000000b005")
        val EVENT_ID: UUID = UUID.fromString("0192f0a4-7b1c-7cde-8f00-00000000b006")
        const val PROBLEM = """{"type":"about:blank","title":"Fictitious problem","status":404}"""
        const val UPDATED = "\"updatedAt\":\"2026-10-01T09:00:00Z\""
        val SEARCH_JSON =
            """
            {"groups":[
             {"type":"TASK","items":[{"id":"$TASK_ID","type":"TASK","title":"Plan the fictitious trip","subtitle":"Next actions",
              "deepLink":"/tasks/$TASK_ID",$UPDATED}]},
             {"type":"PROJECT","items":[{"id":"$PROJECT_ID","type":"PROJECT","title":"Planning project",$UPDATED}]},
             {"type":"EMAIL","items":[{"id":"$MAIL_ID","type":"EMAIL","title":"Plan for Monday","deepLink":"/mail/messages/$MAIL_ID",
              $UPDATED}]},
             {"type":"CONTACT","items":[{"id":"$CONTACT_ID","type":"CONTACT","title":"Paula Planer","deepLink":"/contacts/$CONTACT_ID",
              $UPDATED}]},
             {"type":"NOTE","items":[{"id":"$NOTE_ID","type":"NOTE","title":"Planning notes","deepLink":"/notes/$NOTE_ID",$UPDATED}]},
             {"type":"EVENT","items":[{"id":"$EVENT_ID","type":"EVENT","title":"Planning meeting",
              "deepLink":"/calendar/events/$EVENT_ID",$UPDATED}]}
            ]}
            """.trimIndent()
        val CONTACT_JSON =
            """
            {"id":"$CONTACT_ID","displayName":"Paula Planer","emails":[{"address":"paula@example.org"}],"phones":[],
             "addresses":[],"source":"LOCAL","createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-01T09:00:00Z","version":1}
            """.trimIndent()
    }
}
