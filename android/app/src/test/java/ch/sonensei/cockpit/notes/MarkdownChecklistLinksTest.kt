package ch.sonensei.cockpit.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** The Markdown helpers behind the preview checklist toggle and the `[[` link autocomplete. */
class MarkdownChecklistLinksTest {
    private val taskId = UUID.fromString("00000000-0000-7000-8000-000000000077")

    @Test
    fun `toggleChecklist checks an unchecked item and unchecks a checked one`() {
        val md = "Trip\n- [ ] passport\n- [x] tickets"
        assertEquals("Trip\n- [x] passport\n- [x] tickets", Markdown.toggleChecklist(md, 1))
        assertEquals("Trip\n- [ ] passport\n- [ ] tickets", Markdown.toggleChecklist(md, 2))
    }

    @Test
    fun `toggleChecklist unchecks an uppercase X and keeps indentation and other markers`() {
        assertEquals("  * [ ] snacks", Markdown.toggleChecklist("  * [X] snacks", 0))
        assertEquals("+ [x] water", Markdown.toggleChecklist("+ [ ] water", 0))
    }

    @Test
    fun `toggleChecklist works on ordered checklist items`() {
        assertEquals("1. [x] first\n2) [ ] second", Markdown.toggleChecklist("1. [ ] first\n2) [ ] second", 0))
        assertEquals("1. [ ] first\n2) [x] second", Markdown.toggleChecklist("1. [ ] first\n2) [ ] second", 1))
    }

    @Test
    fun `toggleChecklist leaves non-checklist lines and out-of-range lines unchanged`() {
        val md = "Plain text\n- loose item\n1. step\n- [ ] real"
        assertEquals(md, Markdown.toggleChecklist(md, 0))
        assertEquals(md, Markdown.toggleChecklist(md, 1))
        assertEquals(md, Markdown.toggleChecklist(md, 2))
        assertEquals(md, Markdown.toggleChecklist(md, 4))
        assertEquals(md, Markdown.toggleChecklist(md, -1))
        assertEquals("", Markdown.toggleChecklist("", 0))
    }

    @Test
    fun `parse records the source line of each checklist item, so toggling it hits that line`() {
        val md = "# Trip\n\nIntro\n- [ ] passport\n  - [x] tickets"
        val items = Markdown.parse(md).filterIsInstance<MdBlock.ListItem>()
        assertEquals(listOf(3, 4), items.map { it.line })
        assertEquals("# Trip\n\nIntro\n- [ ] passport\n  - [ ] tickets", Markdown.toggleChecklist(md, items[1].line))
    }

    @Test
    fun `linkQuery finds the text typed after the last open brackets before the cursor`() {
        assertEquals(LinkQuery(start = 4, end = 9, query = "foo"), Markdown.linkQuery("See [[foo", 9))
        assertEquals(LinkQuery(start = 0, end = 2, query = ""), Markdown.linkQuery("[[", 2))
        assertEquals(LinkQuery(start = 0, end = 4, query = "fl"), Markdown.linkQuery("[[flights later", 4))
        assertEquals(LinkQuery(start = 0, end = 5, query = "a b"), Markdown.linkQuery("[[a b]]", 5))
        assertEquals(LinkQuery(start = 12, end = 15, query = "x"), Markdown.linkQuery("[[task:1]]  [[x", 15))
    }

    @Test
    fun `linkQuery is null without open brackets, after a closed link, across a newline or a closing bracket`() {
        assertNull(Markdown.linkQuery("plain text", 5))
        assertNull(Markdown.linkQuery("[single", 7))
        assertNull(Markdown.linkQuery("[[task:x]] more", 15))
        assertNull(Markdown.linkQuery("[[foo\nbar", 9))
        assertNull(Markdown.linkQuery("[[foo]", 6))
    }

    @Test
    fun `linkQuery is null for a cursor outside the text`() {
        assertNull(Markdown.linkQuery("[[foo", -1))
        assertNull(Markdown.linkQuery("[[foo", 6))
        assertNull(Markdown.linkQuery("", 0))
        assertEquals("foo", Markdown.linkQuery("[[foo", 5)?.query)
    }

    @Test
    fun `linkQuery only looks back a limited distance`() {
        val long = "[[" + "a".repeat(200)
        assertNull(Markdown.linkQuery(long, long.length))
    }

    @Test
    fun `insertLink replaces the typed query with the link and puts the cursor after it`() {
        val text = "See [[fli and more"
        val query = Markdown.linkQuery(text, 9)!!
        val link = "[[task:$taskId]]"
        assertEquals("See $link and more" to 4 + link.length, Markdown.insertLink(text, query, "task", taskId))
    }

    @Test
    fun `insertLink also swallows closing brackets typed right after the query`() {
        val text = "See [[fli]] and more"
        val query = Markdown.linkQuery(text, 9)!!
        val link = "[[task:$taskId]]"
        assertEquals("See $link and more" to 4 + link.length, Markdown.insertLink(text, query, "task", taskId))
    }

    @Test
    fun `insertLink keeps a single closing bracket that is not a full closing pair`() {
        val text = "[[fli] x"
        val query = LinkQuery(start = 0, end = 5, query = "fli")
        val link = "[[note:$taskId]]"
        assertEquals("$link] x" to link.length, Markdown.insertLink(text, query, "note", taskId))
    }
}
