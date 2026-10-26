package ch.sonensei.cockpit.newitems

import androidx.compose.ui.test.assertTextEquals
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
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.mail.read.MailReadTags
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.UUID
import javax.inject.Inject

/**
 * The New view in the running app: reached from the More list, wired through the real Hilt graph and HTTP client
 * against a fake server, it lists the unified inbox and a tapped mail opens the mail read view.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class NewScreenInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @Inject lateinit var tokenStore: PersistentTokenStore

    @Inject lateinit var serverUrls: ServerUrlRepository

    private val server = MockWebServer()
    private val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    paths += path
                    return if (request.method == "GET" && path == "/api/v1/mail/messages") {
                        json(200, """{"items":[$MAIL_JSON],"nextCursor":null}""")
                    } else {
                        // Everything else (GTD sync, the opened message) is unknown to this fake server.
                        json(404, PROBLEM)
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

    @Test
    fun `New in the More list shows the real New view with the unified inbox`() {
        openNew()

        composeRule.onNodeWithTag(NewTags.filter(NewFilter.ALL)).assertExists()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertDoesNotExist()
        waitForMail()
        composeRule.onNodeWithTag(NewTags.source(NewRow.Mail(mailSummary())), useUnmergedTree = true).assertTextEquals("Mail")
        assertTrue(paths.contains("/api/v1/mail/messages"))
    }

    @Test
    fun `tapping a mail opens the mail read view and back returns to the list`() {
        openNew()
        waitForMail()

        composeRule.onNodeWithText(SUBJECT).performClick()

        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodes(hasTestTag(MailReadTags.BACK)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitUntil(WAIT_MS) { paths.contains("/api/v1/mail/messages/$MAIL_ID") }
        composeRule.onNodeWithTag(NewTags.LIST).assertDoesNotExist()

        composeRule.onNodeWithTag(MailReadTags.BACK).performClick()
        composeRule.onNodeWithTag(NewTags.LIST).assertExists()
        composeRule.onNodeWithText(SUBJECT).assertExists()
    }

    private fun openNew() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("New"))
        composeRule.onNodeWithText("New").performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.New)).assertExists()
    }

    private fun waitForMail() =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodes(hasText(SUBJECT)).fetchSemanticsNodes().isNotEmpty() }

    private fun mailSummary() = NewTestData.mail(SUBJECT, null, 0).copy(id = UUID.fromString(MAIL_ID))

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
        const val SUBJECT = "Fictitious quarterly report"
        const val MAIL_ID = "0192f0a4-7b1c-7cde-8f00-00000000a001"
        const val ACCOUNT_ID = "0192f0a4-7b1c-7cde-8f00-00000000a080"
        const val FOLDER_ID = "0192f0a4-7b1c-7cde-8f00-00000000a090"
        const val PROBLEM = """{"type":"about:blank","title":"Fictitious problem","status":404}"""
        val MAIL_JSON =
            """
            {"id":"$MAIL_ID","accountId":"$ACCOUNT_ID","folderId":"$FOLDER_ID",
             "subject":"$SUBJECT","snippet":"Numbers attached",
             "from":[{"name":"Ada Example","address":"ada@example.org"}],"to":[],
             "sentAt":"2026-10-21T07:59:00Z","receivedAt":"2026-10-21T08:00:00Z",
             "seen":false,"flagged":false,"hasAttachments":false,"answered":false,"version":1}
            """.trimIndent()
    }
}
