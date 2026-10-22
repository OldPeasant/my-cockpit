package ch.sonensei.cockpit.contacts

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The intents handed to the dialer and to mail apps. */
@RunWith(AndroidJUnit4::class)
class ContactIntentsTest {
    @Test
    fun `dial opens the dialer with the number, without calling`() {
        val intent = ContactIntents.dial("+41 44 123 45 67")
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel", intent.data?.scheme)
        assertEquals("+41441234567", intent.data?.schemeSpecificPart)
    }

    @Test
    fun `dialable keeps digits, a leading plus and dialer symbols only`() {
        assertEquals("+41441234567", ContactIntents.dialable("+41 (0)44 123-45-67"))
        assertEquals("0441234567", ContactIntents.dialable(" 044/123.45.67 "))
        assertEquals("*31#0791234567,12", ContactIntents.dialable("*31# 079 123 45 67,12"))
        assertEquals("4112", ContactIntents.dialable("41+12"))
    }

    @Test
    fun `mail sends to the address and only mail apps answer it`() {
        val intent = ContactIntents.mail(" anna@example.org ")
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data?.scheme)
        assertEquals("anna@example.org", intent.data?.schemeSpecificPart)
        assertArrayEquals(arrayOf("anna@example.org"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
    }

    @Test
    fun `launch starts the intent, or reports that no app handles it`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val intent = ContactIntents.dial("044 123 45 67").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertTrue(ContactIntents.launch(app, intent))
        assertEquals(intent, shadowOf(app).nextStartedActivity)

        shadowOf(app).checkActivities(true)
        assertFalse(ContactIntents.launch(app, ContactIntents.mail("anna@example.org").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
    }
}
