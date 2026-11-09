package ch.sonensei.cockpit.gtd.lists

import android.content.Intent
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID

/** Waiting "Follow up" > "Draft email" (SPEC.md §5.3): the address found in the waiting-for text, the mailto and its labels. */
@RunWith(AndroidJUnit4::class)
class WaitingDraftEmailTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 20)
    private val lease =
        GtdTask(id(1), "Lease papers", TaskList.WAITING, 0, 1, waitingForText = "Anna", waitingSince = today.minusDays(3))
    private val loaded = TaskListUiState(tasks = listOf(lease), loading = false, loaded = true, today = today)

    private val drafted = mutableListOf<String>()

    private fun show(state: TaskListUiState) {
        composeRule.setContent {
            CockpitTheme { WaitingContent(state, LiveListCallbacks({}, {}, {}), { _, _ -> }, draftMail = { drafted += it.title }) }
        }
    }

    @Test
    fun `the address is found in common name-and-address forms`() {
        assertEquals("anna@example.org", emailIn("Anna Example <anna@example.org>"))
        assertEquals("anna@example.org", emailIn("Anna (anna@example.org)"))
        assertEquals("anna@example.org", emailIn("Anna [anna@example.org]"))
        assertEquals("bob@example.org", emailIn("\"bob@example.org\", carol@example.org"))
        assertEquals("anna+work@example.org", emailIn("anna+work@example.org"))
    }

    @Test
    fun `a sentence ending after the address does not add its full stop to it`() {
        assertEquals("anna@example.org", emailIn("Reply from anna@example.org."))
        assertEquals("anna@mail.example.co.uk", emailIn("Ask anna@mail.example.co.uk."))
        assertEquals("anna@example.org", emailIn("Ping anna@example.org..."))
        assertNull(emailIn("alex@localhost."))
    }

    @Test
    fun `no address is found in a name, a host without a domain, or nothing`() {
        assertNull(emailIn(null))
        assertNull(emailIn(""))
        assertNull(emailIn("Alex"))
        assertNull(emailIn("alex@localhost"))
        assertNull(emailIn("@example.org"))
    }

    @Test
    fun `the subject is encoded, so a question mark or hash in the title stays in it`() {
        val task = lease.copy(title = "Q3 report? #2", waitingForText = "anna@example.org")
        assertEquals("mailto:anna@example.org?subject=Q3%20report%3F%20%232", followUpMailUri(task))
        val intent = followUpMailIntent(task)
        assertEquals("mailto", intent.data?.scheme)
        assertEquals("Q3 report? #2", intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `without an address the mail has no recipient, only the subject`() {
        val intent = followUpMailIntent(lease)
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto:?subject=Lease%20papers", intent.dataString)
        assertFalse(intent.hasExtra(Intent.EXTRA_EMAIL))
        assertEquals("Lease papers", intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `a plus in the address survives as a recipient`() {
        val intent = followUpMailIntent(lease.copy(waitingForText = "Anna <anna+work@example.org>"))
        assertEquals(listOf("anna+work@example.org"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList())
        assertEquals("anna+work@example.org", intent.data?.schemeSpecificPart?.substringBefore('?'))
    }

    @Test
    fun `the draft email button names its task for screen readers and drafts that task`() {
        show(loaded)
        composeRule.onNodeWithText("Draft email").assertIsDisplayed()
        composeRule
            .onNodeWithTag(WaitingTags.draftMail(lease.id))
            .assertContentDescriptionEquals("Draft an email to follow up on Lease papers")
            .performClick()
        assertEquals(listOf("Lease papers"), drafted)
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German, draft email is German`() {
        show(loaded)
        composeRule.onNodeWithText("E-Mail entwerfen").assertIsDisplayed()
        composeRule
            .onNodeWithTag(WaitingTags.draftMail(lease.id))
            .assertContentDescriptionEquals("E-Mail zum Nachfassen von Lease papers entwerfen")
    }
}
