package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.Base32
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class Base32Tests {
	// RFC 4648 §10, without padding.
	private val vectors =
		mapOf(
			"" to "",
			"f" to "MY",
			"fo" to "MZXQ",
			"foo" to "MZXW6",
			"foob" to "MZXW6YQ",
			"fooba" to "MZXW6YTB",
			"foobar" to "MZXW6YTBOI",
		)

	@Test
	fun `RFC 4648 test vectors encode and decode`() {
		vectors.forEach { (plain, encoded) ->
			Base32.encode(plain.toByteArray()) shouldBe encoded
			String(Base32.decode(encoded)) shouldBe plain
		}
	}

	@Test
	fun `decode ignores case, spaces and padding`() {
		String(Base32.decode("mzxw 6ytb oi======")) shouldBe "foobar"
	}

	@Test
	fun `decode rejects characters outside the alphabet`() {
		shouldThrow<IllegalArgumentException> { Base32.decode("MZXW1") }
	}

	@Test
	fun `random bytes round-trip`() {
		val bytes = ByteArray(20) { (it * 37 + 11).toByte() }
		Base32.decode(Base32.encode(bytes)).contentEquals(bytes) shouldBe true
	}
}
