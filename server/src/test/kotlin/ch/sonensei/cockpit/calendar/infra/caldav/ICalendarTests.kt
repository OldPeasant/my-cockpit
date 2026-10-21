package ch.sonensei.cockpit.calendar.infra.caldav

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/** The iCalendar content-line model of the CalDAV adapter: parsing, unfolding, folding, escaping (RFC 5545 §3.1, §3.3.11). */
class ICalendarTests {
	@Test
	fun `parse reads nested components with LF or CRLF line ends, unfolds lines and skips blank and broken lines`() {
		val text =
			"BEGIN:VCALENDAR\r\n" +
				"VERSION:2.0\n" +
				"\n" +
				"no content line\r\n" +
				"BEGIN:VEVENT\r\n" +
				"UID:fold-1\r\n" +
				"SUMMARY:A long\r\n" +
				"  title\r\n" +
				"\t continued\r\n" +
				"begin:valarm\r\n" +
				"trigger:-PT5M\r\n" +
				"end:VALARM\r\n" +
				"END:VEVENT\r\n" +
				"END:VCALENDAR\r\n"

		val roots = IcsComponent.parse(text)

		roots.map { it.name } shouldContainExactly listOf("VCALENDAR")
		val calendar = roots.single()
		calendar.properties.map { it.name } shouldContainExactly listOf("VERSION")
		val event = calendar.children("VEVENT").single()
		event.text("SUMMARY") shouldBe "A long title continued"
		val alarm = event.children("VALARM").single()
		alarm.property("TRIGGER")!!.value shouldBe "-PT5M"
	}

	@Test
	fun `parse keeps quoted parameter values with colons, semicolons and commas, and several values`() {
		val line =
			"""ATTENDEE;CN="Muster, Sam; PhD";DELEGATED-FROM="mailto:a@example.test","mailto:b@example.test"""" +
				""";ROLE=CHAIR:mailto:sam@example.test"""

		val property = IcsProperty.parse(line)!!

		property.name shouldBe "ATTENDEE"
		property.value shouldBe "mailto:sam@example.test"
		property.param("cn") shouldBe "Muster, Sam; PhD"
		property.params.map { it.first } shouldContainExactly listOf("CN", "DELEGATED-FROM", "ROLE")
		property.params[1].second shouldBe "\"mailto:a@example.test\",\"mailto:b@example.test\""
		property.param("ROLE") shouldBe "CHAIR"
		// Written back as it was read.
		property.format() shouldBe line
	}

	@Test
	fun `a value may contain colons and semicolons after the first colon`() {
		val property = IcsProperty.parse("DESCRIPTION:Call: 10:00; bring notes")!!

		property.value shouldBe "Call: 10:00; bring notes"
		property.params shouldBe mutableListOf()
	}

	@Test
	fun `lines that are no content lines are not properties`() {
		IcsProperty.parse("no colon here").shouldBeNull()
		IcsProperty.parse(":value without a name").shouldBeNull()
		IcsProperty.parse("""SUMMARY;CN="unclosed:value""").shouldBeNull()
		IcsProperty.parse("SUMMARY;NOEQUALS:value").shouldBeNull()
		IcsProperty.parse("SUMMARY;CN=x").shouldBeNull()
	}

	@Test
	fun `unbalanced BEGIN and END are refused`() {
		shouldThrow<IllegalArgumentException> { IcsComponent.parse("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nEND:VCALENDAR\r\n") }
		shouldThrow<IllegalArgumentException> { IcsComponent.parse("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nEND:VEVENT\r\n") }
		shouldThrow<IllegalArgumentException> { IcsComponent.parse("END:VEVENT\r\n") }
	}

	@Test
	fun `format folds long lines at 75 octets with CRLF and a space, and parse unfolds them again`() {
		val title = "Quarterly planning with the fictitious example team ".repeat(5).trim()
		val calendar = IcsComponent("VCALENDAR").apply { components += IcsComponent("VEVENT").apply { setText("SUMMARY", title) } }

		val text = calendar.format()

		text shouldStartWith "BEGIN:VCALENDAR\r\n"
		text shouldEndWith "END:VCALENDAR\r\n"
		val lines = text.removeSuffix("\r\n").split("\r\n")
		lines.forEach { (it.toByteArray(Charsets.UTF_8).size <= 75) shouldBe true }
		lines.count { it.startsWith(" ") } shouldBe 3
		IcsComponent
			.parse(text)
			.single()
			.children("VEVENT")
			.single()
			.text("SUMMARY") shouldBe title
	}

