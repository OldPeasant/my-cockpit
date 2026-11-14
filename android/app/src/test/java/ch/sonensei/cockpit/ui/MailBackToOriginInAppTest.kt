package ch.sonensei.cockpit.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailAccountFolders
import ch.sonensei.cockpit.mail.list.MailListAction
import ch.sonensei.cockpit.mail.list.MailListTags
import ch.sonensei.cockpit.mail.list.MailPage
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.mail.list.MailSummary
import ch.sonensei.cockpit.mail.list.Mailbox
import ch.sonensei.cockpit.mail.read.MailActionOutcome
import ch.sonensei.cockpit.mail.read.MailMessage
import ch.sonensei.cockpit.mail.read.MailReadRepository
import ch.sonensei.cockpit.mail.read.MailReadTags
import ch.sonensei.cockpit.newitems.NewTags
import ch.sonensei.cockpit.search.SearchGroup
import ch.sonensei.cockpit.search.SearchHit
import ch.sonensei.cockpit.search.SearchRepository
import ch.sonensei.cockpit.search.SearchResult
import ch.sonensei.cockpit.search.SearchTags
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
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
 * Back from a mail returns to where it was opened, for the entry points with their own navigation code: the New
 * screen, global search (system back, toolbar back, and after a delete) and the Dashboard → Mail tab. The Mail list and
 * its search results are covered by `MailRouteInAppTest`, `MailSearchRouteInAppTest` and `MailReadNavigationInAppTest`,
 * task links by `MailOriginShellTest`.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class MailBackToOriginInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val mail: MailRepository = mockk(relaxed = true)

    @BindValue
    @JvmField
    val reader: MailReadRepository = mockk()

    @BindValue
    @JvmField
    val search: SearchRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private var scenario: ActivityScenario<MainActivity>? = null

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val account = MailAccount(id(80), "Work", "me@example.org")

    private fun summary(n: Int) =
        MailSummary(id(n), account.id, "Anna Example", "Subject $n", "Snippet $n", Instant.EPOCH, false, false, false)

    private fun message(n: Int) =
        MailMessage(
            id(n),
            "Subject $n",
            "Anna Example <anna@example.org>",
            emptyList(),
            emptyList(),
            null,
            true,
            "",
            "Body $n",
            0,
            emptyList(),
        )

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { mail.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(account, emptyList())))
        coEvery { mail.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage(listOf(summary(1), summary(2)), null))
        coEvery { mail.markRead(any()) } returns MailResult.Ok(true)
        (1..7).forEach { n -> coEvery { reader.message(id(n)) } returns MailResult.Ok(message(n)) }
        coEvery { search.search("invoice", any()) } returns
            SearchResult.Ok(listOf(SearchGroup("EMAIL", listOf(SearchHit(id(7), "EMAIL", "Subject 7", "Anna Example", null)))))
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun waitFor(tag: String) =
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForSubject(n: Int) =
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodes(hasTestTag(MailReadTags.SUBJECT) and hasText("Subject $n")).fetchSemanticsNodes().isNotEmpty()
        }

    private fun systemBack() = scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun openNew() {
        launch()
        composeRule.onNode(hasText("New") and isSelectable()).performClick()
        waitFor(screenTag(CockpitDestination.New))
        waitFor("new-row:mail:${id(1)}")
    }

    private fun searchInvoice() {
        launch()
        composeRule.onNodeWithTag(SEARCH_ACTION_TAG).performClick()
        waitFor(SEARCH_SCREEN_TAG)
        composeRule.onNodeWithTag(SearchTags.FIELD).performTextInput("invoice")
        composeRule.onNodeWithTag(SearchTags.FIELD).performImeAction()
        waitFor(SearchTags.hit(id(7)))
        composeRule.onNodeWithTag(SearchTags.hit(id(7))).performClick()
        waitForSubject(7)
    }

    private fun assertSearchShownWithItsHit() {
        waitFor(SEARCH_SCREEN_TAG)
        waitFor(SearchTags.hit(id(7)))
        composeRule.onNodeWithTag(SearchTags.FIELD).assertTextEquals("invoice")
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertDoesNotExist()
    }

    @Test
    fun `back from a mail opened on the New screen returns to the New screen`() {
        openNew()
        composeRule.onNodeWithTag("new-row:mail:${id(1)}").performClick()
        waitForSubject(1)
        // The New screen's list is not a mail list: no previous/next there.
        composeRule.onNodeWithTag(MailReadTags.NAVIGATION).assertDoesNotExist()

        systemBack()
        waitFor(NewTags.LIST)
        composeRule.onNodeWithTag(screenTag(CockpitDestination.New)).assertExists()
        composeRule.onNodeWithTag(MailReadTags.SUBJECT).assertDoesNotExist()

        composeRule.onNodeWithTag("new-row:mail:${id(2)}").performClick()
        waitForSubject(2)
        composeRule.onNodeWithTag(MailReadTags.BACK).performClick()
        waitFor(NewTags.LIST)
        composeRule.onNodeWithTag(screenTag(CockpitDestination.New)).assertExists()
    }

    @Test
    fun `a mail deleted on the New screen returns to the New screen`() {
        coEvery { reader.act(id(1), MailListAction.DELETE) } returns MailResult.Ok(MailActionOutcome(true))
        openNew()
        composeRule.onNodeWithTag("new-row:mail:${id(1)}").performClick()
        waitForSubject(1)

        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.DELETE)).performClick()
        waitFor(NewTags.LIST)
        composeRule.onNodeWithTag(screenTag(CockpitDestination.New)).assertExists()
        composeRule.onNodeWithTag("new-row:mail:${id(1)}").assertDoesNotExist()
    }

    @Test
    fun `system back from a mail opened in global search returns to the search with its results`() {
        searchInvoice()
        composeRule.onNodeWithTag(MailReadTags.NAVIGATION).assertDoesNotExist()

        systemBack()
        assertSearchShownWithItsHit()
        coVerify(exactly = 1) { search.search("invoice", any()) }
    }

    @Test
    fun `the read view's back from a mail opened in global search returns to the search`() {
        searchInvoice()

        composeRule.onNodeWithTag(MailReadTags.BACK).performClick()
        assertSearchShownWithItsHit()
    }

    @Test
    fun `a mail deleted from global search returns to the search, not past it`() {
        coEvery { reader.act(id(7), MailListAction.DELETE) } returns MailResult.Ok(MailActionOutcome(true))
        searchInvoice()

        composeRule.onNodeWithTag(MailReadTags.action(MailListAction.DELETE)).performClick()
        waitFor(SEARCH_SCREEN_TAG)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SEARCH_SCREEN_TAG).assertExists()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertDoesNotExist()
    }

    @Test
    fun `from the Dashboard, back from a mail returns to the Mail list and back again to the Dashboard`() {
        launch()
        waitFor(screenTag(CockpitDestination.Dashboard))
        composeRule.onNode(hasText("Mail") and isSelectable()).performClick()
        waitFor(MailListTags.row(id(1)))
        composeRule.onNodeWithTag(MailListTags.row(id(1))).performClick()
        waitForSubject(1)

        systemBack()
        waitFor(MailListTags.LIST)
        systemBack()
        waitFor(screenTag(CockpitDestination.Dashboard))
        composeRule.onNodeWithTag(MailListTags.LIST).assertDoesNotExist()
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
