package ch.sonensei.cockpit.push

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.unifiedpush.android.connector.UnifiedPush

/**
 * The real UnifiedPush library on Robolectric's package manager: which distributor the app picks, and that a device
 * without any distributor gets no registration (the periodic WorkManager syncs stay the only sync).
 */
@RunWith(AndroidJUnit4::class)
class LibraryUnifiedPushConnectorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val connector = LibraryUnifiedPushConnector(context)

    @Before
    fun setUp() {
        // The library keeps its store in a process-wide singleton that outlives Robolectric's per-test app data.
        UnifiedPush.removeDistributor(context)
    }

    /** Installs a fictitious distributor app that answers the UnifiedPush register broadcast. */
    private fun installDistributor(packageName: String) {
        val packages = shadowOf(context.packageManager)
        packages.installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            },
        )
        val receiver = ComponentName(packageName, "$packageName.RegisterReceiver")
        val filter = IntentFilter(ACTION_REGISTER)
        packages.addReceiverIfNotPresent(receiver)
        packages.addIntentFilterForReceiver(receiver, filter)
        packages.addResolveInfoForIntent(
            Intent(ACTION_REGISTER),
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        this.packageName = packageName
                        name = receiver.className
                        exported = true
                        applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
                    }
            },
        )
    }

    @Test
    fun `without a distributor app nothing is selected, so the app does not register`() {
        assertFalse(connector.selectDistributor())

        assertNull(UnifiedPush.getSavedDistributor(context))
    }

    @Test
    fun `the only installed distributor is selected and remembered`() {
        installDistributor(DISTRIBUTOR)
        // Precondition: the library sees the fictitious distributor; otherwise this test proves nothing.
        assertEquals(listOf(DISTRIBUTOR), UnifiedPush.getDistributors(context))

        assertTrue(connector.selectDistributor())

        assertEquals(DISTRIBUTOR, UnifiedPush.getSavedDistributor(context))
    }

    @Test
    fun `a distributor chosen before is kept`() {
        installDistributor(DISTRIBUTOR)
        installDistributor(OTHER_DISTRIBUTOR)
        UnifiedPush.saveDistributor(context, OTHER_DISTRIBUTOR)

        assertTrue(connector.selectDistributor())

        assertEquals(OTHER_DISTRIBUTOR, UnifiedPush.getSavedDistributor(context))
    }

    @Test
    fun `with several distributors and no system default the first one is taken`() {
        installDistributor(DISTRIBUTOR)
        installDistributor(OTHER_DISTRIBUTOR)
        // Android answers the link intent with its chooser: there is no default distributor.
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(LINK)),
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "android" } },
        )
        val installed = UnifiedPush.getDistributors(context)
        assertEquals(setOf(DISTRIBUTOR, OTHER_DISTRIBUTOR), installed.toSet())

        assertTrue(connector.selectDistributor())

        assertEquals(installed.first(), UnifiedPush.getSavedDistributor(context))
    }

    @Test
    fun `the system default distributor is taken over the first installed one`() {
        installDistributor(DISTRIBUTOR)
        installDistributor(OTHER_DISTRIBUTOR)
        val installed = UnifiedPush.getDistributors(context)
        assertEquals(setOf(DISTRIBUTOR, OTHER_DISTRIBUTOR), installed.toSet())
        // The user made a distributor the default: Android answers the link intent with that app, not the chooser.
        val default = installed.last()
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(LINK)),
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = default
                        name = "$default.LinkActivity"
                        exported = true
                        applicationInfo = ApplicationInfo().apply { packageName = default }
                    }
            },
        )

        assertTrue(connector.selectDistributor())

        assertEquals(default, UnifiedPush.getSavedDistributor(context))
    }

    @Test
    fun `several installed distributors without a link activity still give a registration`() {
        // Distributors that predate the link activity: the library resolves no default (NoneAvailable), yet
        // distributors are installed. The connector promises false only "when no distributor is installed", and the
        // task's assumption says several without a default -> the first installed one.
        installDistributor(DISTRIBUTOR)
        installDistributor(OTHER_DISTRIBUTOR)
        val installed = UnifiedPush.getDistributors(context)
        assertEquals(setOf(DISTRIBUTOR, OTHER_DISTRIBUTOR), installed.toSet())

        assertTrue("distributors are installed, so one must be selected", connector.selectDistributor())

        assertEquals(installed.first(), UnifiedPush.getSavedDistributor(context))
    }

    private companion object {
        const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
        const val DISTRIBUTOR = "org.example.fictitious.distributor"
        const val OTHER_DISTRIBUTOR = "org.example.fictitious.otherdistributor"
        const val LINK = "unifiedpush://link"
    }
}