	@Test
	fun `folding counts UTF-8 octets and never splits a multibyte character or a surrogate pair`() {
		// 2-byte umlauts, 3-byte euro signs and 4-byte emoji (surrogate pairs in Java strings), at every alignment.
		val title = "Zürich € 🎉 ".repeat(20) + "äöü".repeat(30) + "🎉".repeat(25)
		val calendar = IcsComponent("VCALENDAR").apply { setText("X-WR-CALNAME", title) }

		val text = calendar.format()

		val lines = text.removeSuffix("\r\n").split("\r\n")
		(lines.size > 5) shouldBe true
		lines.forEach { line ->
			(line.toByteArray(Charsets.UTF_8).size <= 75) shouldBe true
			Character.isLowSurrogate(line.trimStart(' ').first()) shouldBe false
			Character.isHighSurrogate(line.last()) shouldBe false
		}
		// Re-encoding loses nothing: no character was cut.
		String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8) shouldBe text
		IcsComponent.parse(text).single().text("X-WR-CALNAME") shouldBe title
	}

	@Test
	fun `a line of exactly 75 octets is not folded, one of 76 is`() {
		val exact = IcsComponent("X").apply { set("SUMMARY", "a".repeat(75 - "SUMMARY:".length)) }
		val over = IcsComponent("X").apply { set("SUMMARY", "a".repeat(76 - "SUMMARY:".length)) }

		exact.format() shouldBe "BEGIN:X\r\nSUMMARY:${"a".repeat(67)}\r\nEND:X\r\n"
		over.format() shouldBe "BEGIN:X\r\nSUMMARY:${"a".repeat(67)}\r\n a\r\nEND:X\r\n"
	}

	@Test
	fun `text values escape backslashes, semicolons, commas and line breaks, and round-trip`() {
		val text = "Room 3, floor 2; wing \\B\r\nBring notes\rand pens\nend"

		val escaped = escapeText(text)

		escaped shouldBe "Room 3\\, floor 2\\; wing \\\\B\\nBring notes\\nand pens\\nend"
		unescapeText(escaped) shouldBe "Room 3, floor 2; wing \\B\nBring notes\nand pens\nend"
		unescapeText("Line\\NNext\\,") shouldBe "Line\nNext,"
		// A trailing lone backslash stays.
		unescapeText("odd\\") shouldBe "odd\\"
	}

	@Test
	fun `a text property survives format and parse with all special characters`() {
		val description = "Agenda:\n1. Budget, costs; risks\n2. C:\\temp\\notes"
		val event = IcsComponent("VEVENT").apply { setText("DESCRIPTION", description) }

		val read = IcsComponent.parse(event.format()).single()

		read.text("DESCRIPTION") shouldBe description
	}

	@Test
	fun `raw line breaks in a value are written as escaped newlines, never as new content lines`() {
		val property = IcsProperty("X-NOTE", mutableListOf(), "first\r\nINJECTED:yes")

		property.format() shouldBe "X-NOTE:first\\nINJECTED:yes"
	}

	@Test
	fun `set replaces every property of a name where the first was, null removes it, setText escapes`() {
		val event =
			IcsComponent("VEVENT").apply {
				properties += IcsProperty("UID", mutableListOf(), "set-1")
				properties += IcsProperty("COMMENT", mutableListOf(), "one")
				properties += IcsProperty("SUMMARY", mutableListOf(), "Title")
				properties += IcsProperty("COMMENT", mutableListOf(), "two")
			}

		event.set("COMMENT", "only", listOf("LANGUAGE" to "en"))
		event.properties.map { it.name } shouldContainExactly listOf("UID", "COMMENT", "SUMMARY")
		event.property("COMMENT")!!.format() shouldBe "COMMENT;LANGUAGE=en:only"

		event.set("LOCATION", "Room 1")
		event.properties.last().name shouldBe "LOCATION"

		event.setText("SUMMARY", "A, B")
		event.property("SUMMARY")!!.value shouldBe "A\\, B"
		event.setText("SUMMARY", null)
		event.property("SUMMARY").shouldBeNull()
		event.text("SUMMARY").shouldBeNull()
		event.all("COMMENT").size shouldBe 1
	}

	@Test
	fun `setParam quotes values with special characters, keeps the position and removes with null`() {
		val property = IcsProperty("ATTENDEE", mutableListOf("CN" to "Old", "ROLE" to "CHAIR"), "mailto:kim@example.test")

		property.setParam("cn", "Demo, Kim: \"the\" boss")
		property.params shouldContainExactly listOf("cn" to "\"Demo, Kim: the boss\"", "ROLE" to "CHAIR")
		property.param("CN") shouldBe "Demo, Kim: the boss"

		property.setParam("PARTSTAT", "ACCEPTED")
		property.params.last() shouldBe ("PARTSTAT" to "ACCEPTED")

		property.setParam("ROLE", null)
		property.param("ROLE").shouldBeNull()
		property.format() shouldBe "ATTENDEE;cn=\"Demo, Kim: the boss\";PARTSTAT=ACCEPTED:mailto:kim@example.test"
	}

	@Test
	fun `quoteParam drops quotes and line breaks and quotes only when needed`() {
		quoteParam("Europe/Zurich") shouldBe "Europe/Zurich"
		quoteParam("a;b") shouldBe "\"a;b\""
		quoteParam("say \"hi\"\r\nthere") shouldBe "say hi there"
	}

	@Test
	fun `names of components, properties and parameters are case-insensitive`() {
		val calendar = IcsComponent.parse("begin:vcalendar\r\nx-wr-calname;value=text:Work\r\nend:vcalendar\r\n").single()

		calendar.name shouldBe "VCALENDAR"
		calendar.property("X-WR-CALNAME")!!.param("VALUE") shouldBe "text"
	}
}
