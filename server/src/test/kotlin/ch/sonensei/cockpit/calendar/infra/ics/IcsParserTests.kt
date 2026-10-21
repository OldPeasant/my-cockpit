package ch.sonensei.cockpit.calendar.infra.ics

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The tolerant content-line reader: folding, parameters, nesting, escapes and the broken feeds it must survive. */
class IcsParserTests {
	private fun single(text: String): IcsComponent = IcsParser.parse(text).single()

	private fun crlf(text: String) = text.trimIndent().replace("\n", "\r\n")

	@Test
	fun `nests components and keeps properties in feed order`() {
		val calendar =
			single(
				crlf(
					"""
					BEGIN:VCALENDAR
					VERSION:2.0
					PRODID:-//Fictitious Org//Test//EN
					BEGIN:VEVENT
					UID:one@example.test
					SUMMARY:First
					BEGIN:VALARM
					TRIGGER:-PT5M
					END:VALARM
					END:VEVENT
					BEGIN:VEVENT
					UID:two@example.test
					END:VEVENT
					END:VCALENDAR
					""",
				),
			)

		calendar.name shouldBe "VCALENDAR"
		calendar.properties.map { it.name } shouldContainExactly listOf("VERSION", "PRODID")
		val events = calendar.children("VEVENT")
		events.map { it.first("UID")?.value } shouldContainExactly listOf("one@example.test", "two@example.test")
		events[0]
			.children("VALARM")
			.single()
			.first("TRIGGER")
			?.value shouldBe "-PT5M"
		events[1].components.shouldBeEmpty()
	}

	@Test
	fun `unfolds lines continued with a space or a tab, with CRLF and with bare LF`() {
		val crlfText = "BEGIN:VEVENT\r\nDESCRIPTION:Line one an\r\n d still one\r\n\tand more\r\nEND:VEVENT\r\n"
		val lfText = "BEGIN:VEVENT\nDESCRIPTION:Line one an\n d still one\n\tand more\nEND:VEVENT\n"

		for (text in listOf(crlfText, lfText)) {
			single(text).first("DESCRIPTION")?.value shouldBe "Line one and still oneand more"
		}
	}

	@Test
	fun `a folded line splits inside a parameter or the property name`() {
		val event = single("BEGIN:VEVENT\r\nDTST\r\n ART;TZ\r\n ID=Europe/Zurich:20261105T\r\n 090000\r\nEND:VEVENT")

		val start = event.first("DTSTART").shouldNotBeNull()
		start.param("TZID") shouldBe "Europe/Zurich"
		start.value shouldBe "20261105T090000"
	}

	@Test
	fun `strips a byte order mark and ignores blank lines`() {
		val calendar = single("\uFEFFBEGIN:VCALENDAR\r\n\r\n   \r\nX-WR-CALNAME:Holidays\r\nEND:VCALENDAR\r\n")

		calendar.name shouldBe "VCALENDAR"
		calendar.first("X-WR-CALNAME")?.value shouldBe "Holidays"
	}

	@Test
	fun `names, parameter names and component names are upper-cased, values are kept`() {
		val event = single("begin:vevent\r\nsummary;language=de-CH:Znüni Mit Team\r\nend:vevent")

		event.name shouldBe "VEVENT"
		val summary = event.first("SUMMARY").shouldNotBeNull()
		summary.param("LANGUAGE") shouldBe "de-CH"
		summary.value shouldBe "Znüni Mit Team"
	}

	@Test
	fun `quoted parameter values may hold colons, semicolons and commas, and lose their quotes`() {
		val event =
			single(
				crlf(
					"""
					BEGIN:VEVENT
					ATTENDEE;CN="Muster, Erika";DELEGATED-FROM="mailto:a@example.test":mailto:erika@example.test
					X-THING;X-P="a;b:c";X-Q=plain:the:value:with:colons
					END:VEVENT
					""",
				),
			)

		val attendee = event.first("ATTENDEE").shouldNotBeNull()
		attendee.param("CN") shouldBe "Muster, Erika"
		attendee.param("DELEGATED-FROM") shouldBe "mailto:a@example.test"
		attendee.value shouldBe "mailto:erika@example.test"
		val thing = event.first("X-THING").shouldNotBeNull()
		thing.param("X-P") shouldBe "a;b:c"
		thing.param("X-Q") shouldBe "plain"
		thing.value shouldBe "the:value:with:colons"
	}

