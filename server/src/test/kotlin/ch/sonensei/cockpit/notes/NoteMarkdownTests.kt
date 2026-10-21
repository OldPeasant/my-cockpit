package ch.sonensei.cockpit.notes

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.notes.domain.NoteMarkdown
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.UUID

class NoteMarkdownTests {
	private val task = UUID.fromString("0192f0c0-0000-7000-8000-000000000001")
	private val note = UUID.fromString("0192f0c0-0000-7000-8000-000000000002")

	@Test
	fun `inline links are found once each, in order, regardless of type case`() {
		val body = "See [[task:$task]] and [[NOTE:$note]], again [[task:$task]]."

		NoteMarkdown.inlineLinks(body) shouldContainExactly listOf(ItemRef(ItemType.TASK, task), ItemRef(ItemType.NOTE, note))
	}

	@Test
	fun `inline links with an unknown type or a malformed id are ignored`() {
		NoteMarkdown.inlineLinks("[[widget:$task]] [[task:not-a-uuid]] [task:$task] [[task: $task]]").shouldBeEmpty()
	}

	@Test
	fun `inline links of multi-word types use the type name in lower case`() {
		NoteMarkdown.inlineLinks("[[journal_entry:$task]]") shouldContainExactly listOf(ItemRef(ItemType.JOURNAL_ENTRY, task))
	}

	@Test
	fun `plain text strips Markdown, link targets, images and inline links`() {
		val body =
			"""
			# Trip **plan**

			- [ ] Book _train_ to [Bern](https://example.org/bern)
			- [x] Pack ~~socks~~ `bag`

			![map](cockpit-file:$task) see [[task:$task]]

			| Day | Place |
			|-----|-------|
			| 1   | Thun  |
			""".trimIndent()

		val text = NoteMarkdown.plainText(body)

		text.lines().first() shouldBe "Trip plan"
		text shouldNotContain "**"
		text shouldNotContain "https://example.org"
		text shouldNotContain "cockpit-file"
		text shouldNotContain "[["
		text shouldNotContain "[ ]"
		listOf("Book train to Bern", "Pack socks bag", "Thun").forEach { (it in text) shouldBe true }
	}

	@Test
	fun `plain text of a blank body is empty`() {
		NoteMarkdown.plainText("  \n\n ") shouldBe ""
	}

	@Test
	fun `the title is the first line with text, as plain text`() {
		NoteMarkdown.titleOf("\n\n![](cockpit-file:$task)\n## Shopping *list*\n- milk") shouldBe "Shopping list"
		NoteMarkdown.titleOf("- [ ] call the plumber\nmore") shouldBe "call the plumber"
	}

	@Test
	fun `a body without text gives the untitled title`() {
		NoteMarkdown.titleOf("") shouldBe NoteMarkdown.UNTITLED
		NoteMarkdown.titleOf("![](cockpit-file:$task)\n[[task:$task]]") shouldBe NoteMarkdown.UNTITLED
	}

	@Test
	fun `a long first line is cut with an ellipsis`() {
		val title = NoteMarkdown.titleOf("x".repeat(300))

		title.length shouldBe NoteMarkdown.MAX_DERIVED_TITLE_LENGTH
		title.last() shouldBe '…'
	}
}
