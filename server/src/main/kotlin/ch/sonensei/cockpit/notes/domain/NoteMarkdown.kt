package ch.sonensei.cockpit.notes.domain

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import com.vladsch.flexmark.ast.AutoLink
import com.vladsch.flexmark.ast.Code
import com.vladsch.flexmark.ast.CodeBlock
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.ast.HardLineBreak
import com.vladsch.flexmark.ast.HtmlBlock
import com.vladsch.flexmark.ast.HtmlCommentBlock
import com.vladsch.flexmark.ast.HtmlEntity
import com.vladsch.flexmark.ast.HtmlInline
import com.vladsch.flexmark.ast.HtmlInlineComment
import com.vladsch.flexmark.ast.IndentedCodeBlock
import com.vladsch.flexmark.ast.MailLink
import com.vladsch.flexmark.ast.RefNode
import com.vladsch.flexmark.ast.Reference
import com.vladsch.flexmark.ast.SoftLineBreak
import com.vladsch.flexmark.ast.Text
import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.tables.TableCell
import com.vladsch.flexmark.ext.tables.TableRow
import com.vladsch.flexmark.ext.tables.TableSeparator
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Block
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.data.MutableDataSet
import com.vladsch.flexmark.util.sequence.Escaping
import java.util.UUID

/**
 * The Markdown of note bodies: plain text for search and titles (Markdown stripped with flexmark, inline links left
 * out) and the inline links `[[type:id]]`, where `type` is an [ItemType] in lower case (e.g. `[[task:0192…]]`).
 */
object NoteMarkdown {
	private val INLINE_LINK = Regex("""\[\[([A-Za-z_]+):([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})]]""")
	private val TYPES = ItemType.entries.associateBy { it.name.lowercase() }

	private val parser: Parser =
		Parser
			.builder(
				MutableDataSet().set(
					Parser.EXTENSIONS,
					listOf(
						TablesExtension.create(),
						StrikethroughExtension.create(),
						TaskListExtension.create(),
						AutolinkExtension.create(),
					),
				),
			).build()

	const val UNTITLED = "Untitled note"
	const val MAX_DERIVED_TITLE_LENGTH = 100

	/** The items linked inline, each once, in order of appearance; unknown types and invalid ids are left out. */
	fun inlineLinks(markdown: String): List<ItemRef> =
		INLINE_LINK
			.findAll(markdown)
			.mapNotNull { match ->
				val type = TYPES[match.groupValues[1].lowercase()] ?: return@mapNotNull null
				val id = runCatching { UUID.fromString(match.groupValues[2]) }.getOrNull() ?: return@mapNotNull null
				ItemRef(type, id)
			}.distinct()
			.toList()

	/** [markdown] as plain text: formatting, link targets and inline links removed, blocks on lines of their own. */
	fun plainText(markdown: String): String {
		if (markdown.isBlank()) return ""
		val document = parser.parse(markdown.replace(INLINE_LINK, ""))
		return StringBuilder()
			.also { collect(document, it, verbatim = false) }
			.lines()
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.joinToString("\n")
	}

	/**
	 * Appends the text of [node] to [out]. Not flexmark's `TextCollectingVisitor`: it miscounts backslash escapes and
	 * entities next to bold text, autolinks and hard line breaks (an `AssertionError` with assertions on, garbled text
	 * without). Escapes and entities are resolved here, except in code ([verbatim]); HTML and link reference
	 * definitions are left out; blocks and line breaks end a line, table cells are separated by a space.
	 */
	private fun collect(
		node: Node,
		out: StringBuilder,
		verbatim: Boolean,
	) {
		if (node is RefNode && !node.isDefined) {
			// Brackets without a reference definition are text, e.g. `[\[task:x]]` from a mail.
			val first = node.firstChild
			val last = node.lastChild
			if (first == null || last == null) {
				out.append(Escaping.unescapeString(node.chars.toString()))
			} else {
				out.append(Escaping.unescapeString(node.chars.baseSubSequence(node.startOffset, first.startOffset).toString()))
				node.children.forEach { collect(it, out, verbatim) }
				out.append(Escaping.unescapeString(node.chars.baseSubSequence(last.endOffset, node.endOffset).toString()))
			}
			return
		}
		when (node) {
			is Text -> out.append(if (verbatim) node.chars.toString() else Escaping.unescapeString(node.chars.toString()))
			is HtmlEntity -> out.append(Escaping.unescapeString(node.chars.toString()))
			is AutoLink -> out.append(node.text)
			is MailLink -> out.append(node.text)
			is SoftLineBreak, is HardLineBreak -> out.append('\n')
			is IndentedCodeBlock -> out.append('\n').append(node.contentChars).append('\n')
			is HtmlInline, is HtmlInlineComment, is HtmlBlock, is HtmlCommentBlock, is Reference, is TableSeparator -> Unit
			is TableCell -> {
				node.children.forEach { collect(it, out, verbatim) }
				out.append(' ')
			}
			is TableRow -> {
				node.children.forEach { collect(it, out, verbatim) }
				out.append('\n')
			}
			else -> {
				val code = verbatim || node is Code || node is CodeBlock || node is FencedCodeBlock
				if (node is Block) out.append('\n')
				node.children.forEach { collect(it, out, code) }
				if (node is Block) out.append('\n')
			}
		}
	}

	/**
	 * The title derived from a body: its first line with text, as plain text, at most [MAX_DERIVED_TITLE_LENGTH]
	 * characters (cut with `…`); [UNTITLED] when the body has no text.
	 */
	fun titleOf(markdown: String): String {
		val line =
			markdown
				.lineSequence()
				.map { plainText(it).lineSequence().firstOrNull().orEmpty() }
				.firstOrNull { it.isNotBlank() }
				?: return UNTITLED
		return if (line.length <= MAX_DERIVED_TITLE_LENGTH) line else line.take(MAX_DERIVED_TITLE_LENGTH - 1).trimEnd() + "…"
	}
}
