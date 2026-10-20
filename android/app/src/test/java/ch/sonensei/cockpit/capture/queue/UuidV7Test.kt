package ch.sonensei.cockpit.capture.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UuidV7Test {
    @Test
    fun `ids carry version 7, the RFC variant and the timestamp`() {
        val ms = 1_792_490_400_000L
        val id = UuidV7.generate(ms)

        assertEquals(7, id.version())
        assertEquals(2, id.variant())
        assertEquals(ms, id.mostSignificantBits ushr 16)
        assertNotEquals(id, UuidV7.generate(ms))
    }

    @Test
    fun `later ids sort later`() {
        val earlier = UuidV7.generate(1_000L).toString()
        val later = UuidV7.generate(2_000L).toString()
        assertTrue(earlier < later)
    }
}
