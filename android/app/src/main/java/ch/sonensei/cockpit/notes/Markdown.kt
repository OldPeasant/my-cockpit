package ch.sonensei.cockpit.notes

import java.util.UUID

/**
 * The Markdown a note uses, as blocks for the read view: headings, paragraphs, lists, checklists, quotes, code and
 * images. Not a full CommonMark parser: what it does not know is shown as text, so nothing written is ever hidden.
 */
sealed interface MdBlock {
    data class Heading(
        val level: Int,
        val text: String,
    ) : MdBlock

    data class Paragraph(
        val text: String,
    ) : MdBlock

    /** One list item; [checked] is `null` for a plain item and `true`/`false` for a checklist item. */
    data class ListItem(
        val text: String,
        val marker: String,
        val checked: Boolean? = null,
        val depth: Int = 0,
        /** The index of the item's line in the source, so a checklist item can be toggled there; `-1`: unknown. */
        val line: Int = -1,
    ) : MdBlock

    data class Quote(
        val text: String,
    ) : MdBlock

    data class Code(
        val text: String,
    ) : MdBlock

    data class Image(
        val alt: String,
        val source: ImageSource,
    ) : MdBlock

    data object Rule : MdBlock
}

/** Where an image comes from: a file in the drive (`cockpit-file:{id}`) or a web address. */
sealed interface ImageSource {
    data class CockpitFile(
        val fileId: UUID,
    ) : ImageSource

    data class Web(
        val url: String,
    ) : ImageSource
}

/** An inline link being typed: `[[` at [start], the cursor at [end], [query] typed in between. */
data class LinkQuery(
    val start: Int,
    val end: Int,
    val query: String,
)

object Markdown {
    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val bullet = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val ordered = Regex("""^(\s*)(\d{1,9}[.)])\s+(.*)$""")
    private val checkbox = Regex("""^\[([ xX])]\s+(.*)$""")
    private val image = Regex("""!\[([^\]]*)]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)""")
    private val rule = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
    private val fence = Regex("""^\s{0,3}(```|~~~)""")

    /**
     * [markdown] with the checklist item on line [line] (as in [MdBlock.ListItem.line]) checked or unchecked; unchanged
     * when that line is no checklist item.
     */
    fun toggleChecklist(
        markdown: String,
        line: Int,
    ): String {
        val lines = markdown.split('\n').toMutableList()
        val text = lines.getOrNull(line) ?: return markdown
        val item = (bullet.find(text) ?: ordered.find(text)) ?: return markdown
        val rest = item.groups[3]!!
        val box = checkbox.find(rest.value) ?: return markdown
        val mark = rest.range.first + box.groups[1]!!.range.first
        val toggled = if (text[mark] == ' ') 'x' else ' '
        lines[line] = text.substring(0, mark) + toggled + text.substring(mark + 1)
        return lines.joinToString("\n")
    }

    /** The `[[query` right before [cursor] in [text] (an inline link being typed), or `null`. */
    fun linkQuery(
        text: String,
        cursor: Int,
    ): LinkQuery? {
        if (cursor < 0 || cursor > text.length) return null
        val match = linkTrigger.find(text.substring(maxOf(0, cursor - MAX_LINK_QUERY), cursor)) ?: return null
        return LinkQuery(start = cursor - match.value.length, end = cursor, query = match.groupValues[1])
    }

    /**
     * [text] with the `[[query` of [query] (and a `]]` typed right after it) replaced by `[[type:id]]`; returns the
     * text and the cursor after the link.
     */
    fun insertLink(
        text: String,
        query: LinkQuery,
        type: String,
        id: UUID,
    ): Pair<String, Int> {
        val end = if (text.startsWith("]]", query.end)) query.end + 2 else query.end
        val link = "[[$type:$id]]"
        return text.substring(0, query.start) + link + text.substring(end) to query.start + link.length
    }

    private val linkTrigger = Regex("""\[\[([^\]\n]*)$""")
    private const val MAX_LINK_QUERY = 100

    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        val quote = mutableListOf<String>()

        fun flush() {
            if (paragraph.isNotEmpty()) addText(blocks, paragraph.joinToString("\n")) { MdBlock.Paragraph(it) }
            if (quote.isNotEmpty()) blocks += MdBlock.Quote(quote.joinToString("\n"))
            paragraph.clear()
            quote.clear()
        }

