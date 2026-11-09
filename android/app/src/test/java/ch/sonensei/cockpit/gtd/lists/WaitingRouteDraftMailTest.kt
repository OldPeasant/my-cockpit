package ch.sonensei.cockpit.gtd.lists

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.next.TaskCompletionRepository
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** "Draft email" on the Waiting route addresses the mail to the linked contact (SPEC.md §5.3). */
@RunWith(AndroidJUnit4::class)
class WaitingRouteDraftMailTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val now = Instant.parse("2026-10-20T09:00:00Z")
    private val anna = id(50)
    private val contract =
        GtdTask(
            id(1),
            "Contract",
            TaskList.WAITING,
            0,
            1,
            waitingForText = "Anna Example (anna.old@example.org)",
            waitingForContactId = anna,
            waitingSince = LocalDate.of(2026, 10, 11),
        )

    private val repository =
        mockk<TaskListsRepository> { every { observe(TaskList.WAITING, any()) } returns MutableStateFlow(Live(listOf(contract))) }
    private val contexts = mockk<ContextRepository> { every { observe() } returns MutableStateFlow(Cached(emptyList(), false, now)) }
    private val completion = mockk<TaskCompletionRepository>()

    private fun draftMailWith(contactEmail: suspend (UUID) -> String?): Intent {
        val viewModel = WaitingViewModel(repository, contexts, completion, Clock.fixed(now, ZoneOffset.UTC), contactEmail)
        composeRule.setContent { CockpitTheme { WaitingRoute(viewModel = viewModel) } }
        composeRule.onNodeWithTag(WaitingTags.draftMail(contract.id)).performClick()
        composeRule.waitForIdle()
        return shadowOf(composeRule.activity).nextStartedActivity
    }

    @Test
    fun `draft email is addressed to the linked contact's email address`() {
        val asked = mutableListOf<UUID>()
        val started =
            draftMailWith {
                asked += it
                "anna@example.com"
            }

        assertEquals(listOf(anna), asked)
        assertEquals(Intent.ACTION_SENDTO, started.action)
        assertEquals(listOf("anna@example.com"), started.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList())
        assertEquals("anna@example.com", started.data?.schemeSpecificPart?.substringBefore('?'))
        assertEquals("Contract", started.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `draft email falls back to the address in the waiting-for text when the contact has none`() {
        val started = draftMailWith { null }

        assertEquals(listOf("anna.old@example.org"), started.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList())
        assertEquals("anna.old@example.org", started.data?.schemeSpecificPart?.substringBefore('?'))
    }

    @Test
    fun `taps while the contact is still loading open a single mail`() {
        val lookup = CompletableDeferred<String?>()
        var asked = 0
        val viewModel =
            WaitingViewModel(repository, contexts, completion, Clock.fixed(now, ZoneOffset.UTC)) {
                asked++
                lookup.await()
            }
        composeRule.setContent { CockpitTheme { WaitingRoute(viewModel = viewModel) } }
        val button = composeRule.onNodeWithTag(WaitingTags.draftMail(contract.id))

        button.performClick()
        button.performClick()
        composeRule.waitForIdle()
        assertNull(shadowOf(composeRule.activity).nextStartedActivity)

        lookup.complete("anna@example.com")
        composeRule.waitForIdle()
        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(listOf("anna@example.com"), started.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList())
        assertNull(shadowOf(composeRule.activity).nextStartedActivity)
        assertEquals(1, asked)

        // Once the draft is open, the button drafts again.
        button.performClick()
        composeRule.waitForIdle()
        assertEquals(Intent.ACTION_SENDTO, shadowOf(composeRule.activity).nextStartedActivity.action)
    }
}
