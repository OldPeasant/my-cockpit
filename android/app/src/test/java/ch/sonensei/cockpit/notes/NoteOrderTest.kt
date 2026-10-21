package ch.sonensei.cockpit.notes

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** The list order (SPEC.md §8.1 "Views"): pinned first, then the most recently changed. */
class NoteOrderTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun note(
        n: Int,
        pinned: Boolean,
        updatedAt: String,
    ) = Note(id(n), null, "Note $n", "", pinned, emptyList(), Instant.parse(updatedAt), 1)

    @Test
    fun `pinned notes come first, each group newest first`() {
        val oldPinned = note(1, true, "2026-01-01T00:00:00Z")
        val newPinned = note(2, true, "2026-10-01T00:00:00Z")
        val newest = note(3, false, "2026-10-20T00:00:00Z")
        val oldest = note(4, false, "2025-12-31T23:59:59Z")

        assertEquals(
            listOf(newPinned, oldPinned, newest, oldest),
            listOf(oldest, newest, oldPinned, newPinned).sortedWith(noteOrder),
        )
    }

    @Test
    fun `a pinned note older than every other note still comes first`() {
        val pinned = note(1, true, "2000-01-01T00:00:00Z")
        val fresh = note(2, false, "2026-10-20T00:00:00Z")

        assertEquals(listOf(pinned, fresh), listOf(fresh, pinned).sortedWith(noteOrder))
    }

    @Test
    fun `notes changed at the same moment have a stable order by id`() {
        val a = note(1, false, "2026-10-20T00:00:00Z")
        val b = note(2, false, "2026-10-20T00:00:00Z")

        assertEquals(listOf(a, b), listOf(b, a).sortedWith(noteOrder))
        assertEquals(listOf(a, b), listOf(a, b).sortedWith(noteOrder))
    }

    @Test
    fun `the If-Match value is the quoted version`() {
        assertEquals("\"0\"", etag(0))
        assertEquals("\"42\"", etag(42))
    }
}
