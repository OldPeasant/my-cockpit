package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.Totp
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class TotpTests {
	// RFC 6238 Appendix B: 8-digit codes for the ASCII seeds of the reference implementation.
	private val times = listOf(59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L)
	private val vectors =
		mapOf(
			TimeBasedOneTimePasswordGenerator.TOTP_ALGORITHM_HMAC_SHA1 to
				("12345678901234567890" to listOf("94287082", "07081804", "14050471", "89005924", "69279037", "65353130")),
			TimeBasedOneTimePasswordGenerator.TOTP_ALGORITHM_HMAC_SHA256 to
				(
					"12345678901234567890123456789012" to
						listOf("46119246", "68084774", "67062674", "91819424", "90698825", "77737706")
				),
			TimeBasedOneTimePasswordGenerator.TOTP_ALGORITHM_HMAC_SHA512 to
				(
					"1234567890123456789012345678901234567890123456789012345678901234" to
						listOf("90693936", "25091201", "99943326", "93441116", "38618901", "47863826")
				),
		)

	@Test
	fun `RFC 6238 test vectors pass`() {
		vectors.forEach { (algorithm, seedAndCodes) ->
			val (seed, codes) = seedAndCodes
			val totp = Totp(digits = 8, algorithm = algorithm)
			times.zip(codes).forEach { (time, code) ->
				withClue("$algorithm at $time") {
					totp.generate(seed.toByteArray(), Instant.ofEpochSecond(time)) shouldBe code
					totp.verify(seed.toByteArray(), code, Instant.ofEpochSecond(time)) shouldBe true
				}
			}
		}
	}

	@Test
	fun `default codes have 6 digits and are zero-padded`() {
		val totp = Totp()
		val seed = "12345678901234567890".toByteArray()
		// The 6-digit code is the last 6 digits of the 8-digit RFC vector (07081804 -> 081804).
		totp.generate(seed, Instant.ofEpochSecond(1111111109)) shouldBe "081804"
		totp.generate(seed, Instant.ofEpochSecond(59)) shouldBe "287082"
	}

	@Test
	fun `verify accepts one step of drift and rejects more`() {
		val totp = Totp()
		val seed = Totp.newSecret()
		val now = Instant.parse("2026-10-20T10:00:15Z")
		val code = totp.generate(seed, now)

		totp.verify(seed, code, now) shouldBe true
		totp.verify(seed, code, now.plus(Duration.ofSeconds(30))) shouldBe true
		totp.verify(seed, code, now.minus(Duration.ofSeconds(30))) shouldBe true
		totp.verify(seed, code, now.plus(Duration.ofSeconds(90))) shouldBe false
		totp.verify(seed, code, now.minus(Duration.ofSeconds(90))) shouldBe false
	}

	@Test
	fun `verify rejects malformed codes and accepts spaces`() {
		val totp = Totp()
		val seed = Totp.newSecret()
		val now = Instant.parse("2026-10-20T10:00:15Z")
		val code = totp.generate(seed, now)

		totp.verify(seed, "${code.take(3)} ${code.drop(3)}", now) shouldBe true
		totp.verify(seed, "", now) shouldBe false
		totp.verify(seed, code.take(5), now) shouldBe false
		totp.verify(seed, code + "0", now) shouldBe false
		totp.verify(seed, "abcdef", now) shouldBe false
	}

	@Test
	fun `verify rejects six-character codes with signs or punctuation`() {
		val totp = Totp()
		val seed = Totp.newSecret()
		val now = Instant.parse("2026-10-20T10:00:15Z")
		val code = totp.generate(seed, now)

		totp.verify(seed, "-${code.drop(1)}", now) shouldBe false
		totp.verify(seed, "+${code.drop(1)}", now) shouldBe false
		totp.verify(seed, "${code.take(5)}/", now) shouldBe false
		totp.verify(seed, "${code.take(5)}:", now) shouldBe false
	}

	@Test
	fun `new secrets are 160 bits and random`() {
		Totp.newSecret().size shouldBe 20
		(Totp.newSecret().contentEquals(Totp.newSecret())) shouldBe false
	}
}
