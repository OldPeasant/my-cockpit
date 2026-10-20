package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.gtd.domain.CaptureContext
import ch.sonensei.cockpit.gtd.domain.CaptureParser
import ch.sonensei.cockpit.gtd.domain.CaptureProject
import ch.sonensei.cockpit.gtd.domain.ParsedCapture
import ch.sonensei.cockpit.gtd.domain.TaskList
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** Edge cases of the capture syntax (gtd-capture-api) beyond [CaptureParserTests]. */
class CaptureParserEdgeCaseTests {
	private val today = LocalDate.of(2026, 12, 31)
	private val garden = CaptureProject(UUID.randomUUID(), "Garden")
	private val gardenTwin = CaptureProject(UUID.randomUUID(), "garden")
	private val renovation = CaptureProject(UUID.randomUUID(), "Home  Renovation ")
	private val home = CaptureContext(UUID.randomUUID(), " @home ")
	private val phone = CaptureContext(UUID.randomUUID(), "@phone")

	private fun parse(
		text: String,
		projects: List<CaptureProject> = listOf(garden, gardenTwin, renovation),
		contexts: List<CaptureContext> = listOf(home, phone),
	): ParsedCapture = CaptureParser.parse(text, today, projects, contexts)

	@Test
	fun `without known projects and contexts every token but due and waiting stays as text`() {
		parse("Plant #garden @home !today ^waiting", emptyList(), emptyList()).let {
			it.title shouldBe "Plant #garden @home"
			it.project shouldBe null
			it.contexts shouldBe emptyList()
			it.dueDate shouldBe today
			it.list shouldBe TaskList.WAITING
		}
	}

	@Test
	fun `the first project in order of preference wins among equal titles`() {
		parse("Weeds #GARDEN").project shouldBe garden
		parse("Weeds #garden", projects = listOf(gardenTwin, garden)).project shouldBe gardenTwin
	}

	@Test
	fun `project titles with repeated or trailing spaces still match`() {
		parse("Tiles #home-renovation").project shouldBe renovation
		parse("Tiles #home__renovation").project shouldBe renovation
	}

	@Test
	fun `context names are trimmed before matching`() {
		parse("Call @home").contexts shouldBe listOf(home)
	}

	@Test
	fun `the same context named twice in different spellings is listed once`() {
		parse("@home Call @HOME @phone @Home").let {
			it.contexts shouldBe listOf(home, phone)
			it.title shouldBe "Call"
		}
	}

	@Test
	fun `bare sigils and sigils with only punctuation stay as text`() {
		val text = "Odd # @ ! ^ #. @, !? ^)"
		parse(text) shouldBe ParsedCapture(text)
	}

	@Test
	fun `a token inside parentheses or after other characters is not a token`() {
		val text = "Call (@home) x!today y^Anna"
		parse(text) shouldBe ParsedCapture(text)
	}

	@Test
	fun `several trailing punctuation marks are stripped from the value and stay in the title`() {
		parse("Pay !tomorrow?! now").let {
			it.dueDate shouldBe today.plusDays(1)
			it.title shouldBe "Pay?! now"
		}
		parse("Ask ^Anna).").let {
			it.waitingFor shouldBe "Anna"
			it.title shouldBe "Ask)."
		}
	}

	@Test
	fun `punctuation inside a token is part of its value`() {
		parse("Call @ph.one").let {
			it.contexts shouldBe emptyList()
			it.title shouldBe "Call @ph.one"
		}
	}

	@Test
	fun `tomorrow crosses the year boundary`() {
		parse("Party !tomorrow").dueDate shouldBe LocalDate.of(2027, 1, 1)
	}

	@Test
	fun `due keywords and the waiting keyword are case-insensitive`() {
		parse("Pay !TODAY").dueDate shouldBe today
		parse("Quote ^WAITING").let {
			it.list shouldBe TaskList.WAITING
			it.waitingFor shouldBe null
			it.title shouldBe "Quote"
		}
	}

	@Test
	fun `leap days are valid only in leap years`() {
		parse("Pay !2028-02-29").dueDate shouldBe LocalDate.of(2028, 2, 29)
		parse("Pay !2027-02-29").let {
			it.dueDate shouldBe null
			it.title shouldBe "Pay !2027-02-29"
		}
	}

	@Test
	fun `dates in other formats stay as text`() {
		val text = "Pay !2026-3-1 !01.03.2026 !20260301 !2026-03-01T10:00"
		parse(text) shouldBe ParsedCapture(text)
	}

	@Test
	fun `an invalid date before a valid one does not block the valid one`() {
		parse("Pay !2026-02-30 !2026-03-02").let {
			it.dueDate shouldBe LocalDate.of(2026, 3, 2)
			it.title shouldBe "Pay !2026-02-30"
		}
	}

