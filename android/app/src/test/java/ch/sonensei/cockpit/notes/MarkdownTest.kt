package ch.sonensei.cockpit.notes

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class MarkdownTest {
    private val fileId = UUID.fromString("00000000-0000-7000-8000-000000000042")

    @Test
    fun `parses headings, paragraphs, lists, checklists, quotes, code and rules`() {
        val md =
            """
            # Trip
            Pack the bag
            tonight.

            - [ ] passport
            - [x] tickets
            * snacks
            1. first

            > be early
            ---
            ```
            code *here*
            ```
            """.trimIndent()

        assertEquals(
            listOf(
                MdBlock.Heading(1, "Trip"),
                MdBlock.Paragraph("Pack the bag\ntonight."),
                MdBlock.ListItem("passport", "-", checked = false),
                MdBlock.ListItem("tickets", "-", checked = true),
                MdBlock.ListItem("snacks", "*"),
                MdBlock.ListItem("first", "1."),
                MdBlock.Quote("be early"),
                MdBlock.Rule,
                MdBlock.Code("code *here*"),
            ),
            Markdown.parse(md),
        )
    }

    @Test
    fun `images become blocks of their own, also inside a line`() {
        assertEquals(
            listOf(
                MdBlock.Paragraph("Before"),
                MdBlock.Image("", ImageSource.CockpitFile(fileId)),
                MdBlock.Paragraph("after"),
                MdBlock.Image("logo", ImageSource.Web("https://example.org/a.png")),
            ),
            Markdown.parse("Before ![](cockpit-file:$fileId) after\n\n![logo](https://example.org/a.png)"),
        )
    }

    @Test
    fun `a malformed cockpit-file reference is kept as an address`() {
        assertEquals(ImageSource.Web("cockpit-file:nope"), Markdown.imageSource("cockpit-file:nope"))
        assertEquals("https://server.invalid/api/v1/files/$fileId/content", ImageSource.CockpitFile(fileId).url())
    }

    @Test
    fun `inline emphasis, code and links`() {
        assertEquals(
            listOf(
                MdSpan("a "),
                MdSpan("bold", bold = true),
                MdSpan(" "),
                MdSpan("it", italic = true),
                MdSpan(" "),
                MdSpan("x*y", code = true),
                MdSpan(" "),
                MdSpan("site", link = "https://example.org"),
                MdSpan(" "),
                MdSpan("task:1", link = "cockpit:task:1"),
            ),
            MdInline.parse("a **bold** *it* `x*y` [site](https://example.org) [[task:1]]"),
        )
    }

    @Test
    fun `the excerpt is plain text without markers or images`() {
        assertEquals("Trip Pack now passport", plainExcerpt("# Trip\nPack **now**\n\n![](cockpit-file:$fileId)\n- [ ] passport"))
    }

    @Test
    fun `inserting an image puts it on a line of its own`() {
        assertEquals("ab\n![](x)\ncd", NoteEditorViewModel.insertImage("abcd", "![](x)", 2))
        assertEquals("![](x)\n", NoteEditorViewModel.insertImage("", "![](x)", null))
        assertEquals("ab\n![](x)\n", NoteEditorViewModel.insertImage("ab\n", "![](x)", 99))
    }
}
