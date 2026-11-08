package ch.sonensei.cockpit.calendar.agenda

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The overlay switch is remembered on the device, off until switched on. */
@RunWith(AndroidJUnit4::class)
class TaskOverlaySettingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the overlay is off by default and the choice outlives the object`() {
        assertFalse(SharedPreferencesTaskOverlaySetting(context).enabled)

        SharedPreferencesTaskOverlaySetting(context).enabled = true
        assertTrue(SharedPreferencesTaskOverlaySetting(context).enabled)

        SharedPreferencesTaskOverlaySetting(context).enabled = false
        assertFalse(SharedPreferencesTaskOverlaySetting(context).enabled)
    }

    @Test
    fun `the in-memory setting keeps its value only in the object`() {
        val setting = TaskOverlaySetting.InMemory()
        assertFalse(setting.enabled)
        setting.enabled = true
        assertTrue(setting.enabled)
        assertFalse(TaskOverlaySetting.InMemory().enabled)
    }
}
