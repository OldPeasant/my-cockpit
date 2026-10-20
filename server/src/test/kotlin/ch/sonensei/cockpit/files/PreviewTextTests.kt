package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.files.domain.PreviewService
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PreviewTextTests {
	private fun boundary(
		text: String,
		limit: Int,
	) = PreviewService.utf8Boundary(text.toByteArray(), limit)

	@Test
	fun `ascii is cut exactly at the limit`() {
		boundary("abcdef", 3) shouldBe 3
	}

	@Test
	fun `a cut inside a multi-byte character moves to its start`() {
		// "a€b": a(1) €(3: E2 82 AC) b(1)
		boundary("a€b", 1) shouldBe 1
		boundary("a€b", 2) shouldBe 1
		boundary("a€b", 3) shouldBe 1
		boundary("a€b", 4) shouldBe 4
		// "😀" is 4 bytes
		boundary("x😀", 4) shouldBe 1
		boundary("x😀", 5) shouldBe 5
		boundary("ü", 1) shouldBe 0
	}

	@Test
	fun `stray continuation bytes do not move the cut indefinitely`() {
		val bytes = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte())
		PreviewService.utf8Boundary(bytes, 5) shouldBe 5
	}

	@Test
	fun `mime types are normalized and text types recognized`() {
		PreviewService.baseMime("Text/Markdown; charset=UTF-8") shouldBe "text/markdown"
		PreviewService.isText("text/csv") shouldBe true
		PreviewService.isText("application/json") shouldBe true
		PreviewService.isText("application/pdf") shouldBe false
	}
}
