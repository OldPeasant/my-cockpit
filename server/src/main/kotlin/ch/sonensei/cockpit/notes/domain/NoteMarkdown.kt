package ch.sonensei.cockpit.notes.domain

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.TextCollectingVisitor
import com.vladsch.flexmark.util.data.MutableDataSet
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
		return TextCollectingVisitor()
			.collectAndGetText(document)
			.lines()
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.joinToString("\n")
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