	@Test
	fun `an unknown project before a known one does not block the known one`() {
		parse("#nope Weeds #garden").let {
			it.project shouldBe garden
			it.title shouldBe "#nope Weeds"
		}
	}

	@Test
	fun `the waiting keyword after a name stays as text`() {
		parse("Quote ^Anna ^waiting").let {
			it.waitingFor shouldBe "Anna"
			it.title shouldBe "Quote ^waiting"
		}
	}

	@Test
	fun `a later line never sets project, contexts, due date or waiting, even when the title line has none`() {
		val body = "bulbs @home\n#garden\n!2026-04-01 from the shop ^Anna"
		parse("Plant tulips\n$body") shouldBe ParsedCapture("Plant tulips", body)
	}

	@Test
	fun `a title line of only tokens followed by a line of only tokens keeps that line unparsed as the title`() {
		parse("#garden\n@home !today\nrest").let {
			it.project shouldBe garden
			it.title shouldBe "@home !today"
			it.contexts shouldBe emptyList()
			it.dueDate shouldBe null
			it.extraNotes shouldBe "rest"
		}
	}

	@Test
	fun `a title line of only unknown tokens is the title, not skipped`() {
		parse("#unknown @office\nPlant tulips") shouldBe ParsedCapture("#unknown @office", "Plant tulips")
	}

	@Test
	fun `a title line of only tokens with no further line leaves an empty title but keeps the parse result`() {
		parse("  #garden ^Anna  \n\n  ").let {
			it.title shouldBe ""
			it.extraNotes shouldBe null
			it.project shouldBe garden
			it.waitingFor shouldBe "Anna"
			it.list shouldBe TaskList.WAITING
		}
	}

	@Test
	fun `a title taken from the next line is cut like any other title`() {
		val line = "y".repeat(CaptureParser.MAX_TITLE_LENGTH + 1)
		parse("#garden\n$line\nmore").let {
			it.project shouldBe garden
			it.title shouldBe "y".repeat(CaptureParser.MAX_TITLE_LENGTH - 1) + "…"
			it.extraNotes shouldBe "$line\nmore"
		}
	}

	@Test
	fun `tabs and windows line endings are handled like spaces and newlines`() {
		parse("Plant\t\ttulips\t@home\r\n  second\tline \r\nthird\r\n").let {
			it.title shouldBe "Plant tulips"
			it.extraNotes shouldBe "  second\tline \nthird"
			it.contexts shouldBe listOf(home)
		}
	}

	@Test
	fun `a title of exactly the maximum length is not cut, one more character is`() {
		val max = "t".repeat(CaptureParser.MAX_TITLE_LENGTH)
		parse(max) shouldBe ParsedCapture(max)

		val over = max + "u"
		parse(over).let {
			it.title shouldBe "t".repeat(CaptureParser.MAX_TITLE_LENGTH - 1) + "…"
			it.extraNotes shouldBe over
		}
	}

	@Test
	fun `a cut title does not end in a space before the ellipsis`() {
		val line = "a".repeat(CaptureParser.MAX_TITLE_LENGTH - 2) + " " + "b".repeat(10)
		parse(line).let {
			it.title shouldBe "a".repeat(CaptureParser.MAX_TITLE_LENGTH - 2) + "…"
			it.extraNotes shouldBe line
		}
	}

	@Test
	fun `a cut title keeps the cleaned line, without recognized tokens, in front of the notes`() {
		val words = "w".repeat(CaptureParser.MAX_TITLE_LENGTH + 1)
		parse("$words #garden\nbody").let {
			it.project shouldBe garden
			it.title shouldBe "w".repeat(CaptureParser.MAX_TITLE_LENGTH - 1) + "…"
			it.extraNotes shouldBe "$words\nbody"
		}
	}

	@Test
	fun `non-ascii text and names are matched case-insensitively`() {
		val cafe = CaptureProject(UUID.randomUUID(), "Café Über")
		parse("Menü prüfen #CAFÉ-über", projects = listOf(cafe)).let {
			it.project shouldBe cafe
			it.title shouldBe "Menü prüfen"
		}
	}

	@Test
	fun `plain splits lines but never parses and keeps the body verbatim`() {
		CaptureParser.plain("  \n^Anna !today\n\n rest ") shouldBe ParsedCapture("^Anna !today", " rest ")
		CaptureParser.plain("") shouldBe ParsedCapture("")
	}

	@Test
	fun `an empty text has an empty title`() {
		parse("") shouldBe ParsedCapture("")
	}
}
