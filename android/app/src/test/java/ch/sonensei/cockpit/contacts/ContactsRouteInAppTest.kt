package ch.sonensei.cockpit.contacts

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.capture.entry.CaptureButtonTags
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/**
 * The Contacts route in the Hilt activity with a fake repository: the list opens a contact, Call and Email hand the
 * number or address to the dialer or a mail app, a phone without such an app says so, and back returns to the list.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ContactsRouteInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val repo: ContactsRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private val annaId = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val bertId = UUID.fromString("00000000-0000-7000-8000-000000000002")
    private val waitingId = UUID.fromString("00000000-0000-7000-8000-000000000010")
    private val anna =
        Contact(
            id = annaId,
            displayName = "Anna Muster",
            organization = "Beispiel AG",
            title = null,
            emails = listOf(LabeledValue("anna@example.org", "work")),
            phones = listOf(LabeledValue("+41 (0)44 123 45 67", "mobile"), LabeledValue("044 765 43 21", null)),
            addresses = emptyList(),
            birthday = null,
            notesMd = null,
            source = "LOCAL",
        )
    private val bert = anna.copy(id = bertId, displayName = "Bert Beispiel", emails = emptyList(), phones = emptyList())
    private val related =
        ContactRelated(
            waitingFor = listOf(RelatedWaiting(waitingId, "Send the offer", LocalDate.of(2026, 10, 1))),
            mails = emptyList(),
            events = emptyList(),
        )

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { repo.list(any()) } returns ContactsResult.Ok(listOf(anna, bert))
        coEvery { repo.get(annaId) } returns ContactsResult.Ok(anna)
        coEvery { repo.related(annaId) } returns ContactsResult.Ok(related)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun activity(): Activity {
        var activity: Activity? = null
        scenario!!.onActivity { activity = it }
        return activity!!
    }

    private fun waitForTag(tag: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun openContacts() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Contacts"))
        composeRule.onNodeWithText("Contacts").performClick()
        waitForTag(ContactsTags.contact(annaId))
    }

    private fun openAnna() {
        openContacts()
        composeRule.onNodeWithTag(ContactsTags.contact(annaId)).performClick()
        waitForTag(ContactsTags.CALL)
    }

    @Test
    fun `the Contacts screen is the real list, not the placeholder`() {
        openContacts()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Contacts)).assertIsDisplayed()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertDoesNotExist()
        composeRule.onNodeWithTag(ContactsTags.contact(bertId)).assertExists()
        coVerify { repo.list("") }
    }

    @Test
    fun `a contact opened from the list shows its details and related items`() {
        openAnna()
        composeRule.onNodeWithText("Anna Muster").assertIsDisplayed()
        composeRule.onNodeWithTag(ContactsTags.LIST).assertDoesNotExist()
        composeRule.onNodeWithTag(ContactsTags.DETAIL).performScrollToNode(hasTestTag(ContactsTags.related(waitingId)))
        composeRule.onNodeWithTag(ContactsTags.related(waitingId)).assertExists()
        coVerify(exactly = 1) { repo.get(annaId) }
        coVerify(exactly = 1) { repo.related(annaId) }
    }

    @Test
    fun `Call opens the dialer with the first number, ready to dial`() {
        openAnna()
        composeRule.onNodeWithTag(ContactsTags.CALL).performClick()
        composeRule.waitForIdle()

        val started = shadowOf(activity()).nextStartedActivity
        assertNotNull("the dialer was not started", started)
        assertEquals(Intent.ACTION_DIAL, started.action)
        // Uri.fromParts encodes the `+`, so the decoded parts are compared.
        assertEquals("tel", started.data?.scheme)
        assertEquals("+41441234567", started.data?.schemeSpecificPart)
    }

    @Test
    fun `a number in the phone list dials that number`() {
        openAnna()
        // The detail is a lazy list: the second number may be below the fold.
        composeRule.onNodeWithTag(ContactsTags.DETAIL).performScrollToNode(hasTestTag(ContactsTags.phone("044 765 43 21")))
        composeRule.onNodeWithTag(ContactsTags.phone("044 765 43 21")).performClick()
        composeRule.waitForIdle()

        val started = shadowOf(activity()).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, started.action)
        assertEquals("tel", started.data?.scheme)
        assertEquals("0447654321", started.data?.schemeSpecificPart)
    }

    @Test
    fun `Email opens a mail app with the address`() {
        openAnna()
        composeRule.onNodeWithTag(ContactsTags.MAIL).performClick()
        composeRule.waitForIdle()

        val started = shadowOf(activity()).nextStartedActivity
        assertNotNull("no mail app was started", started)
        assertEquals(Intent.ACTION_SENDTO, started.action)
        assertEquals("mailto", started.data?.scheme)
        assertEquals("anna@example.org", started.data?.schemeSpecificPart)
    }

    @Test
    fun `without a dialer on the phone, Call says so and starts nothing`() {
        openAnna()
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)

        composeRule.onNodeWithTag(ContactsTags.CALL).performClick()
        composeRule.waitForIdle()

        assertEquals("No app on this phone can handle this.", ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(activity()).nextStartedActivity)
        // Still on the contact.
        composeRule.onNodeWithTag(ContactsTags.CALL).assertExists()
    }

    @Test
    fun `the detail's back arrow returns to the list`() {
        openAnna()
        composeRule.onNodeWithTag(ContactsTags.BACK).performClick()
        waitForTag(ContactsTags.contact(annaId))
        composeRule.onNodeWithTag(ContactsTags.CALL).assertDoesNotExist()
    }

    @Test
    fun `the system back returns from the contact to the list, still on the Contacts screen`() {
        openAnna()
        composeRule.runOnUiThread { (activity() as ComponentActivity).onBackPressedDispatcher.onBackPressed() }
        waitForTag(ContactsTags.contact(annaId))
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Contacts)).assertExists()
        composeRule.onNodeWithTag(ContactsTags.CALL).assertDoesNotExist()
    }

    @Test
    fun `the open contact survives a configuration change`() {
        openAnna()
        scenario!!.recreate()
        waitForTag(ContactsTags.CALL)
        composeRule.onNodeWithText("Anna Muster").assertExists()
        composeRule.onNodeWithTag(ContactsTags.LIST).assertDoesNotExist()
    }

    /**
     * Triggers New by its click action rather than a touch: whether a touch reaches it (the shell's capture button
     * covers the same corner) is tested on its own below.
     */
    private fun clickNew() {
        composeRule.onNodeWithTag(ContactsTags.NEW).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun waitForText(text: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `New contact opens the form, Save creates the contact and shows it`() {
        val carlaId = UUID.fromString("00000000-0000-7000-8000-000000000003")
        val carla = bert.copy(id = carlaId, displayName = "Carla Beispiel", givenName = "Carla", familyName = "Beispiel")
        coEvery { repo.create(any()) } returns ContactsResult.Ok(carla)
        coEvery { repo.get(carlaId) } returns ContactsResult.Ok(carla)
        coEvery { repo.related(carlaId) } returns ContactsResult.Ok(ContactRelated(emptyList(), emptyList(), emptyList()))
        openContacts()

        clickNew()
        waitForTag(ContactEditTags.FORM)
        composeRule.onNodeWithText("New contact").assertExists()
        composeRule.onNodeWithTag(ContactEditTags.GIVEN).performTextInput("Carla")
        composeRule.onNodeWithTag(ContactEditTags.FAMILY).performTextInput("Beispiel")
        composeRule.onNodeWithTag(ContactEditTags.SAVE).performClick()

        waitForTag(ContactsTags.SOURCE)
        composeRule.onNodeWithText("Carla Beispiel").assertExists()
        composeRule.onNodeWithTag(ContactEditTags.FORM).assertDoesNotExist()
        coVerify(exactly = 1) { repo.create(match { it.givenName == "Carla" && it.familyName == "Beispiel" }) }
    }

    @Test
    fun `the New contact button is not covered by the shell's capture button`() {
        openContacts()
        composeRule.onNodeWithTag(ContactsTags.NEW).assertIsDisplayed()
        // Screens with their own button in the bottom corner move Capture to the app bar (CockpitDestination.withOwnFab);
        // otherwise both buttons sit in the same corner and a tap on New opens Capture instead.
        composeRule.onNodeWithTag(CaptureButtonTags.FAB).assertDoesNotExist()
    }

    @Test
    fun `Cancel on a new contact returns to the list without saving`() {
        openContacts()
        clickNew()
        waitForTag(ContactEditTags.FORM)
        composeRule.onNodeWithTag(ContactEditTags.CANCEL).performClick()
        waitForTag(ContactsTags.contact(annaId))
        coVerify(exactly = 0) { repo.create(any()) }
    }

    @Test
    fun `Edit opens the form with the contact, Save updates it and the detail shows the change`() {
        coEvery { repo.get(annaId) } returns ContactsResult.Ok(anna) andThen ContactsResult.Ok(anna.copy(title = "CEO"))
        coEvery { repo.update(any(), any()) } returns ContactsResult.Ok(anna.copy(title = "CEO", version = 1))
        openAnna()

        composeRule.onNodeWithTag(ContactsTags.EDIT).performClick()
        waitForTag(ContactEditTags.FORM)
        composeRule.onNodeWithText("Edit contact").assertExists()
        composeRule.onNodeWithTag(ContactEditTags.TITLE).performTextInput("CEO")
        composeRule.onNodeWithTag(ContactEditTags.SAVE).performClick()

        waitForText("CEO, Beispiel AG")
        composeRule.onNodeWithTag(ContactEditTags.FORM).assertDoesNotExist()
        coVerify(exactly = 1) { repo.update(anna, match { it.title == "CEO" && it.organization == "Beispiel AG" }) }
    }

    @Test
    fun `cancelling the form after a failed save leaves no error on the detail`() {
        coEvery { repo.update(any(), any()) } returns ContactsResult.Failed(400)
        openAnna()
        composeRule.onNodeWithTag(ContactsTags.EDIT).performClick()
        waitForTag(ContactEditTags.FORM)
        composeRule.onNodeWithTag(ContactEditTags.SAVE).performClick()
        waitForTag(ContactEditTags.ERROR)

        composeRule.onNodeWithTag(ContactEditTags.CANCEL).performClick()
        waitForTag(ContactsTags.EDIT)
        // Nothing was deleted, so the detail must not say a delete failed.
        composeRule.onNodeWithTag(ContactsTags.ACTION_ERROR).assertDoesNotExist()
        composeRule.onNodeWithText("The contact could not be deleted.").assertDoesNotExist()
    }

    @Test
    fun `Delete asks first, and confirming moves the contact to the Trash and returns to the list`() {
        coEvery { repo.delete(any()) } returns ContactsResult.Ok(Unit)
        openAnna()

        composeRule.onNodeWithTag(ContactsTags.DELETE).performClick()
        composeRule.onNodeWithText("Delete contact?").assertExists()
        composeRule.onNodeWithText("\"Anna Muster\" is moved to the Trash.").assertExists()
        coVerify(exactly = 0) { repo.delete(any()) }

        composeRule.onNodeWithTag(ContactsTags.CONFIRM_DELETE).performClick()
        waitForTag(ContactsTags.contact(annaId))
        composeRule.onNodeWithTag(ContactsTags.CALL).assertDoesNotExist()
        assertEquals("Moved \"Anna Muster\" to the Trash", ShadowToast.getTextOfLatestToast())
        coVerify(exactly = 1) { repo.delete(anna) }
    }

    @Test
    fun `cancelling the delete question keeps the contact`() {
        openAnna()
        composeRule.onNodeWithTag(ContactsTags.DELETE).performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Delete contact?").assertDoesNotExist()
        composeRule.onNodeWithTag(ContactsTags.CALL).assertExists()
        coVerify(exactly = 0) { repo.delete(any()) }
    }

    @Test
    fun `a delete refused by a synced source stays on the contact and says where to change it`() {
        val google = anna.copy(source = "GOOGLE")
        coEvery { repo.get(annaId) } returns ContactsResult.Ok(google)
        coEvery { repo.delete(any()) } returns ContactsResult.Failed(403)
        openAnna()
        composeRule.onNodeWithText("Synced with Google").assertExists()

        composeRule.onNodeWithTag(ContactsTags.DELETE).performClick()
        composeRule.onNodeWithTag(ContactsTags.CONFIRM_DELETE).performClick()

        waitForTag(ContactsTags.ACTION_ERROR)
        composeRule
            .onNodeWithText("This contact comes from Google, which does not accept changes from the cockpit. Change it there.")
            .assertExists()
        composeRule.onNodeWithTag(ContactsTags.CALL).assertExists()
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