        val lines = markdown.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fenceMatch = fence.find(line)
            when {
                fenceMatch != null -> {
                    flush()
                    val marker = fenceMatch.groupValues[1]
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(marker)) code += lines[i++]
                    blocks += MdBlock.Code(code.joinToString("\n"))
                }
                line.isBlank() -> flush()
                rule.matches(line) -> {
                    flush()
                    blocks += MdBlock.Rule
                }
                heading.matches(line) -> {
                    flush()
                    val (hashes, text) = heading.find(line)!!.destructured
                    blocks += MdBlock.Heading(hashes.length, text)
                }
                line.trimStart().startsWith(">") -> {
                    if (paragraph.isNotEmpty()) flush()
                    quote += line.trimStart().removePrefix(">").removePrefix(" ")
                }
                bullet.matches(line) || ordered.matches(line) -> {
                    flush()
                    val (indent, marker, rest) = (bullet.find(line) ?: ordered.find(line))!!.destructured
                    val box = checkbox.find(rest)
                    val depth = indent.replace("\t", "    ").length / 2
                    if (box != null) {
                        blocks +=
                            MdBlock.ListItem(box.groupValues[2], marker, checked = box.groupValues[1] != " ", depth = depth, line = i)
                    } else {
                        addText(blocks, rest) { MdBlock.ListItem(it, marker, depth = depth) }
                    }
                }
                quote.isNotEmpty() -> quote += line.trim()
                else -> paragraph += line.trim()
            }
            i++
        }
        flush()
        return blocks
    }

    /**
     * Adds [text] as [block]s, with each image in it as an [MdBlock.Image] of its own (in its place), so images are
     * shown full width also when they were pasted into the middle of a line.
     */
    private fun addText(
        blocks: MutableList<MdBlock>,
        text: String,
        block: (String) -> MdBlock,
    ) {
        var start = 0
        for (match in image.findAll(text)) {
            val before = text.substring(start, match.range.first).trim()
            if (before.isNotEmpty()) blocks += block(before)
            blocks += MdBlock.Image(match.groupValues[1], imageSource(match.groupValues[2]))
            start = match.range.last + 1
        }
        val rest = text.substring(start).trim()
        if (rest.isNotEmpty() || start == 0) blocks += block(rest)
    }

    /** `cockpit-file:{uuid}` is a file of the drive; anything else is loaded as a web address. */
    fun imageSource(reference: String): ImageSource {
        val id = reference.removePrefix(COCKPIT_FILE).takeIf { it != reference }
        return id?.let { runCatching { ImageSource.CockpitFile(UUID.fromString(it)) }.getOrNull() } ?: ImageSource.Web(reference)
    }

    private const val COCKPIT_FILE = "cockpit-file:"
}

/** A run of inline text with its emphasis. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    /** The link target of a `[text](url)` link or of an inline link `[[type:id]]`. */
    val link: String? = null,
)

/** Inline Markdown: `**bold**`, `*italic*`/`_italic_`, `` `code` ``, `~~strike~~`, `[text](url)` and `[[type:id]]`. */
object MdInline {
    private val token =
        Regex(
            """(`)([^`]+)`|(\*\*|__)(.+?)\3|(~~)(.+?)~~|(?<![\w*])([*_])(?!\s)(.+?)(?<!\s)\7(?![\w*])|\[\[([^\]]+)]]|\[([^\]]+)]\(([^)\s]+)\)""",
        )

    fun parse(
        text: String,
        base: MdSpan = MdSpan(""),
    ): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        var start = 0
        for (match in token.findAll(text)) {
            if (match.range.first > start) spans += base.copy(text = text.substring(start, match.range.first))
            val g = match.groups
            spans +=
                when {
                    g[1] != null -> listOf(base.copy(text = g[2]!!.value, code = true))
                    g[3] != null -> parse(g[4]!!.value, base.copy(bold = true))
                    g[5] != null -> parse(g[6]!!.value, base.copy(strike = true))
                    g[7] != null -> parse(g[8]!!.value, base.copy(italic = true))
                    g[9] != null -> listOf(base.copy(text = g[9]!!.value, link = "cockpit:${g[9]!!.value}"))
                    else -> parse(g[10]!!.value, base.copy(link = g[11]!!.value))
                }
            start = match.range.last + 1
        }
        if (start < text.length) spans += base.copy(text = text.substring(start))
        return spans.filter { it.text.isNotEmpty() }
    }
}
