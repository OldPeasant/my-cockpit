package ch.sonensei.cockpit.capture.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UuidV7Test {
    @Test
    fun `ids carry version 7, the RFC variant and the timestamp`() {
        val generator = UuidV7Generator()
        val ms = 1_792_490_400_000L
        val id = generator.generate(ms)

        assertEquals(7, id.version())
        assertEquals(2, id.variant())
        assertEquals(ms, id.mostSignificantBits ushr 16)
        assertNotEquals(id, generator.generate(ms))
    }

    @Test
    fun `later ids sort later`() {
        val earlier = UuidV7.generate(1_000L).toString()
        val later = UuidV7.generate(2_000L).toString()
        assertTrue(earlier < later)
    }

    @Test
    fun `ids within the same millisecond are strictly increasing and keep the timestamp`() {
        val generator = UuidV7Generator()
        val ms = 1_792_490_400_000L

        val ids = List(1_000) { generator.generate(ms) }

        assertEquals(ids.map { it.toString() }.sorted(), ids.map { it.toString() })
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertEquals(ms, it.mostSignificantBits ushr 16) }
    }

    @Test
    fun `an exhausted counter moves to the next millisecond and a clock stepping back still sorts later`() {
        val generator = UuidV7Generator()
        val ms = 1_792_490_400_000L

        // 4096 counter values per millisecond, at least 2048 of them left after the random start.
        val ids = List(5_000) { generator.generate(ms) } + generator.generate(ms - 60_000)

        assertEquals(ids.map { it.toString() }.sorted(), ids.map { it.toString() })
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.last().mostSignificantBits ushr 16 > ms)
        ids.forEach { assertEquals(7, it.version()) }
    }
}
