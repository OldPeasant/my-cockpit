package ch.sonensei.cockpit.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.capture.queue.CaptureQueueStatus
import ch.sonensei.cockpit.capture.queue.CaptureSyncScheduler
import ch.sonensei.cockpit.push.PushRegistrar
import ch.sonensei.cockpit.reminders.ReminderAlarms
import ch.sonensei.cockpit.reminders.ReminderNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.unifiedpush.android.connector.UnifiedPush
import javax.inject.Inject

/** What the system allows the app right now; read again each time the screen comes back to the front. */
data class PermissionState(
    /** Notifications can be shown: the permission (Android 13+) is granted and the app's notifications are on. */
    val notifications: Boolean,
    /** Reminders go off at the exact minute. */
    val exactAlarms: Boolean,
    /** Android has a setting for exact alarms (12+); below that they are always allowed. */
    val exactAlarmsSettingExists: Boolean,
)

/** An installed UnifiedPush distributor app, e.g. ntfy. */
data class Distributor(
    val packageName: String,
    val label: String,
)

/** The UnifiedPush distributors on this device and the one the app uses (null: none chosen). */
data class DistributorState(
    val installed: List<Distributor>,
    val selected: Distributor?,
)

data class AppVersion(
    val name: String,
    val code: Long,
)

/** The device side of the settings screen, behind an interface so the ViewModel tests need no Android services. */
interface SettingsEnvironment {
    val captureStatus: Flow<CaptureQueueStatus>

    /** Enqueues the capture upload work now (it still waits for the network). */
    suspend fun syncCapturesNow()

    fun permissions(): PermissionState

    fun distributors(): DistributorState

    /** Switches push to [packageName]: the old registration is dropped (on the server too), a new one is made. */
    suspend fun useDistributor(packageName: String)

    val appVersion: AppVersion
}

class AndroidSettingsEnvironment
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val captureQueue: CaptureQueue,
        private val captureSync: CaptureSyncScheduler,
        private val notifier: ReminderNotifier,
        private val alarms: ReminderAlarms,
        private val pushRegistrar: PushRegistrar,
    ) : SettingsEnvironment {
        /** One distributor switch at a time: an interleaved second switch could unregister what the first just registered. */
        private val switching = Mutex()

        override val captureStatus: Flow<CaptureQueueStatus> get() = captureQueue.status

        override suspend fun syncCapturesNow() = captureSync.schedule()

        override fun permissions() =
            PermissionState(
                notifications = notifier.canNotify(),
                exactAlarms = alarms.canScheduleExact,
                exactAlarmsSettingExists = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            )

        override fun distributors(): DistributorState {
            val installed = UnifiedPush.getDistributors(context).map { Distributor(it, label(it)) }.sortedBy { it.label.lowercase() }
            val saved = UnifiedPush.getSavedDistributor(context)
            return DistributorState(installed, installed.firstOrNull { it.packageName == saved })
        }

        override suspend fun useDistributor(packageName: String) =
            switching.withLock {
                withContext(Dispatchers.IO) {
                    if (UnifiedPush.getSavedDistributor(context) == packageName) {
                        pushRegistrar.register()
                        return@withContext
                    }
                    // A new instance id: a late "unregistered" callback of the old distributor then cannot delete the new
                    // registration on the server.
                    pushRegistrar.deleteOnServer()
                    pushRegistrar.clear()
                    UnifiedPush.saveDistributor(context, packageName)
                    pushRegistrar.register()
                }
            }

        override val appVersion: AppVersion by lazy {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            AppVersion(info.versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(info))
        }

        private fun label(packageName: String): String =
            try {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                packageName
            }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    @Binds
    abstract fun environment(environment: AndroidSettingsEnvironment): SettingsEnvironment
}