	@Test
	fun `a parameter without a value is empty, the first of duplicate parameters wins, an empty value is kept`() {
		val event = single("BEGIN:VEVENT\r\nX-A;FLAG;X-P=one;X-P=two:\r\nEND:VEVENT")

		val property = event.first("X-A").shouldNotBeNull()
		property.param("FLAG") shouldBe ""
		property.param("X-P") shouldBe "one"
		property.value shouldBe ""
	}

	@Test
	fun `lines without a colon, without a name, or with an unterminated quote are skipped`() {
		val event =
			single(
				crlf(
					"""
					BEGIN:VEVENT
					THIS LINE IS GARBAGE
					:no name here
					X-BROKEN;X-P="never closed:value
					SUMMARY:Survivor
					END:VEVENT
					""",
				),
			)

		event.properties.map { it.name } shouldContainExactly listOf("SUMMARY")
	}

	@Test
	fun `an END without its BEGIN is ignored and unclosed components are closed at the end`() {
		val roots =
			IcsParser.parse(
				crlf(
					"""
					END:VEVENT
					BEGIN:VCALENDAR
					BEGIN:VEVENT
					UID:open@example.test
					END:VTODO
					SUMMARY:Still in the event
					""",
				),
			)

		val calendar = roots.single()
		val event = calendar.children("VEVENT").single()
		event.first("UID")?.value shouldBe "open@example.test"
		event.first("SUMMARY")?.value shouldBe "Still in the event"
		// Closed only by the end of the text: not ended.
		calendar.ended shouldBe false
		event.ended shouldBe false
	}

	@Test
	fun `an END of an outer component also closes the inner ones`() {
		val roots =
			IcsParser.parse(
				crlf(
					"""
					BEGIN:VCALENDAR
					BEGIN:VEVENT
					UID:unclosed@example.test
					END:VCALENDAR
					BEGIN:VCALENDAR
					X-WR-CALNAME:Second
					END:VCALENDAR
					""",
				),
			)

		roots.map { it.name } shouldContainExactly listOf("VCALENDAR", "VCALENDAR")
		roots[0]
			.children("VEVENT")
			.single()
			.first("UID")
			?.value shouldBe "unclosed@example.test"
		roots[1].first("X-WR-CALNAME")?.value shouldBe "Second"
		// Each calendar was ended by its own END; the event only by its calendar's.
		roots.map { it.ended } shouldContainExactly listOf(true, true)
		roots[0].children("VEVENT").single().ended shouldBe false
	}

	@Test
	fun `properties outside any component are dropped and empty input has no components`() {
		IcsParser.parse("SUMMARY:Orphan\r\n").shouldBeEmpty()
		IcsParser.parse("").shouldBeEmpty()
		IcsParser.parse("not an ics file at all").shouldBeEmpty()
	}

	@Test
	fun `text values stay escaped until unescaped`() {
		val event = single("BEGIN:VEVENT\r\nDESCRIPTION:One\\, two\\; three\\nfour\r\nEND:VEVENT")

		event.first("DESCRIPTION")?.value shouldBe "One\\, two\\; three\\nfour"
	}

	@Test
	fun `unescape resolves newlines, commas, semicolons and backslashes`() {
		IcsParser.unescape("One\\, two\\; three\\nfour\\Nfive") shouldBe "One, two; three\nfour\nfive"
		IcsParser.unescape("C:\\\\temp\\\\new") shouldBe "C:\\temp\\new"
		IcsParser.unescape("plain text") shouldBe "plain text"
	}

	@Test
	fun `unescape keeps a trailing backslash and drops the backslash of an unknown escape`() {
		IcsParser.unescape("ends with \\") shouldBe "ends with \\"
		IcsParser.unescape("\\x") shouldBe "x"
	}

	@Test
	fun `a continuation line at the very start is read as a line of its own, empty parameter names are ignored`() {
		val roots = IcsParser.parse(" BEGIN:VEVENT\r\nX-A;=orphan;;X-P=kept:value\r\nX-B;X-P=a\r\nEND:VEVENT")

		val event = roots.single()
		event.name shouldBe "VEVENT"
		val property = event.first("X-A").shouldNotBeNull()
		property.parameters shouldBe mapOf("X-P" to "kept")
		property.value shouldBe "value"
		event.first("X-B").shouldBeNull()
	}

	@Test
	fun `param of a missing parameter is null`() {
		single("BEGIN:VEVENT\r\nSUMMARY:Plain\r\nEND:VEVENT").first("SUMMARY")?.param("TZID").shouldBeNull()
	}
}
