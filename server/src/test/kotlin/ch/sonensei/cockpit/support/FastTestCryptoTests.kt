package ch.sonensei.cockpit.support

import ch.sonensei.cockpit.backup.domain.BackupSecrets
import ch.sonensei.cockpit.core.auth.infra.SecurityConfiguration
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * Spring tests hash passwords with [FastTestCrypto.FAST_ENCODER] and encrypt backup secrets with
 * [FastTestCrypto.FAST_KDF]; this keeps the production encoder's parameters (SecurityConfiguration.passwordEncoder)
 * covered, and both fast settings compatible with production (the backup KDF defaults: BackupSecretsCryptoTests).
 */
class FastTestCryptoTests {
	private val production = SecurityConfiguration().passwordEncoder()

	@Test
	fun `production passwords are Argon2id hashes with Spring Security's recommended parameters`() {
		val hash = production.encode("correct horse battery staple")!!

		hash shouldStartWith "\$argon2id\$v=19\$m=16384,t=2,p=1\$"
		production.matches("correct horse battery staple", hash) shouldBe true
		production.matches("wrong horse", hash) shouldBe false
	}

	@Test
	fun `the fast test encoder writes Argon2id hashes that the production encoder verifies`() {
		val hash = FastTestCrypto.FAST_ENCODER.encode("correct horse battery staple")!!

		hash shouldStartWith "\$argon2id\$v=19\$m=8,t=1,p=1\$"
		production.matches("correct horse battery staple", hash) shouldBe true
	}

	@Test
	fun `backup secrets encrypted with the fast KDF are restored by the production decryption`() {
		val envelope = BackupSecrets.encrypt("secret".toByteArray(), "fictitious passphrase", FastTestCrypto.FAST_KDF)

		envelope.kdf.memoryKiB shouldBe 8
		envelope.kdf.iterations shouldBe 1
		String(BackupSecrets.decrypt(envelope, "fictitious passphrase")) shouldBe "secret"
	}
}
