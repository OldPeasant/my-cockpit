package ch.sonensei.cockpit.mail.compose

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeFilter.FilterResult
import org.jsoup.select.NodeTraversor

/** Text conversions for composing: the plain-text alternative of an HTML body, and quoting. */
internal object ComposeText {
	/**
	 * The plain-text alternative of an editor's HTML: paragraphs and headings separated by a blank line, `<br>` and
	 * other blocks on lines of their own, list items as `- `, quotes (`<blockquote>`) with `> ` before each line, links
	 * as `text <url>` when the text is not the URL itself. Whitespace collapses as in a browser, except in `<pre>`.
	 */
	fun plainText(html: String): String = render(Jsoup.parseBodyFragment(html).body()).trim('\n') + "\n"

	/** [text] with `> ` (or `>` for an empty line) before each line. */
	fun quote(text: String): String =
		text
			.trimEnd('\n')
			.lines()
			.joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }

	/** Plain text as HTML paragraphs with line breaks, escaped. */
	fun textToHtml(text: String): String =
		text
			.trimEnd('\n')
			.split(Regex("\n{2,}"))
			.joinToString("") { paragraph -> "<p>" + paragraph.lines().joinToString("<br>") { Entities.escape(it) } + "</p>" }

	fun escape(text: String): String = Entities.escape(text)

	private fun render(root: Element): String {
		val out = StringBuilder()

		fun lineBreaks(count: Int) {
			if (out.isEmpty()) return
			repeat(count - (out.length - out.trimEnd('\n').length)) { out.append('\n') }
		}

		fun atLineStart() = out.isEmpty() || out.last() == '\n'

		NodeTraversor.filter(
			object : NodeFilter {
				override fun head(
					node: Node,
					depth: Int,
				): FilterResult {
					when {
						// A quote renders its children through a nested render with itself as the root.
						node === root -> Unit
						node is TextNode -> {
							if (generateSequence(node.parent()) { it.parent() }.any { it.nodeName() == "pre" }) {
								out.append(node.wholeText)
							} else {
								val collapsed = node.wholeText.replace(WHITESPACE, " ")
								out.append(if (atLineStart() || out.last() == ' ') collapsed.trimStart(' ') else collapsed)
							}
						}
						node !is Element -> Unit
						node.normalName() == "br" -> out.append('\n')
						node.normalName() == "blockquote" -> {
							lineBreaks(2)
							out.append(quote(render(node).trim('\n'))).append('\n')
							return FilterResult.SKIP_CHILDREN
						}
						node.normalName() == "li" -> {
							lineBreaks(1)
							out.append("- ")
						}
						node.normalName() in BLOCKS -> lineBreaks(2)
						node.normalName() in LINES -> lineBreaks(1)
					}
					return FilterResult.CONTINUE
				}

				override fun tail(
					node: Node,
					depth: Int,
				): FilterResult {
					if (node !is Element) return FilterResult.CONTINUE
					when (node.normalName()) {
						in BLOCKS, "blockquote" -> lineBreaks(2)
						in LINES, "li" -> lineBreaks(1)
						"a" -> {
							val href = node.attr("href")
							if (href.startsWith("http", ignoreCase = true) && href != node.text().trim()) out.append(" <").append(href).append('>')
						}
						in CELLS -> if (!atLineStart()) out.append(' ')
					}
					return FilterResult.CONTINUE
				}
			},
			root,
		)
		return out
			.toString()
			.replace(TRAILING_BLANKS, "\n")
			.replace(EXTRA_NEWLINES, "\n\n")
	}

	private val BLOCKS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "ul", "ol", "table", "hr")
	private val LINES = setOf("div", "tr", "section", "article", "header", "footer", "figure", "figcaption", "center", "dd", "dt")
	private val CELLS = setOf("td", "th")
	private val WHITESPACE = Regex("[ \\t\\n\\r\\f]+")
	private val TRAILING_BLANKS = Regex("[ \\t\\u00A0]+\\n")
	private val EXTRA_NEWLINES = Regex("\\n{3,}")
}
