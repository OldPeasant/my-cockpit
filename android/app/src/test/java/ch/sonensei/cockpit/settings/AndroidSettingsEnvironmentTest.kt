package ch.sonensei.cockpit.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.capture.queue.CaptureSyncScheduler
import ch.sonensei.cockpit.push.PushRegistrar
import ch.sonensei.cockpit.reminders.ReminderAlarms
import ch.sonensei.cockpit.reminders.ReminderNotifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.unifiedpush.android.connector.UnifiedPush
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Android side of the settings screen with the real UnifiedPush library on Robolectric's package manager: the
 * distributor list, the order of a distributor switch, the permission state and "Sync now".
 */
@RunWith(AndroidJUnit4::class)
class AndroidSettingsEnvironmentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val captureSync = mockk<CaptureSyncScheduler>(relaxed = true)
    private val notifier = mockk<ReminderNotifier>()
    private val alarms = mockk<ReminderAlarms>()
    private val pushRegistrar = mockk<PushRegistrar>(relaxed = true)

    private val environment =
        AndroidSettingsEnvironment(context, mockk<CaptureQueue>(relaxed = true), captureSync, notifier, alarms, pushRegistrar)

    @Before
    fun setUp() {
        // The library keeps its store in a process-wide singleton that outlives Robolectric's per-test app data.
        UnifiedPush.removeDistributor(context)
    }

    /** Installs a fictitious distributor app with a [label] that answers the UnifiedPush register broadcast. */
    private fun installDistributor(
        packageName: String,
        label: String,
    ) {
        val packages = shadowOf(context.packageManager)
        val app =
            ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = label
            }
        packages.installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = app
            },
        )
        val receiver = ComponentName(packageName, "$packageName.RegisterReceiver")
        packages.addReceiverIfNotPresent(receiver)
        packages.addIntentFilterForReceiver(receiver, IntentFilter(ACTION_REGISTER))
        packages.addResolveInfoForIntent(
            Intent(ACTION_REGISTER),
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        this.packageName = packageName
                        name = receiver.className
                        exported = true
                        applicationInfo = app
                    }
            },
        )
    }

    @Test
    fun `lists installed distributors by label, ignoring case, and marks the saved one`() {
        installDistributor(BETA, "Beta push")
        installDistributor(ALPHA, "alpha push")
        // Precondition: the library sees both fictitious distributors; otherwise this test proves nothing.
        assertEquals(setOf(ALPHA, BETA), UnifiedPush.getDistributors(context).toSet())
        UnifiedPush.saveDistributor(context, BETA)

        val state = environment.distributors()

        assertEquals(listOf(Distributor(ALPHA, "alpha push"), Distributor(BETA, "Beta push")), state.installed)
        assertEquals(Distributor(BETA, "Beta push"), state.selected)
    }

    @Test
    fun `no distributor installed gives an empty list and none selected`() {
        val state = environment.distributors()

        assertEquals(DistributorState(emptyList(), null), state)
    }

    @Test
    fun `installed distributors without a saved one have none selected`() {
        installDistributor(ALPHA, "alpha push")

        val state = environment.distributors()

        assertEquals(listOf(Distributor(ALPHA, "alpha push")), state.installed)
        assertNull(state.selected)
    }

    @Test
    fun `switching distributor drops the old registration first, then registers with the new one`() =
        runBlocking {
            installDistributor(ALPHA, "alpha push")
            installDistributor(BETA, "Beta push")
            UnifiedPush.saveDistributor(context, ALPHA)
            val savedAt = mutableMapOf<String, String?>()
            coEvery { pushRegistrar.deleteOnServer() } answers { savedAt["delete"] = UnifiedPush.getSavedDistributor(context) }
            every { pushRegistrar.clear() } answers { savedAt["clear"] = UnifiedPush.getSavedDistributor(context) }
            every { pushRegistrar.register() } answers { savedAt["register"] = UnifiedPush.getSavedDistributor(context) }

            environment.useDistributor(BETA)

            coVerifyOrder {
                pushRegistrar.deleteOnServer()
                pushRegistrar.clear()
                pushRegistrar.register()
            }
            // The old distributor is still saved while its registration is removed (clear unregisters at the saved
            // distributor), and the new one is saved before the new registration is made.
            assertEquals(mapOf("delete" to ALPHA, "clear" to ALPHA, "register" to BETA), savedAt)
            assertEquals(BETA, UnifiedPush.getSavedDistributor(context))
            assertEquals(BETA, environment.distributors().selected?.packageName)
        }

    @Test
    fun `a second switch waits until the first one has finished`() =
        runBlocking {
            installDistributor(ALPHA, "alpha push")
            installDistributor(BETA, "Beta push")
            UnifiedPush.saveDistributor(context, ALPHA)
            val steps = CopyOnWriteArrayList<String>()
            val firstDeleteStarted = CompletableDeferred<Unit>()
            val releaseFirstDelete = CompletableDeferred<Unit>()
            coEvery { pushRegistrar.deleteOnServer() } coAnswers {
                steps += "delete"
                if (firstDeleteStarted.complete(Unit)) releaseFirstDelete.await()
            }
            every { pushRegistrar.clear() } answers { steps += "clear" }
            every { pushRegistrar.register() } answers { steps += "register:${UnifiedPush.getSavedDistributor(context)}" }

            val first = launch(Dispatchers.Default) { environment.useDistributor(BETA) }
            withTimeout(5_000) { firstDeleteStarted.await() }
            val second = launch(Dispatchers.Default) { environment.useDistributor(ALPHA) }
            // Give an unserialized second switch time to interleave; with the lock it must not start.
            delay(300)
            assertEquals(listOf("delete"), steps.toList())

            releaseFirstDelete.complete(Unit)
            withTimeout(5_000) { joinAll(first, second) }

            assertEquals(
                listOf("delete", "clear", "register:$BETA", "delete", "clear", "register:$ALPHA"),
                steps.toList(),
            )
            assertEquals(ALPHA, UnifiedPush.getSavedDistributor(context))
        }

    @Test
    fun `choosing the distributor already in use only registers again, keeping the registration`() =
        runBlocking {
            installDistributor(ALPHA, "alpha push")
            UnifiedPush.saveDistributor(context, ALPHA)

            environment.useDistributor(ALPHA)

            coVerify(exactly = 0) { pushRegistrar.deleteOnServer() }
            verify(exactly = 0) { pushRegistrar.clear() }
            verify(exactly = 1) { pushRegistrar.register() }
            assertEquals(ALPHA, UnifiedPush.getSavedDistributor(context))
        }

    @Test
    fun `choosing a distributor when none was saved yet saves it and registers`() =
        runBlocking {
            installDistributor(ALPHA, "alpha push")

            environment.useDistributor(ALPHA)

            verify(exactly = 1) { pushRegistrar.register() }
            assertEquals(ALPHA, UnifiedPush.getSavedDistributor(context))
        }

    @Test
    fun `permissions report the notifier and the alarm state, and the exact alarm setting exists`() {
        every { notifier.canNotify() } returns false
        every { alarms.canScheduleExact } returns true

        assertEquals(
            PermissionState(notifications = false, exactAlarms = true, exactAlarmsSettingExists = true),
            environment.permissions(),
        )

        every { notifier.canNotify() } returns true
        every { alarms.canScheduleExact } returns false

        assertEquals(
            PermissionState(notifications = true, exactAlarms = false, exactAlarmsSettingExists = true),
            environment.permissions(),
        )
    }

    @Test
    @Config(sdk = [30])
    fun `before Android 12 there is no exact alarm setting to open`() {
        every { notifier.canNotify() } returns true
        every { alarms.canScheduleExact } returns true

        assertFalse(environment.permissions().exactAlarmsSettingExists)
    }

    @Test
    fun `sync now enqueues the existing capture upload work`() =
        runBlocking {
            environment.syncCapturesNow()

            coVerify(exactly = 1) { captureSync.schedule() }
        }

    @Test
    fun `the app version is read from the installed package`() {
        val version = environment.appVersion

        assertTrue("version name is set", version.name.isNotBlank())
        assertTrue("version code is positive", version.code > 0)
    }

    private companion object {
        const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
        const val ALPHA = "org.example.fictitious.zz.alpha"
        const val BETA = "org.example.fictitious.aa.beta"
    }
}
