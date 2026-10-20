package ch.sonensei.cockpit.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceholderScreenTest {
    @Test
    fun placeholderTextNamesTheApp() {
        assertEquals("My Cockpit: scaffold", placeholderText("My Cockpit"))
    }
}
