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
	fun `backslash escapes next to bold text, autolinks and hard breaks become their characters`() {
		val body = "**F:** \\[x <c@d.e>  \n**T:** \\[y <c@d.e>"

		NoteMarkdown.plainText(body) shouldBe "F: [x c@d.e\nT: [y c@d.e"
		NoteMarkdown.titleOf(body) shouldBe "F: [x c@d.e"
	}

	@Test
	fun `a mail header with escaped display names keeps every name and address`() {
		val body =
			"**From:** Doe\\, Grace [\\[task:x]] **x** <grace@example.test>  \n" +
				"**To:** Ann \\*Example\\* <ann@work.example>, Dan \\<ops\\> <dan@work.example>  \n" +
				"**Cc:** \\[ [note:y]]\\ <erin@example.test>  \n" +
				"**Date:** 2026-10-26\n\n" +
				"Next to <https://example.test/a\\_b> and https://example.test/c end\\\nnext \\` tick \\` \\\\"

		NoteMarkdown.plainText(body) shouldBe
			"From: Doe, Grace [[task:x]] x grace@example.test\n" +
			"To: Ann *Example* ann@work.example, Dan <ops> dan@work.example\n" +
			"Cc: [ [note:y]]\\ erin@example.test\n" +
			"Date: 2026-10-26\n" +
			"Next to https://example.test/a\\_b and https://example.test/c end\n" +
			"next ` tick ` \\"
	}

	@Test
	fun `entity and numeric references next to bold text, autolinks and hard breaks become their characters`() {
		val body = "**F:** &amp; <c@d.e>  \n**T:** &#91;y&#x5D; &copy; <https://example.test>  \n**N:** &nbsp;z"

		NoteMarkdown.plainText(body) shouldBe "F: & c@d.e\nT: [y] © https://example.test\nN: \u00A0z"
		NoteMarkdown.titleOf(body) shouldBe "F: & c@d.e"
	}

	@Test
	fun `brackets without a reference definition stay text`() {
		NoteMarkdown.plainText("[a] [b][c] [d][] ![e] [\\[f]] [**g**]") shouldBe "[a] [b][c] [d][] ![e] [[f]] [g]"
	}

	@Test
	fun `HTML comments are left out, inline and as blocks`() {
		NoteMarkdown.plainText("<!-- hidden \\[ -->\n\n**a** <!-- gone &amp; --> \\[b <c@d.e>") shouldBe "a  [b c@d.e"
	}

	@Test
	fun `escapes and entities stay verbatim in code`() {
		NoteMarkdown.plainText("`a \\[ &amp;` b \\[\n\n```\nc \\[ &amp;\n```\n\n    d \\[") shouldBe "a \\[ &amp; b [\nc \\[ &amp;\nd \\["
	}

	@Test
	fun `table cells are separated, HTML and reference definitions left out`() {
		NoteMarkdown.plainText("| A | B |\n|---|---|\n| 1 | 2 |\n\n<div>\nraw\n</div>\n\n[r]: https://x.test\n\n<b>x</b> [r]") shouldBe
			"A B\n1 2\nx r"
	}

	@Test
	fun `nested emphasis, strikethrough and escapes inside undefined references keep the brackets`() {
		NoteMarkdown.plainText("[**bold _it \\[x_** ~~gone~~] and [a *b*][missing] and [*c*][] and [^1]") shouldBe
			"[bold it [x gone] and [a b][missing] and [c][] and [^1]"
	}

	@Test
	fun `brackets nested in undefined references and links inside brackets keep their text`() {
		NoteMarkdown.plainText("[a [b] c] [x [y](https://example.test/y) z] a ![] b ![][nope]") shouldBe
			"[a [b] c] [x y z] a ![] b ![][nope]"
	}

	@Test
	fun `defined references, links and images keep only their text`() {
		val body =
			"[Full **ref**][r], [r], [collapsed][], ![alt *pic*][r] and " +
				"[link \\] &amp; text](https://example.test/l \"title\") ![img \\*alt\\*](https://example.test/i.png)\n\n" +
				"[r]: https://example.test/r\n[collapsed]: https://example.test/c"

		NoteMarkdown.plainText(body) shouldBe "Full ref, r, collapsed, alt pic and link ] & text img *alt*"
	}

	@Test
	fun `code spans keep escapes and entities verbatim next to bold text, links and hard breaks`() {
		val body = "**a** `\\*b\\* &lt;` \\* <c@d.e>  \n[`\\[x`](https://example.test) ``tick`\\`` ~~`\\~`~~"

		NoteMarkdown.plainText(body) shouldBe "a \\*b\\* &lt; * c@d.e\n\\[x tick`\\ \\~"
	}

	@Test
	fun `strikethrough keeps its text with escapes and entities`() {
		NoteMarkdown.plainText("~~old \\~ price &euro;~~ **new** \\~~not struck\\~~") shouldBe "old ~ price € new ~~not struck~~"
	}

	@Test
	fun `task list items keep their text without the checkbox, with escapes, entities and autolinks`() {
		val body = "- [ ] call \\[Ann\\] <ann@example.test>  \n  then **Bob** &amp; co\n- [x] ~~done~~ \\- ok\n\n* [ ] \\[ ] not a box"

		NoteMarkdown.plainText(body) shouldBe "call [Ann] ann@example.test\nthen Bob & co\ndone - ok\n[ ] not a box"
		NoteMarkdown.titleOf(body) shouldBe "call [Ann] ann@example.test"
	}

	@Test
	fun `nested lists and block quotes put every block on its own line`() {
		val body =
			"> quoted \\*text\\*\n> - item &amp; one\n>   - nested **b** \\\n>     after break\n>\n> > deeper <https://example.test/q>\n\n" +
				"1. first\n   1. inner `\\[`\n2. second"

		NoteMarkdown.plainText(body) shouldBe
			"quoted *text*\nitem & one\nnested b\nafter break\ndeeper https://example.test/q\nfirst\ninner \\[\nsecond"
	}

	@Test
	fun `code blocks inside block quotes and lists keep their lines verbatim, without the quote markers`() {
		val body = "> ```\n> x \\[\n> y &amp;\n> ```\n>\n>     ind \\[\n>     two\n\n- item\n\n      code \\*\n      more"

		NoteMarkdown.plainText(body) shouldBe "x \\[\ny &amp;\nind \\[\ntwo\nitem\ncode \\*\nmore"
	}

	@Test
	fun `a backslash before a line end breaks the line, an escaped or final backslash stays`() {
		NoteMarkdown.plainText("**a**\\\nb \\\\\nc &amp;\\\n<d@e.f>\\") shouldBe "a\nb \\\nc &\nd@e.f\\"
	}

	@Test
	fun `the title of a line ending in a backslash hard break has no stray backslash`() {
		NoteMarkdown.titleOf("**F:** \\[x <c@d.e>\\\n**T:** \\[y <c@d.e>") shouldBe "F: [x c@d.e"
	}

	@Test
	fun `entities at the start and end of lines, blocks and the body resolve, unknown ones stay`() {
		NoteMarkdown.plainText("&amp;**b**&lt;\n# &copy; head &#x41;\n&nbsp;\n<x@y.z>&gt;  \n&unknown; &#65") shouldBe
			"&b<\n© head A\nx@y.z>\n&unknown; &#65"
		NoteMarkdown.titleOf("&nbsp;\n&amp; first") shouldBe "& first"
		NoteMarkdown.titleOf("&copy;") shouldBe "©"
	}

	@Test
	fun `inline links are removed next to bold text, escapes, entities and autolinks`() {
		val body = "**Call** [[task:$task]]\\[x] &amp; [[NOTE:$note]]<a@b.c>  \n[[task:$task]]"

		NoteMarkdown.plainText(body) shouldBe "Call [x] & a@b.c"
	}

	@Test
	fun `the title of a header line with escapes, entities, an autolink and an inline link is its plain text`() {
		val body = "[[task:$task]]\n\n**From:** Doe\\, Grace &lt;ops&gt; <grace@example.test> [[note:$note]]  \n**To:** x"

		NoteMarkdown.titleOf(body) shouldBe "From: Doe, Grace <ops> grace@example.test"
	}

	@Test
	fun `the title length counts resolved escapes`() {
		NoteMarkdown.titleOf("\\[".repeat(NoteMarkdown.MAX_DERIVED_TITLE_LENGTH)) shouldBe "[".repeat(NoteMarkdown.MAX_DERIVED_TITLE_LENGTH)
		NoteMarkdown.titleOf("\\*".repeat(NoteMarkdown.MAX_DERIVED_TITLE_LENGTH + 1)) shouldBe
			"*".repeat(NoteMarkdown.MAX_DERIVED_TITLE_LENGTH - 1) + "…"
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
