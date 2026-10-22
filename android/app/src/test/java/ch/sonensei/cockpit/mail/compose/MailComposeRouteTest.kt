package ch.sonensei.cockpit.mail.compose

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.util.UUID

/**
 * The compose route with a real view model and a mocked repository: send and back close it, attach goes through the
 * system picker (a stand-in registry answers it) into an upload; plus the screen texts the other screen tests skip.
 */
@RunWith(AndroidJUnit4::class)
class MailComposeRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val repository = mockk<MailComposeRepository>()
    private var closed = 0

    private fun draft(etag: String = "\"1\"") = Draft(id(1), work.id, etag, emptyList(), emptyList(), emptyList(), "", "", "", emptyList())

    @Before
    fun setUp() {
        coEvery { repository.accounts() } returns MailResult.Ok(listOf(work))
        coEvery { repository.create(any(), any()) } returns MailResult.Ok(draft())
        coEvery { repository.update(any(), any(), any()) } returns MailResult.Ok(draft("\"2\""))
        coEvery { repository.discard(any(), any()) } returns MailResult.Ok(Unit)
    }

    private fun showRoute(
        mode: ComposeMode,
        vm: MailComposeViewModel,
        registry: ActivityResultRegistry? = null,
    ) = composeRule.setContent {
        CockpitTheme {
            if (registry == null) {
                MailComposeRoute(mode = mode, onClose = { closed++ }, viewModel = vm)
            } else {
                val owner =
                    object : ActivityResultRegistryOwner {
                        override val activityResultRegistry: ActivityResultRegistry = registry
                    }
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MailComposeRoute(mode = mode, onClose = { closed++ }, viewModel = vm)
                }
            }
        }
    }

    @Test
    fun `a typed mail is saved, sent and the route closes`() {
        coEvery { repository.send(id(1), "\"1\"") } returns SendOutcome.Sent
        val vm = MailComposeViewModel(repository)
        showRoute(ComposeMode.New(work.id), vm)

        composeRule.onNodeWithTag(MailComposeTags.TITLE).assertTextEquals("New message")
        composeRule.onNodeWithTag(MailComposeTags.field(RecipientField.TO)).performTextInput("ann@example.org")
        composeRule.onNodeWithTag(MailComposeTags.BODY).performTextInput("Hello Ann")
        composeRule.onNodeWithTag(MailComposeTags.SEND).performClick()
        composeRule.waitUntil(WAIT_MS) { closed == 1 }

        coVerify(exactly = 1) {
            repository.create(work.id, match { it.to == listOf(Recipient("ann@example.org")) && it.html == "<p>Hello Ann</p>" })
        }
        coVerify(exactly = 1) { repository.send(id(1), "\"1\"") }
    }

    @Test
    fun `back on an untouched reply discards its draft and closes`() {
        coEvery { repository.start(ComposeMode.Reply(id(5))) } returns MailResult.Ok(draft())
        val vm = MailComposeViewModel(repository)
        showRoute(ComposeMode.Reply(id(5)), vm)
        composeRule.onNodeWithTag(MailComposeTags.TITLE).assertTextEquals("Reply")
        composeRule.waitUntil(WAIT_MS) { vm.uiState.value.phase == ComposePhase.EDITING }

        composeRule.onNodeWithTag(MailComposeTags.BACK).performClick()
        composeRule.waitUntil(WAIT_MS) { closed == 1 }

        coVerify(exactly = 1) { repository.discard(id(1), "\"1\"") }
    }

    @Test
    fun `a state left closed by an earlier compose does not close the new one`() {
        coEvery { repository.start(ComposeMode.Reply(id(5))) } returns MailResult.Ok(draft())
        val vm = MailComposeViewModel(repository)
        vm.start(ComposeMode.Reply(id(5)))
        vm.abandon()

        showRoute(ComposeMode.New(work.id), vm)
        composeRule.waitForIdle()

        assertEquals(0, closed)
        composeRule.onNodeWithTag(MailComposeTags.TITLE).assertTextEquals("New message")
        assertEquals(ComposeMode.New(work.id), vm.uiState.value.mode)
    }

    @Test
    fun `a file picked in the system picker is described by its provider and uploaded into the draft`() {
        Robolectric.setupContentProvider(DeviceFilesTest.PickedFilesProvider::class.java, DeviceFilesTest.PickedFilesProvider.AUTHORITY)
        val picked = Uri.parse("content://${DeviceFilesTest.PickedFilesProvider.AUTHORITY}/files/report")
        val launched = mutableListOf<Any?>()
        val registry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    launched += input
                    dispatchResult(requestCode, listOf(picked))
                }
            }
        val uploaded = mutableListOf<PickedFile>()
        coEvery { repository.attach(id(1), "\"1\"", any()) } answers {
            uploaded += thirdArg<PickedFile>()
            MailResult.Ok(draft("\"2\"").copy(attachments = listOf(DraftAttachment("2", "Quarterly report.pdf", 2048))))
        }
        val vm = MailComposeViewModel(repository)
        showRoute(ComposeMode.New(work.id), vm, registry)
        composeRule.waitUntil(WAIT_MS) { vm.uiState.value.phase == ComposePhase.EDITING }

        composeRule.onNodeWithTag(MailComposeTags.ATTACH).performClick()
        composeRule.waitUntil(WAIT_MS) { uploaded.isNotEmpty() }
        composeRule.waitForIdle()

        assertEquals(1, launched.size)
        assertEquals(listOf("*/*"), (launched.single() as Array<*>).toList())
        assertEquals("Quarterly report.pdf", uploaded.single().name)
        assertEquals("application/pdf", uploaded.single().mime)
        composeRule.onNodeWithText("Quarterly report.pdf").assertIsDisplayed()
        coVerify(exactly = 1) { repository.create(work.id, any()) }
    }

    @Test
    fun `every problem has its own text`() {
        val texts =
            mapOf(
                ComposeProblem.START_FAILED to "The draft could not be prepared.",
                ComposeProblem.SAVE_FAILED to "The draft could not be saved.",
                ComposeProblem.INVALID_ADDRESS to "Check the marked recipients: one is not an email address.",
                ComposeProblem.NO_ACCOUNT to "No mail account to send from.",
                ComposeProblem.SEND_FAILED to "The message could not be sent.",
                ComposeProblem.ATTACH_FAILED to "The file could not be attached.",
                ComposeProblem.TOO_LARGE to "The file is too large for this message.",
                ComposeProblem.DISCARD_FAILED to "The draft could not be discarded.",
            )
        var current by mutableStateOf(editing)
        composeRule.setContent { CockpitTheme { MailComposeContent(current, MailComposeCallbacks()) } }

        texts.forEach { (problem, text) ->
            current = editing.copy(problem = problem)
            composeRule.onNodeWithTag(MailComposeTags.PROBLEM).assertTextEquals(text)
        }
    }

    @Test
    fun `without an account a new mail offers to retry loading them`() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            CockpitTheme {
                MailComposeContent(
                    editing.copy(accounts = emptyList(), accountId = null, problem = ComposeProblem.NO_ACCOUNT),
                    MailComposeCallbacks(retry = { calls += "retry" }),
                )
            }
        }

        composeRule.onNodeWithTag(MailComposeTags.ACCOUNT).assertDoesNotExist()
        composeRule.onNodeWithTag(MailComposeTags.RETRY).performClick()

        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `cc and bcc are typed in their own fields once shown, the empty body shows its hint`() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            CockpitTheme {
                MailComposeContent(
                    editing.copy(showCcBcc = true),
                    MailComposeCallbacks(cc = { calls += "cc:$it" }, bcc = { calls += "bcc:$it" }),
                )
            }
        }

        composeRule.onNodeWithTag(MailComposeTags.CC_BCC).assertDoesNotExist()
        composeRule.onNodeWithText("Message").assertExists()
        composeRule.onNodeWithTag(MailComposeTags.field(RecipientField.CC)).performTextInput("carl@example.org")
        composeRule.onNodeWithTag(MailComposeTags.field(RecipientField.BCC)).performTextInput("dora@example.org")

        assertEquals(listOf("cc:carl@example.org", "bcc:dora@example.org"), calls.filterNot { it == "cc:" || it == "bcc:" })
    }

    private val editing =
        MailComposeUiState(
            mode = ComposeMode.New(),
            phase = ComposePhase.EDITING,
            accounts = listOf(work),
            accountId = work.id,
        )

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
