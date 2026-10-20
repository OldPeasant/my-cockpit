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

class CaptureParserTests {
	private val today = LocalDate.of(2026, 3, 1)
	private val renovation = CaptureProject(UUID.randomUUID(), "Home Renovation")
	private val garden = CaptureProject(UUID.randomUUID(), "Garden")
	private val home = CaptureContext(UUID.randomUUID(), "@home")
	private val phone = CaptureContext(UUID.randomUUID(), "@Phone")
	private val errands = CaptureContext(UUID.randomUUID(), "errands")

	private fun parse(text: String): ParsedCapture = CaptureParser.parse(text, today, listOf(renovation, garden), listOf(home, phone, errands))

	@Test
	fun `plain text is the title`() {
		parse("Call the plumber") shouldBe ParsedCapture("Call the plumber")
	}

	@Test
	fun `a project is found by title, case-insensitive, with dashes or underscores for spaces`() {
		parse("Buy paint #garden").let {
			it.project shouldBe garden
			it.title shouldBe "Buy paint"
		}
		parse("Buy tiles #home-renovation").project shouldBe renovation
		parse("Buy tiles #HOME_renovation").project shouldBe renovation
	}

	@Test
	fun `an unknown project is kept as text`() {
		parse("Read #book soon") shouldBe ParsedCapture("Read #book soon")
	}

	@Test
	fun `the first known project wins, later ones stay as text`() {
		parse("#unknown #garden #home-renovation weeds").let {
			it.project shouldBe garden
			it.title shouldBe "#unknown #home-renovation weeds"
		}
	}

	@Test
	fun `contexts are found with or without their at sign and accumulate`() {
		parse("@HOME call mom @phone @errands @home").let {
			it.contexts shouldBe listOf(home, phone, errands)
			it.title shouldBe "call mom"
		}
	}

	@Test
	fun `an unknown context is kept as text`() {
		parse("Ask @office about it").let {
			it.contexts shouldBe emptyList()
			it.title shouldBe "Ask @office about it"
		}
	}

	@Test
	fun `due today, tomorrow and an ISO date`() {
		parse("Pay bill !today").dueDate shouldBe today
		parse("Pay bill !Tomorrow").dueDate shouldBe today.plusDays(1)
		parse("Pay bill !2026-11-03").let {
			it.dueDate shouldBe LocalDate.of(2026, 11, 3)
			it.title shouldBe "Pay bill"
		}
	}

	@Test
	fun `invalid dates and unknown keywords stay as text`() {
		parse("Pay !2026-13-45 bill !soon !") shouldBe ParsedCapture("Pay !2026-13-45 bill !soon !")
	}

	@Test
	fun `the first due date wins`() {
		parse("Pay !tomorrow !today").let {
			it.dueDate shouldBe today.plusDays(1)
			it.title shouldBe "Pay !today"
		}
	}

	@Test
	fun `waiting puts the task on the waiting list, a name says for whom`() {
		parse("Quote for the roof ^waiting").let {
			it.list shouldBe TaskList.WAITING
			it.waitingFor shouldBe null
			it.title shouldBe "Quote for the roof"
		}
		parse("Quote for the roof ^Anna ^Ben").let {
			it.list shouldBe TaskList.WAITING
			it.waitingFor shouldBe "Anna"
			it.title shouldBe "Quote for the roof ^Ben"
		}
	}

	@Test
	fun `tokens need whitespace before them, so urls and emails are untouched`() {
		val text = "See https://example.org/page#garden and mail me@home.example a#garden!today"
		parse(text) shouldBe ParsedCapture(text)
	}

	@Test
	fun `trailing punctuation is not part of a token`() {
		parse("Call mom @phone, tomorrow is due !tomorrow.").let {
			it.contexts shouldBe listOf(phone)
			it.dueDate shouldBe today.plusDays(1)
			it.title shouldBe "Call mom tomorrow is due"
		}
	}

	@Test
	fun `all tokens together`() {
		parse("#garden Plant tulips @home !2026-04-01 ^Anna") shouldBe
			ParsedCapture(
				title = "Plant tulips",
				list = TaskList.WAITING,
				project = garden,
				contexts = listOf(home),
				dueDate = LocalDate.of(2026, 4, 1),
				waitingFor = "Anna",
			)
	}

	@Test
	fun `the first non-blank line is the title, the rest extra notes`() {
		parse("\n  Read   article @home\n\nhttps://example.org/a\nsecond line  \n").let {
			it.title shouldBe "Read article"
			it.extraNotes shouldBe "https://example.org/a\nsecond line"
			it.contexts shouldBe listOf(home)
		}
	}

	@Test
	fun `a text of only tokens has an empty title`() {
		parse("#garden @home !today").title shouldBe ""
		parse("   \n ").title shouldBe ""
	}

	@Test
	fun `a too long first line is cut and kept in full in the notes`() {
		val line = "x".repeat(600)
		parse("$line\nmore").let {
			it.title.length shouldBe CaptureParser.MAX_TITLE_LENGTH
			it.title.endsWith("…") shouldBe true
			it.extraNotes shouldBe "$line\nmore"
		}
	}

	@Test
	fun `plain keeps the syntax as text`() {
		CaptureParser.plain("#garden Plant @home\nnotes") shouldBe ParsedCapture("#garden Plant @home", "notes")
	}
}
