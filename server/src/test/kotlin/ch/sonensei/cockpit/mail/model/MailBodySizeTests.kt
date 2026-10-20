package ch.sonensei.cockpit.mail.model

import ch.sonensei.cockpit.core.persistence.UuidV7
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class MailBodySizeTests {
	@Test
	fun `size counts UTF-8 bytes of both parts and treats missing parts as empty`() {
		MailBody.sizeOf(null, null) shouldBe 0
		MailBody.sizeOf("", "") shouldBe 0
		MailBody.sizeOf("abc", null) shouldBe 3
		MailBody.sizeOf(null, "<p>é</p>") shouldBe 9
		MailBody.sizeOf("😀", "山") shouldBe 4 + 3
	}

	@Test
	fun `replacing the parts recomputes the size`() {
		val body = MailBody(UuidV7.generate(), "long plain text", "<p>html</p>", Instant.parse("2026-10-20T08:00:00Z"))
		body.sizeBytes shouldBe 15L + 11L

		body.replace("ü", null)

		body.textPlain shouldBe "ü"
		body.textHtml shouldBe null
		body.sizeBytes shouldBe 2
	}
}
