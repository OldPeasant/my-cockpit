package ch.sonensei.cockpit.calendar.agenda

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The visible labels of the hidden-entry chips. The chip's text is hidden from TalkBack (it reads the content
 * description instead), so the labels are checked on the resources the chips are built from.
 */
@RunWith(AndroidJUnit4::class)
class HiddenEntryStringsTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    @Config(qualifiers = "en-rUS")
    fun `the week label is arrow and number only, the day label says more`() {
        assertEquals("▼ 2", resources.getString(R.string.calendar_hidden_short, "▼", 2))
        assertEquals("▲ 1 earlier", resources.getQuantityString(R.plurals.calendar_hidden_earlier, 1, "▲", 1))
        assertEquals("▲ 2 earlier", resources.getQuantityString(R.plurals.calendar_hidden_earlier, 2, "▲", 2))
        assertEquals("▼ 1 later", resources.getQuantityString(R.plurals.calendar_hidden_later, 1, "▼", 1))
        assertEquals("▼ 3 later", resources.getQuantityString(R.plurals.calendar_hidden_later, 3, "▼", 3))
    }

    @Test
    @Config(qualifiers = "de-rDE")
    fun `the labels and descriptions are in German`() {
        assertEquals("▼ 2", resources.getString(R.string.calendar_hidden_short, "▼", 2))
        assertEquals("▲ 2 früher", resources.getQuantityString(R.plurals.calendar_hidden_earlier, 2, "▲", 2))
        assertEquals("▼ 1 später", resources.getQuantityString(R.plurals.calendar_hidden_later, 1, "▼", 1))
        assertEquals(
            "1 Termin früher, anzeigen",
            resources.getQuantityString(R.plurals.calendar_hidden_earlier_description, 1, 1),
        )
        assertEquals(
            "3 Termine später, anzeigen",
            resources.getQuantityString(R.plurals.calendar_hidden_later_description, 3, 3),
        )
    }
}
