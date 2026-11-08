package ch.sonensei.cockpit.mail.read

import ch.sonensei.cockpit.core.settings.MailImagePolicy
import ch.sonensei.cockpit.core.settings.SettingsUpdateResult
import ch.sonensei.cockpit.core.settings.UserSettings
import ch.sonensei.cockpit.core.settings.UserSettingsChange
import ch.sonensei.cockpit.core.settings.UserSettingsRepository
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Remote images of the read view: blocked by default; the synced settings (`mailImagePolicy = allow`, an allowed
 * sender) load them through the proxy without asking; "Load images" and "Always for this sender" ask for them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailReadImagesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val reader = mockk<MailReadRepository>()
    private val mail = mockk<MailRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    /** The mail as the server sends it with remote images removed. */
    private fun blocked(
        n: Int = 1,
        from: String? = "anna@example.org",
        count: Int = 2,
    ) = MailMessage(
        id = id(n),
        subject = "Quarterly report",
        from = "Anna Example <anna@example.org>",
        to = emptyList(),
        cc = emptyList(),
        date = Instant.EPOCH,
        seen = true,
        html = "<p>Hi</p>",
        text = "Hi",
        remoteImagesBlocked = count,
        attachments = emptyList(),
        fromAddress = from,
    )

    /** The same mail with its remote images pointing to the server's proxy. */
    private fun proxied(n: Int = 1) =
        blocked(n).copy(html = """<p>Hi</p><img src="/api/v1/mail/image-proxy?url=x">""", remoteImagesBlocked = 0)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { mail.markRead(any()) } returns MailResult.Ok(true)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun opened(
        settings: UserSettingsRepository = settingsWith(),
        first: MailResult<MailMessage> = MailResult.Ok(blocked()),
        withImages: MailResult<MailMessage> = MailResult.Ok(proxied()),
    ): MailReadViewModel {
        coEvery { reader.message(id(1)) } returns first
        coEvery { reader.message(id(1), true) } returns withImages
        return readViewModel(reader, mail, settings).also { it.open(id(1)) }
    }

    private fun MailReadViewModel.shown() = uiState.value.message

    @Test
    fun `without settings, or with the default policy, remote images stay blocked`() =
        runTest(dispatcher) {
            listOf(null, UserSettings(), UserSettings(mailImageAllowedSenders = listOf("ben@example.org"))).forEach {
                val vm = opened(settingsWith(it))
                advanceUntilIdle()

                assertEquals("$it", blocked(), vm.shown())
            }
            coVerify(exactly = 0) { reader.message(any(), true) }
        }

    @Test
    fun `policy allow loads the mail with proxied images right away`() =
        runTest(dispatcher) {
            val vm = opened(settingsWith(UserSettings(mailImagePolicy = MailImagePolicy.ALLOW)))
            advanceUntilIdle()

            assertEquals(proxied(), vm.shown())
            assertFalse(vm.uiState.value.loading)
            coVerify(exactly = 1) { reader.message(id(1), true) }
            coVerify(exactly = 0) { reader.message(any(), false) }
        }

    @Test
    fun `policy allow that fails to load shows the load error`() =
        runTest(dispatcher) {
            val vm = opened(settingsWith(UserSettings(mailImagePolicy = MailImagePolicy.ALLOW)), withImages = MailResult.Offline)
            advanceUntilIdle()

            assertEquals(MailReadError.OFFLINE, vm.uiState.value.error)
            assertNull(vm.shown())
        }

    @Test
    fun `an allowed sender, in any case, gets its images through the proxy after the first load`() =
        runTest(dispatcher) {
            // The mail moved: the proxied copy is asked for under its current id, which is also the one marked read.
            coEvery { reader.message(id(1)) } returns MailResult.Ok(blocked(n = 2).copy(seen = false))
            coEvery { reader.message(id(2), true) } returns MailResult.Ok(proxied(n = 2).copy(seen = false))
            val settings = settingsWith(UserSettings(mailImageAllowedSenders = listOf("ben@example.org", "Anna@Example.ORG")))
            val vm = readViewModel(reader, mail, settings).also { it.open(id(1)) }
            advanceUntilIdle()

            assertEquals(proxied(n = 2).copy(seen = false), vm.shown())
            coVerify(exactly = 1) { reader.message(id(1), false) }
            coVerify(exactly = 1) { reader.message(id(2), true) }
            coVerify(exactly = 1) { mail.markRead(id(2)) }
        }

    @Test
    fun `an allowed sender whose proxied load fails still sees the mail, with its images blocked`() =
        runTest(dispatcher) {
            val settings = settingsWith(UserSettings(mailImageAllowedSenders = listOf("anna@example.org")))
            listOf(MailResult.Offline, MailResult.Failed(502)).forEach { failure ->
                val vm = opened(settings, withImages = failure)
                advanceUntilIdle()

                assertEquals("$failure", blocked(), vm.shown())
                assertNull(vm.uiState.value.error)
                assertNull(vm.uiState.value.notice)
                assertFalse(vm.uiState.value.loading)
            }
        }

    @Test
    fun `an allowed sender's mail without blocked images is loaded only once`() =
        runTest(dispatcher) {
            val settings = settingsWith(UserSettings(mailImageAllowedSenders = listOf("anna@example.org")))
            val vm = opened(settings, first = MailResult.Ok(blocked(count = 0)))
            advanceUntilIdle()

            assertEquals(blocked(count = 0), vm.shown())
            coVerify(exactly = 0) { reader.message(any(), true) }
        }

    @Test
    fun `a mail without a sender address is not matched by the allowlist`() =
        runTest(dispatcher) {
            val settings = settingsWith(UserSettings(mailImageAllowedSenders = listOf("anna@example.org")))
            val vm = opened(settings, first = MailResult.Ok(blocked(from = null)))
            advanceUntilIdle()

            assertEquals(blocked(from = null), vm.shown())
            coVerify(exactly = 0) { reader.message(any(), true) }
        }

    @Test
    fun `load images shows the mail again with proxied images`() =
        runTest(dispatcher) {
            val vm = opened()
            advanceUntilIdle()

            vm.loadImages()
            assertTrue(vm.uiState.value.busy)
            advanceUntilIdle()

            assertEquals(proxied(), vm.shown())
            assertFalse(vm.uiState.value.busy)
            assertNull(vm.uiState.value.notice)
            coVerify(exactly = 1) { reader.message(id(1), true) }
        }

    @Test
    fun `a failed load images keeps the mail and says so`() =
        runTest(dispatcher) {
            listOf(MailResult.Offline, MailResult.Failed(404)).forEach { failure ->
                val vm = opened(withImages = failure)
                advanceUntilIdle()

                vm.loadImages()
                advanceUntilIdle()

                assertEquals("$failure", blocked(), vm.shown())
                assertEquals("$failure", MailReadNotice.ImagesFailed, vm.uiState.value.notice)
                assertFalse(vm.uiState.value.busy)
                assertNull(vm.uiState.value.error)
            }
        }

    @Test
    fun `load images runs once while busy and not before the mail is shown`() =
        runTest(dispatcher) {
            val notYet = opened(first = MailResult.Offline)
            advanceUntilIdle()
            notYet.loadImages()
            notYet.alwaysLoadImages()
            advanceUntilIdle()
            coVerify(exactly = 0) { reader.message(any(), true) }

            val pending = CompletableDeferred<MailResult<MailMessage>>()
            coEvery { reader.message(id(1)) } returns MailResult.Ok(blocked())
            coEvery { reader.message(id(1), true) } coAnswers { pending.await() }
            val vm = readViewModel(reader, mail).also { it.open(id(1)) }
            advanceUntilIdle()

            vm.loadImages()
            advanceUntilIdle()
            vm.loadImages()
            vm.alwaysLoadImages()
            pending.complete(MailResult.Ok(proxied()))
            advanceUntilIdle()

            coVerify(exactly = 1) { reader.message(id(1), true) }
            assertEquals(proxied(), vm.shown())
        }

    @Test
    fun `images that arrive after the mail was closed or replaced are dropped`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<MailResult<MailMessage>>()
            coEvery { reader.message(id(1)) } returns MailResult.Ok(blocked())
            coEvery { reader.message(id(1), true) } coAnswers { pending.await() }
            coEvery { reader.message(id(2)) } returns MailResult.Ok(blocked(n = 2, count = 0))
            val vm = readViewModel(reader, mail).also { it.open(id(1)) }
            advanceUntilIdle()

            vm.loadImages()
            advanceUntilIdle()
            vm.open(id(2))
            advanceUntilIdle()
            pending.complete(MailResult.Offline)
            advanceUntilIdle()

            assertEquals(blocked(n = 2, count = 0), vm.shown())
            assertNull(vm.uiState.value.notice)
            assertFalse(vm.uiState.value.busy)
        }

    @Test
    fun `always for this sender adds the sender to the allowlist and loads the images`() =
        runTest(dispatcher) {
            val settings = settingsWith(UserSettings(mailImageAllowedSenders = listOf("ben@example.org")))
            coEvery { settings.update(any()) } returns SettingsUpdateResult.Updated
            val vm = opened(settings)
            advanceUntilIdle()

            vm.alwaysLoadImages()
            assertTrue(vm.uiState.value.busy)
            advanceUntilIdle()

            coVerify(exactly = 1) {
                settings.update(UserSettingsChange(mailImageAllowedSenders = listOf("ben@example.org", "anna@example.org")))
            }
            assertEquals(proxied(), vm.shown())
            assertNull(vm.uiState.value.notice)
            assertFalse(vm.uiState.value.busy)
        }

    @Test
    fun `a sender already allowed, in any case, is not saved again`() =
        runTest(dispatcher) {
            val settings = settingsWith(UserSettings(mailImageAllowedSenders = listOf("ANNA@example.org")))
            // The images of this sender were loaded on open; the button can still be tapped on the blocked copy.
            val vm = opened(settings, withImages = MailResult.Offline)
            advanceUntilIdle()
            coEvery { reader.message(id(1), true) } returns MailResult.Ok(proxied())

            vm.alwaysLoadImages()
            advanceUntilIdle()

            coVerify(exactly = 0) { settings.update(any()) }
            assertEquals(proxied(), vm.shown())
            assertNull(vm.uiState.value.notice)
        }

    @Test
    fun `a sender that could not be saved still gets this mail's images, with a notice`() =
        runTest(dispatcher) {
            listOf(
                SettingsUpdateResult.Conflict,
                SettingsUpdateResult.Rejected,
                SettingsUpdateResult.Offline,
                SettingsUpdateResult.NotLoaded,
            ).forEach { result ->
                val settings = settingsWith(UserSettings())
                coEvery { settings.update(any()) } returns result
                val vm = opened(settings)
                advanceUntilIdle()

                vm.alwaysLoadImages()
                advanceUntilIdle()

                coVerify(exactly = 1) { settings.update(UserSettingsChange(mailImageAllowedSenders = listOf("anna@example.org"))) }
                assertEquals("$result", proxied(), vm.shown())
                assertEquals("$result", MailReadNotice.SenderNotSaved, vm.uiState.value.notice)
                assertFalse("$result", vm.uiState.value.busy)
            }
        }

    @Test
    fun `settings not loaded yet start the allowlist with this sender`() =
        runTest(dispatcher) {
            val settings = settingsWith(null)
            coEvery { settings.update(any()) } returns SettingsUpdateResult.NotLoaded
            val vm = opened(settings)
            advanceUntilIdle()

            vm.alwaysLoadImages()
            advanceUntilIdle()

            coVerify(exactly = 1) { settings.update(UserSettingsChange(mailImageAllowedSenders = listOf("anna@example.org"))) }
            assertEquals(proxied(), vm.shown())
            assertEquals(MailReadNotice.SenderNotSaved, vm.uiState.value.notice)
        }

    @Test
    fun `when the images fail after always for this sender, the image failure is shown`() =
        runTest(dispatcher) {
            listOf(SettingsUpdateResult.Updated, SettingsUpdateResult.Offline).forEach { result ->
                val settings = settingsWith(UserSettings())
                coEvery { settings.update(any()) } returns result
                val vm = opened(settings, withImages = MailResult.Offline)
                advanceUntilIdle()

                vm.alwaysLoadImages()
                advanceUntilIdle()

                assertEquals("$result", blocked(), vm.shown())
                assertEquals("$result", MailReadNotice.ImagesFailed, vm.uiState.value.notice)
            }
        }

    @Test
    fun `always for this sender does nothing for a mail without a sender address`() =
        runTest(dispatcher) {
            val settings = settingsWith(UserSettings())
            val vm = opened(settings, first = MailResult.Ok(blocked(from = null)))
            advanceUntilIdle()

            vm.alwaysLoadImages()
            advanceUntilIdle()

            coVerify(exactly = 0) { settings.update(any()) }
            coVerify(exactly = 0) { reader.message(any(), true) }
            assertFalse(vm.uiState.value.busy)
            assertEquals(blocked(from = null), vm.shown())
        }
}
