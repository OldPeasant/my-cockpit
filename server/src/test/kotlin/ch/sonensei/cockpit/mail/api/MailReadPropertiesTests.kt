package ch.sonensei.cockpit.mail.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.util.unit.DataSize
import java.time.Duration

/** The read limits refuse values that would disable or break them. */
class MailReadPropertiesTests {
	private fun proxy(
		maxSize: DataSize = DataSize.ofMegabytes(5),
		maxRedirects: Int = 3,
	) = MailReadProperties.ImageProxy(maxSize, Duration.ofSeconds(5), Duration.ofSeconds(10), maxRedirects, false)

	@Test
	fun `the text size limit must be between one byte and 2 GB`() {
		MailReadProperties(DataSize.ofBytes(1), proxy()).maxTextSize shouldBe DataSize.ofBytes(1)
		MailReadProperties(DataSize.ofBytes(Int.MAX_VALUE.toLong()), proxy()).maxTextSize.toBytes() shouldBe Int.MAX_VALUE.toLong()

		shouldThrow<IllegalArgumentException> { MailReadProperties(DataSize.ofBytes(0), proxy()) }.message shouldContain
			"max-text-size"
		shouldThrow<IllegalArgumentException> { MailReadProperties(DataSize.ofBytes(Int.MAX_VALUE + 1L), proxy()) }
	}

	@Test
	fun `the image size limit must be between one byte and 2 GB`() {
		proxy(maxSize = DataSize.ofBytes(1)).maxSize shouldBe DataSize.ofBytes(1)

		shouldThrow<IllegalArgumentException> { proxy(maxSize = DataSize.ofBytes(0)) }.message shouldContain "image-proxy.max-size"
		shouldThrow<IllegalArgumentException> { proxy(maxSize = DataSize.ofBytes(Int.MAX_VALUE + 1L)) }
	}

	@Test
	fun `zero redirects are allowed, negative ones are not`() {
		proxy(maxRedirects = 0).maxRedirects shouldBe 0

		shouldThrow<IllegalArgumentException> { proxy(maxRedirects = -1) }.message shouldContain "max-redirects"
	}
}
