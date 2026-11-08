package ch.sonensei.cockpit.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** The helpers behind the list: site names, typed tags, the search and the order. */
class BookmarksModelsTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun bookmark(
        n: Int = 1,
        url: String = "https://example.org/$n",
        title: String? = "Page $n",
        displayTitle: String = title ?: hostOf(url),
        description: String? = null,
        tags: List<String> = emptyList(),
        readLater: Boolean = true,
        readAt: Instant? = null,
        createdAt: Instant = Instant.EPOCH,
    ) = Bookmark(id(n), url, title, displayTitle, description, null, tags, readLater, readAt, createdAt, 1)

    @Test
    fun `the site is the host without www, or the address itself when it has no host`() {
        assertEquals("example.org", hostOf("https://www.example.org/a?b=c"))
        assertEquals("blog.example.net", hostOf("https://blog.example.net/x"))
        // Only a leading "www." is dropped.
        assertEquals("docs.www.example.org", hostOf("https://docs.www.example.org/"))
        assertEquals("mailto:someone@example.org", hostOf("mailto:someone@example.org"))
        assertEquals("not a url", hostOf("not a url"))
        assertEquals("", hostOf(""))
    }

    @Test
    fun `typed tags are split at commas and spaces, without hash signs, each once regardless of case`() {
        assertEquals(listOf("reading", "rust", "kotlin"), parseTags("reading, #rust  kotlin"))
        assertEquals(listOf("Rust"), parseTags("Rust rust, #RUST"))
        assertEquals(listOf("deep", "work"), parseTags("##deep\twork\n"))
        assertEquals(emptyList<String>(), parseTags(""))
        assertEquals(emptyList<String>(), parseTags(" , ,# ##"))
    }

    @Test
    fun `the search needs every word somewhere in title, site, address, notes or tags, regardless of case`() {
        val article =
            bookmark(
                url = "https://www.example.org/slow-reading",
                title = "Slow Reading",
                description = "About attention",
                tags = listOf("focus"),
            )
        assertTrue(article.matches(""))
        assertTrue(article.matches("   "))
        assertTrue(article.matches("slow"))
        assertTrue(article.matches("  READING   attention "))
        assertTrue(article.matches("example.org"))
        assertTrue(article.matches("#focus"))
        assertTrue(article.matches("focus slow"))
        assertFalse(article.matches("slow rust"))
        assertFalse(article.matches("#attention"))
    }

    @Test
    fun `an untitled bookmark is found by its site`() {
        val untitled = bookmark(url = "https://blog.example.net/x", title = null)
        assertEquals("blog.example.net", untitled.displayTitle)
        assertTrue(untitled.matches("BLOG"))
        assertFalse(untitled.matches("page"))
    }

    @Test
    fun `only unread read-later bookmarks are in the queue`() {
        assertTrue(bookmark().inQueue)
        assertFalse(bookmark().read)
        assertFalse(bookmark(readAt = Instant.EPOCH).inQueue)
        assertTrue(bookmark(readAt = Instant.EPOCH).read)
        assertFalse(bookmark(readLater = false).inQueue)
        assertEquals("example.org", bookmark(url = "https://www.example.org/").site)
    }

    @Test
    fun `the order is newest first, then by id`() {
        val old = bookmark(1, createdAt = Instant.parse("2026-10-01T09:00:00Z"))
        val newer = bookmark(2, createdAt = Instant.parse("2026-10-02T09:00:00Z"))
        val sameTime = bookmark(3, createdAt = Instant.parse("2026-10-02T09:00:00Z"))
        assertEquals(listOf(sameTime, newer, old), listOf(old, newer, sameTime).sortedWith(bookmarkOrder))
    }

    @Test
    fun `the If-Match value is the quoted version`() {
        assertEquals("\"42\"", etag(42))
    }

    @Test
    fun `a web address is an absolute http or https address with a host`() {
        assertTrue(isWebAddress("https://example.org"))
        assertTrue(isWebAddress("  HTTP://example.org/a?b=c  "))
        assertFalse(isWebAddress(""))
        assertFalse(isWebAddress("   "))
        assertFalse(isWebAddress("example.org"))
        assertFalse(isWebAddress("ftp://example.org/file"))
        assertFalse(isWebAddress("mailto:someone@example.org"))
        assertFalse(isWebAddress("javascript:alert(1)"))
        assertFalse(isWebAddress("https://"))
        assertFalse(isWebAddress("https:///path-only"))
        assertFalse(isWebAddress("https://exa mple.org"))
    }

    @Test
    fun `a link in the readable copy is resolved against the page, and only web addresses are opened`() {
        val page = "https://example.org/articles/slow-reading"
        assertEquals("https://example.org/articles/next", resolveLink(page, "next"))
        assertEquals("https://example.org/about", resolveLink(page, " /about "))
        assertEquals("https://other.example.net/x", resolveLink(page, "https://other.example.net/x"))
        assertEquals("https://cdn.example.org/a", resolveLink(page, "//cdn.example.org/a"))
        assertEquals(null, resolveLink(page, "javascript:alert(1)"))
        assertEquals(null, resolveLink(page, "mailto:someone@example.org"))
        assertEquals(null, resolveLink(page, "not a link"))
        // A page address that is no web address cannot make a relative link one.
        assertEquals(null, resolveLink("not a page", "next"))
    }
}
