package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.RecoveryCodes
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class RecoveryCodesTests {
	@Test
	fun `ten codes in the xxxxx-xxxxx format are generated`() {
		val codes = RecoveryCodes.generate()

		codes shouldHaveSize RecoveryCodes.COUNT
		RecoveryCodes.COUNT shouldBe 10
		codes.forEach { it shouldMatch Regex("[a-z2-9]{5}-[a-z2-9]{5}") }
	}

	@Test
	fun `codes avoid look-alike characters`() {
		val used = List(50) { RecoveryCodes.generate() }.flatten().joinToString("").toSet()

		used.intersect("01ilo".toSet()) shouldBe emptySet()
	}

	@Test
	fun `codes are random and do not repeat`() {
		val codes = List(100) { RecoveryCodes.generate() }.flatten()

		codes.toSet() shouldHaveSize codes.size
	}

	@Test
	fun `normalize ignores case, dashes and spaces`() {
		RecoveryCodes.normalize("abcde-fghjk") shouldBe "abcdefghjk"
		RecoveryCodes.normalize("ABCDE-FGHJK") shouldBe "abcdefghjk"
		RecoveryCodes.normalize(" abcde fghjk ") shouldBe "abcdefghjk"
		RecoveryCodes.normalize("abcdefghjk") shouldBe "abcdefghjk"
		RecoveryCodes.normalize("") shouldBe ""
	}

	@Test
	fun `a generated code and its normalized form normalize to the same value`() {
		RecoveryCodes.generate().forEach { code ->
			RecoveryCodes.normalize(code) shouldMatch Regex("[a-z2-9]{10}")
			RecoveryCodes.normalize(code.uppercase().replace("-", " ")) shouldBe RecoveryCodes.normalize(code)
		}
	}
}
