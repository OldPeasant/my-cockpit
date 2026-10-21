package ch.sonensei.cockpit.capture.share

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The merged manifest makes the share sheet offer the cockpit for text, images and any file, single or multiple. */
@RunWith(AndroidJUnit4::class)
class ShareManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun resolvesToShareActivity(intent: Intent): Boolean =
        context.packageManager
            .queryIntentActivities(intent.setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.name == ShareActivity::class.java.name }

    @Test
    fun `single shares of text, images and any file resolve to the share activity`() {
        for (type in listOf("text/plain", "image/jpeg", "image/png", "application/pdf", "video/mp4")) {
            assertTrue("SEND $type", resolvesToShareActivity(Intent(Intent.ACTION_SEND).setType(type)))
        }
    }

    @Test
    fun `multiple shares of text, images and any file resolve to the share activity`() {
        for (type in listOf("text/plain", "image/*", "image/png", "application/pdf", "*/*")) {
            assertTrue("SEND_MULTIPLE $type", resolvesToShareActivity(Intent(Intent.ACTION_SEND_MULTIPLE).setType(type)))
        }
    }

    @Test
    fun `other actions do not open the share sheet`() {
        assertFalse(resolvesToShareActivity(Intent(Intent.ACTION_VIEW).setType("text/plain")))
        assertFalse(resolvesToShareActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
    }

    @Test
    fun `the share activity is exported so other apps can reach it`() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, ShareActivity::class.java), 0)

        assertTrue(info.exported)
    }
}
