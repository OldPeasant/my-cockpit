package ch.sonensei.cockpit.journal

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.notes.NoteImages
import ch.sonensei.cockpit.notes.NotePhoto
import ch.sonensei.cockpit.notes.clearFileProviderCache
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * A camera photo belongs to the day the camera was opened for, also when Android killed the app while the camera ran:
 * the recreated screen's view model shows today again, and the photo must not be added there. A stand-in registry
 * answers the camera; the state restoration stands in for the process death.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class JournalPhotoAfterRestoreRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 11, 1)
    private val yesterday = today.minusDays(1)
    private val clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val repo = mockk<JournalRepository>()
    private val images = mockk<NoteImages> { every { loader } returns mockk() }
    private val image = "![photo.jpeg](cockpit-file:00000000-0000-7000-8000-0000000000bb)"

    private val todays =
        JournalEntry(
            UUID.fromString("00000000-0000-7000-8000-000000000201"),
            today,
            "Ferry to the island",
            null,
            emptyList(),
            Instant.EPOCH,
            1,
        )
    private val yesterdays =
        JournalEntry(
            UUID.fromString("00000000-0000-7000-8000-000000000202"),
            yesterday,
            // No text: the day opens in the plain editor, so the added image is not rendered (no image loader here).
            "",
            null,
            emptyList(),
            Instant.EPOCH,
            1,
        )

    /** Remembers the camera's request and the file it was asked to write; answered by the test. */
    private val camera =
        object : ActivityResultRegistry() {
            var requestCode: Int? = null
            var output: Uri? = null

            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                this.requestCode = requestCode
                output = input as Uri
            }
        }

    @Before
    fun setUp() {
        clearFileProviderCache()
        coEvery { repo.get(today) } returns JournalResult.Ok(todays)
        coEvery { repo.get(yesterday) } returns JournalResult.Ok(yesterdays)
        coEvery { repo.days(any(), any()) } returns JournalResult.Ok(emptyMap())
        coEvery { repo.onThisDay(any()) } returns JournalResult.Ok(emptyList())
        coEvery { repo.uploadImage(any()) } returns JournalResult.Ok(image)
        coEvery { repo.saveEntry(any(), any(), any()) } answers {
            val entry = if (firstArg<LocalDate>() == today) todays else yesterdays
            JournalSaveResult.Saved(entry.copy(bodyMd = thirdArg<JournalDraft>().bodyMd, version = 2))
        }
    }

    private fun viewModel() = JournalViewModel(repo, images, CoroutineScope(Dispatchers.Main), clock)

    /** Opens yesterday in [vm] and takes a photo there; the camera has written its file but not answered yet. */
    private fun takePhotoOnYesterday(vm: JournalViewModel) {
        composeRule.runOnIdle { vm.previousDay() }
        composeRule.waitUntil(WAIT_MS) { vm.uiState.value.date == yesterday && vm.uiState.value.editable }
        composeRule.onNodeWithTag(JournalTags.CAMERA).performClick()
        composeRule.waitUntil(WAIT_MS) { camera.output != null }
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.contentResolver.openOutputStream(camera.output!!)!!.use { it.write(byteArrayOf(1, 2, 3)) }
    }

    private fun answerCamera() = composeRule.runOnIdle { camera.dispatchResult(camera.requestCode!!, true) }

    @Test
    fun `a photo taken for an earlier day is not added to today after the app was recreated`() {
        val first = viewModel()
        var vm = first
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { Route(vm) }
        takePhotoOnYesterday(first)

        // Process death: a new view model starts on today, the screen's saved state comes back.
        val recreated = viewModel()
        vm = recreated
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitUntil(WAIT_MS) { recreated.uiState.value.editable }
        assertEquals(today, recreated.uiState.value.date)

        answerCamera()
        composeRule.waitUntil(WAIT_MS) { recreated.uiState.value.message == JournalMessage.PHOTO_DAY_CHANGED }
        composeRule.waitForIdle()

        assertEquals("Ferry to the island", recreated.uiState.value.body)
        coVerify(exactly = 0) { repo.uploadImage(any()) }
        coVerify(exactly = 0) { repo.saveEntry(any(), any(), any()) }
    }

    @Test
    fun `a photo taken for an earlier day is added to that day while it is still shown`() {
        val vm = viewModel()
        composeRule.setContent { Route(vm) }
        takePhotoOnYesterday(vm)

        answerCamera()
        composeRule.waitUntil(WAIT_MS) {
            vm.uiState.value.body
                .contains(image)
        }

        assertEquals(yesterday, vm.uiState.value.date)
        coVerify(exactly = 1) { repo.uploadImage(match<NotePhoto> { it.bytes.contentEquals(byteArrayOf(1, 2, 3)) }) }
    }

    @Composable
    private fun Route(vm: JournalViewModel) {
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = camera
            }
        CockpitTheme {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { JournalRoute(viewModel = vm) }
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
