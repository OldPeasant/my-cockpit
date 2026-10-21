package ch.sonensei.cockpit.smoke

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * The Phase 3 demo on an emulator, against the e2e stack on the development machine (10.0.2.2):
 * `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.e2eBaseUrl=http://10.0.2.2:<port>/`
 * (docs/ARCHITECTURE.md "Android demo script").
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class Phase3DemoEmulatorTest : Phase3Demo() {
    override val device: SmokeDevice = EmulatorDevice()
}

/** Real airplane mode, the real gallery (MediaStore), the real WorkManager and the real clock. */
private class EmulatorDevice : SmokeDevice {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val photos = mutableListOf<Uri>()

    override val baseUrl: String =
        InstrumentationRegistry.getArguments().getString(BASE_URL_ARGUMENT) ?: DEFAULT_BASE_URL
    override val reminderLeadMs = 20_000L
    override val alarmSlackMs = 30_000L

    override fun setUp() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
    }

    override fun tearDown() {
        photos.forEach { context.contentResolver.delete(it, null, null) }
    }

    override fun setOnline(online: Boolean) {
        shell("cmd connectivity airplane-mode ${if (online) "disable" else "enable"}")
        val deadline = Instant.now().plus(NETWORK_WAIT)
        while (hasInternet() != online) {
            check(Instant.now().isBefore(deadline)) { "network still ${if (online) "down" else "up"}" }
            Thread.sleep(250)
        }
    }

    override fun pause(ms: Long) = Thread.sleep(ms)

    override fun runQueuedWork() = Unit

    override fun galleryPhoto(
        name: String,
        bytes: ByteArray,
    ): Uri {
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        photos += uri
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri
    }

    override fun letAlarmGoOff(at: Instant) {
        val wait = Duration.between(Instant.now(), at)
        if (!wait.isNegative) Thread.sleep(wait.toMillis())
    }

    private fun hasInternet(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    companion object {
        const val BASE_URL_ARGUMENT = "e2eBaseUrl"

        /** The e2e stack's default port (deploy/docker-compose.e2e.yml), as seen from the emulator. */
        const val DEFAULT_BASE_URL = "http://10.0.2.2:18090/"
        val NETWORK_WAIT: Duration = Duration.ofSeconds(30)
    }
}
