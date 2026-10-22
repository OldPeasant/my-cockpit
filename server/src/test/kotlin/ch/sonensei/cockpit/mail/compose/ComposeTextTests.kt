package ch.sonensei.cockpit.mail.compose

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** The plain-text alternative of composed HTML, quoting, and plain text as HTML ([ComposeText]). */
class ComposeTextTests {
	@Nested
	inner class PlainText {
		@Test
		fun `paragraphs are separated by a blank line and inline markup is dropped`() {
			ComposeText.plainText("<p>Hello <b>Bob</b></p><p>See you <i>soon</i>.</p>") shouldBe "Hello Bob\n\nSee you soon.\n"
		}

		@Test
		fun `a line break starts a new line within a paragraph`() {
			ComposeText.plainText("<p>Line one<br>Line two</p>") shouldBe "Line one\nLine two\n"
		}

		@Test
		fun `headings are blocks of their own`() {
			ComposeText.plainText("<h2>Agenda</h2><p>Item</p>") shouldBe "Agenda\n\nItem\n"
		}

		@Test
		fun `divs are lines, not paragraphs`() {
			ComposeText.plainText("<div>First</div><div>Second</div>") shouldBe "First\nSecond\n"
		}

		@Test
		fun `list items become dash lines`() {
			ComposeText.plainText("<p>Todo:</p><ul><li>Buy milk</li><li>Call Ann</li></ul><p>Done</p>") shouldBe
				"Todo:\n\n- Buy milk\n- Call Ann\n\nDone\n"
		}

		@Test
		fun `ordered lists use dashes too`() {
			ComposeText.plainText("<ol><li>One</li><li>Two</li></ol>") shouldBe "- One\n- Two\n"
		}

		@Test
		fun `a link shows its URL after the text`() {
			ComposeText.plainText("""<p>Read <a href="https://example.test/report">the report</a> now</p>""") shouldBe
				"Read the report <https://example.test/report> now\n"
		}

		@Test
		fun `a link whose text is its URL is not repeated`() {
			ComposeText.plainText("""<p><a href="https://example.test/x">https://example.test/x</a></p>""") shouldBe
				"https://example.test/x\n"
		}

		@Test
		fun `non-http links keep only their text`() {
			ComposeText.plainText("""<p><a href="mailto:ann@example.test">Ann</a> and <a href="javascript:alert(1)">x</a></p>""") shouldBe
				"Ann and x\n"
		}

		@Test
		fun `a blockquote is quoted line by line, with empty lines as a bare marker`() {
			ComposeText.plainText("<p>Fine.</p><blockquote><p>How are you?</p><p>Bob</p></blockquote>") shouldBe
				"Fine.\n\n> How are you?\n>\n> Bob\n"
		}

		@Test
		fun `nested blockquotes are quoted twice`() {
			ComposeText.plainText("<blockquote><p>Outer</p><blockquote><p>Inner</p></blockquote></blockquote>") shouldBe
				"> Outer\n>\n> > Inner\n"
		}

		@Test
		fun `text after a blockquote is a paragraph of its own`() {
			ComposeText.plainText("<blockquote>Quoted</blockquote>Reply") shouldBe "> Quoted\n\nReply\n"
		}

		@Test
		fun `whitespace collapses as in a browser`() {
			ComposeText.plainText("<p>  Many \n\t spaces   here  </p>") shouldBe "Many spaces here\n"
		}

		@Test
		fun `whitespace in pre is kept`() {
			ComposeText.plainText("<p>Code:</p><pre>fun x() {\n    return  1\n}</pre>") shouldBe "Code:\n\nfun x() {\n    return  1\n}\n"
		}

		@Test
		fun `table cells are separated by a space, rows by a line`() {
			ComposeText.plainText("<table><tr><td>Name</td><td>Ann</td></tr><tr><td>Role</td><td>Lead</td></tr></table>") shouldBe
				"Name Ann\nRole Lead\n"
		}

		@Test
		fun `entities are decoded`() {
			ComposeText.plainText("<p>Tom &amp; Jerry &lt;3 &quot;cheese&quot;</p>") shouldBe "Tom & Jerry <3 \"cheese\"\n"
		}

		@Test
		fun `non-ASCII text is kept`() {
			ComposeText.plainText("<p>Grüezi – café ✓</p>") shouldBe "Grüezi – café ✓\n"
		}

		@Test
		fun `no more than one blank line in a row`() {
			ComposeText.plainText("<p>A</p><p></p><p></p><p>B</p>") shouldBe "A\n\nB\n"
		}

		@Test
		fun `plain text without markup comes through and ends with one newline`() {
			ComposeText.plainText("Just text") shouldBe "Just text\n"
		}

		@Test
		fun `empty HTML is an empty line`() {
			ComposeText.plainText("") shouldBe "\n"
		}
	}

	@Nested
	inner class Quote {
		@Test
		fun `every line gets a marker, empty lines a bare one`() {
			ComposeText.quote("Hello\n\nBob") shouldBe "> Hello\n>\n> Bob"
		}

		@Test
		fun `trailing newlines do not add empty quoted lines`() {
			ComposeText.quote("Hello\n\n\n") shouldBe "> Hello"
		}

		@Test
		fun `quoted lines are quoted again`() {
			ComposeText.quote("> old\nnew") shouldBe "> > old\n> new"
		}
	}

	@Nested
	inner class TextToHtml {
		@Test
		fun `paragraphs split at blank lines, single newlines become line breaks`() {
			ComposeText.textToHtml("One\nTwo\n\nThree\n") shouldBe "<p>One<br>Two</p><p>Three</p>"
		}

		@Test
		fun `markup in the text is escaped`() {
			val html = ComposeText.textToHtml("<script>alert('x')</script> & <b>bold</b>")

			html shouldNotContain "<script>"
			html shouldNotContain "<b>"
			html shouldContain "&lt;script&gt;"
			html shouldContain "&amp;"
		}

		@Test
		fun `more than one blank line is one paragraph break`() {
			ComposeText.textToHtml("A\n\n\n\nB") shouldBe "<p>A</p><p>B</p>"
		}
	}
}
