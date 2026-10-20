package ch.sonensei.cockpit.gtd.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Edge cases of the `text/event-stream` line format, fed the way the stream reads it (one line per call). */
class SseParserTest {
    private fun SseParser.feedAll(vararg lines: String): List<SseEvent> = lines.mapNotNull { feed(it) }

    @Test
    fun `a value keeps its colons and only the first space after the field is dropped`() {
        val events = SseParser().feedAll("event: change", """data: {"type":"TASK","at":"10:00"}""", "data:  indented", "")

        assertEquals(listOf(SseEvent("change", "{\"type\":\"TASK\",\"at\":\"10:00\"}\n indented")), events)
    }

    @Test
    fun `the event name does not leak into the next event`() {
        val events = SseParser().feedAll("event: change", "data: one", "", "data: two", "")

        assertEquals(listOf(SseEvent("change", "one"), SseEvent("message", "two")), events)
    }

    @Test
    fun `comments inside an event, unknown fields and repeated blank lines change nothing`() {
        val parser = SseParser()

        val events = parser.feedAll("", "", ": keep-alive", "event: change", ": still alive", "retry: 1000", "data: x", "", "")

        assertEquals(listOf(SseEvent("change", "x")), events)
    }

    @Test
    fun `an empty data value still makes an event and the last event name wins`() {
        val events = SseParser().feedAll("event: first", "event: change", "data:", "")

        assertEquals(listOf(SseEvent("change", "")), events)
    }

    @Test
    fun `a field without a value and a blank line before any data dispatch nothing`() {
        val parser = SseParser()

        assertNull(parser.feed("event"))
        assertNull(parser.feed(""))
        // The empty event name from the line above was reset by the blank line.
        assertEquals(listOf(SseEvent("message", "y")), parser.feedAll("data: y", ""))
    }
}
