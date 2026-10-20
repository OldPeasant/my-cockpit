package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.files.domain.FileNames
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class FileNamesTests {
	@ParameterizedTest
	@ValueSource(
		strings = [
			"..", ".", "../etc/passwd", "a/b", "..\\windows", "a\\b", "/abs", "x\u0000y", "line\nbreak", "tab\there",
			"", "   ", "\u007f",
		],
	)
	fun `path traversal, separators, control characters and blank names are rejected`(name: String) {
		val e = shouldThrow<InvalidRequestException> { FileNames.validate(name, "name") }
		e.fieldErrors.single().field shouldBe "name"
	}

	@Test
	fun `too long names are rejected, the maximum is accepted`() {
		shouldThrow<InvalidRequestException> { FileNames.validate("a".repeat(FileNames.MAX_LENGTH + 1)) }
		FileNames.validate("a".repeat(FileNames.MAX_LENGTH)).length shouldBe FileNames.MAX_LENGTH
	}

	@Test
	fun `valid names are trimmed and may contain dots and unicode`() {
		FileNames.validate("  Report 2026.pdf ") shouldBe "Report 2026.pdf"
		FileNames.validate("..hidden") shouldBe "..hidden"
		FileNames.validate("Überweisung…") shouldBe "Überweisung…"
	}

	@Test
	fun `numbered names go before the extension and stay within the maximum length`() {
		FileNames.numbered("report.pdf", 2) shouldBe "report (2).pdf"
		FileNames.numbered("Reports", 3) shouldBe "Reports (3)"
		FileNames.numbered(".bashrc", 2) shouldBe ".bashrc (2)"
		val long = FileNames.numbered("a".repeat(FileNames.MAX_LENGTH - 4) + ".txt", 12)
		long.length shouldBe FileNames.MAX_LENGTH
		long shouldEndWith " (12).txt"
	}
}
