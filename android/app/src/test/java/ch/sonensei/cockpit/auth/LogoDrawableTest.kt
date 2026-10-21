package ch.sonensei.cockpit.auth

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** The brand mark drawable shown on the login screen: inflates, and picks the ink for the active night mode. */
@RunWith(AndroidJUnit4::class)
class LogoDrawableTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The fill colours of the vector's paths in document order, lower-case `#aarrggbb`. */
    private fun fillColors(): List<String> {
        val parser = context.resources.getXml(R.drawable.ic_logo)
        val colors = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "path") {
                colors += parser.getAttributeValue(ANDROID_NS, "fillColor").lowercase()
            }
        }
        return colors
    }

    @Test
    fun `the logo inflates as a 64 dp square`() {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_logo)
        assertNotNull(drawable)
        val density = context.resources.displayMetrics.density
        assertEquals((64 * density).toInt(), drawable!!.intrinsicWidth)
        assertEquals(drawable.intrinsicWidth, drawable.intrinsicHeight)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `in light mode the mark uses the dark ink and the orange sky`() {
        assertEquals(listOf(LIGHT_INK, SKY, LIGHT_INK), fillColors())
    }

    @Test
    @Config(qualifiers = "night")
    fun `in dark mode the mark uses the light ink and keeps the orange sky`() {
        assertEquals(listOf(DARK_INK, SKY, DARK_INK), fillColors())
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val LIGHT_INK = "#ff14243a"
        const val DARK_INK = "#ffeef2f7"
        const val SKY = "#ffe8962e"
    }
}
