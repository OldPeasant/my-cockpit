package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupSecrets
import ch.sonensei.cockpit.backup.domain.InvalidBackupException
import ch.sonensei.cockpit.backup.domain.SecretsEnvelope
import ch.sonensei.cockpit.backup.domain.WrongPassphraseException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * The encryption of `data/secrets.enc.json` (SPEC.md §10.2: Argon2id-derived key, AES-GCM) without Spring: a wrong
 * passphrase and any change of the file are [WrongPassphraseException]; an envelope of an unknown format or with KDF
 * parameters out of bounds is an [InvalidBackupException] that is rejected before the key derivation runs. Most tests
 * use the smallest KDF parameters, so they stay fast; one checks the default.
 */
class BackupSecretsCryptoTests {
	private val fast = BackupSecrets.Kdf(memoryKiB = 8, iterations = 1, parallelism = 1)
	private val plaintext = """{"probe":{"account-1":"fictitious-imap-password-4711"}}""".toByteArray()

	private fun encrypt(passphrase: String = PASSPHRASE) = BackupSecrets.encrypt(plaintext, passphrase, fast)

	private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

	private fun unb64(text: String) = Base64.getDecoder().decode(text)

	private fun invalid(envelope: SecretsEnvelope) =
		shouldThrowExactly<InvalidBackupException> { BackupSecrets.decrypt(envelope, PASSPHRASE) }.problems shouldContainExactly
			listOf("data/secrets.enc.json is not valid")

	@Test
	fun `the right passphrase decrypts what was encrypted`() {
		BackupSecrets.decrypt(encrypt(), PASSPHRASE) shouldBe plaintext
	}

	@Test
	fun `a non-ASCII passphrase and empty secrets round-trip`() {
		val passphrase = "Grüezi Chuchichäschtli 🔐 fictitious"
		val envelope = BackupSecrets.encrypt("{}".toByteArray(), passphrase, fast)

		String(BackupSecrets.decrypt(envelope, passphrase)) shouldBe "{}"
	}

	@Test
	fun `the envelope names Argon2id and AES-256-GCM with a 128-bit salt, a 96-bit nonce and a 128-bit tag`() {
		val envelope = encrypt()

		envelope.formatVersion shouldBe 1
		envelope.cipher shouldBe "AES-256-GCM"
		envelope.kdf.algorithm shouldBe "argon2id"
		envelope.kdf.memoryKiB shouldBe 8
		envelope.kdf.iterations shouldBe 1
		envelope.kdf.parallelism shouldBe 1
		unb64(envelope.kdf.salt).size shouldBe 16
		unb64(envelope.nonce).size shouldBe 12
		unb64(envelope.ciphertext).size shouldBe plaintext.size + 16
		String(unb64(envelope.ciphertext)) shouldNotContain "fictitious-imap-password"
	}

	@Test
	fun `the default KDF is 64 MiB, 3 passes, one lane and is what encrypt uses`() {
		BackupSecrets.DEFAULT_KDF shouldBe BackupSecrets.Kdf(memoryKiB = 65536, iterations = 3, parallelism = 1)

		val envelope = BackupSecrets.encrypt(plaintext, PASSPHRASE)

		envelope.kdf.memoryKiB shouldBe 65536
		envelope.kdf.iterations shouldBe 3
		envelope.kdf.parallelism shouldBe 1
		BackupSecrets.decrypt(envelope, PASSPHRASE) shouldBe plaintext
	}

	@Test
	fun `every encryption uses a fresh salt and nonce`() {
		val first = encrypt()
		val second = encrypt()

		second.kdf.salt shouldNotBe first.kdf.salt
		second.nonce shouldNotBe first.nonce
		second.ciphertext shouldNotBe first.ciphertext
	}

	@Test
	fun `a wrong passphrase is a WrongPassphraseException that does not quote the passphrase`() {
		val e = shouldThrow<WrongPassphraseException> { BackupSecrets.decrypt(encrypt(), "a wrong fictitious passphrase") }

		e.problems shouldContainExactly listOf("The passphrase is wrong")
		(e.message ?: "") shouldNotContain "fictitious"
	}

	@Test
	fun `passphrases differing only in case or trailing space are wrong`() {
		listOf(PASSPHRASE.uppercase(), "$PASSPHRASE ", PASSPHRASE.dropLast(1), "").forEach { other ->
			withClue("'$other'") { shouldThrow<WrongPassphraseException> { BackupSecrets.decrypt(encrypt(), other) } }
		}
	}

	@Test
	fun `a flipped bit, a truncated or an extended ciphertext is detected like a wrong passphrase`() {
		val envelope = encrypt()
		val bytes = unb64(envelope.ciphertext)
		val tampered =
			listOf(
				bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
				bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x80).toByte() },
				bytes.copyOf(bytes.size - 1),
				bytes.copyOf(10),
				ByteArray(0),
				bytes + 0,
			)
		tampered.forEachIndexed { index, changed ->
			withClue("change $index") {
				shouldThrow<WrongPassphraseException> { BackupSecrets.decrypt(envelope.copy(ciphertext = b64(changed)), PASSPHRASE) }
			}
		}
	}

	@Test
	fun `changed KDF parameters, salt or nonce within bounds are detected, they are authenticated`() {
		val envelope = encrypt()
		val otherSalt = unb64(envelope.kdf.salt).also { it[0] = (it[0].toInt() xor 1).toByte() }
		val otherNonce = unb64(envelope.nonce).also { it[0] = (it[0].toInt() xor 1).toByte() }
		listOf(
			envelope.copy(kdf = envelope.kdf.copy(iterations = 2)),
			envelope.copy(kdf = envelope.kdf.copy(memoryKiB = 16)),
			envelope.copy(kdf = envelope.kdf.copy(parallelism = 2, memoryKiB = 16)),
			envelope.copy(kdf = envelope.kdf.copy(salt = b64(otherSalt))),
			envelope.copy(nonce = b64(otherNonce)),
		).forEachIndexed { index, changed ->
			withClue("change $index") { shouldThrow<WrongPassphraseException> { BackupSecrets.decrypt(changed, PASSPHRASE) } }
		}
	}

	@Test
	fun `an unknown format version, cipher or KDF algorithm is not valid`() {
		val envelope = encrypt()
		listOf(
			envelope.copy(formatVersion = 2),
			envelope.copy(formatVersion = 0),
			envelope.copy(cipher = "AES-128-GCM"),
			envelope.copy(cipher = "aes-256-gcm"),
			envelope.copy(kdf = envelope.kdf.copy(algorithm = "argon2i")),
			envelope.copy(kdf = envelope.kdf.copy(algorithm = "pbkdf2")),
		).forEach(::invalid)
	}

	@Test
	fun `KDF parameters out of bounds are not valid and are rejected without deriving a key`() {
		val envelope = encrypt()
		val kdf = envelope.kdf
		listOf(
			kdf.copy(memoryKiB = 7),
			kdf.copy(memoryKiB = 0),
			kdf.copy(memoryKiB = -1),
			// 1 GiB is the most a backup may ask for; more would let a crafted file exhaust the server's memory.
			kdf.copy(memoryKiB = 1024 * 1024 + 1),
			kdf.copy(memoryKiB = Int.MAX_VALUE),
			kdf.copy(iterations = 0),
			kdf.copy(iterations = 21),
			kdf.copy(iterations = Int.MAX_VALUE),
			kdf.copy(parallelism = 0),
			kdf.copy(parallelism = 17),
		).forEachIndexed { index, changed ->
			withClue("kdf $index: $changed") { invalid(envelope.copy(kdf = changed)) }
		}
	}

	@Test
	fun `the highest iterations and parallelism within bounds are accepted, not mistaken for invalid ones`() {
		// The bounds check passes, so the changed (authenticated) parameters give a wrong passphrase, not "not valid".
		val envelope = encrypt()

		shouldThrow<WrongPassphraseException> {
			BackupSecrets.decrypt(envelope.copy(kdf = envelope.kdf.copy(iterations = 20, parallelism = 16, memoryKiB = 256)), PASSPHRASE)
		}
	}

	@Test
	fun `a salt, nonce or ciphertext that is not Base64, an empty salt or a nonce of another size is not valid`() {
		val envelope = encrypt()
		listOf(
			envelope.copy(kdf = envelope.kdf.copy(salt = "not base64!")),
			envelope.copy(kdf = envelope.kdf.copy(salt = "")),
			envelope.copy(nonce = "not base64!"),
			envelope.copy(nonce = ""),
			envelope.copy(nonce = b64(ByteArray(16))),
			envelope.copy(nonce = b64(ByteArray(11))),
			envelope.copy(ciphertext = "not base64!"),
		).forEachIndexed { index, changed ->
			withClue("change $index") { invalid(changed) }
		}
	}

	@Test
	fun `a wrong passphrase is an InvalidBackupException too, so a restore reports it as a validation error`() {
		val e: InvalidBackupException = shouldThrow<WrongPassphraseException> { BackupSecrets.decrypt(encrypt(), "another passphrase") }

		e.problems shouldContainExactly listOf(WrongPassphraseException.WRONG_PASSPHRASE)
	}

	companion object {
		private const val PASSPHRASE = "fictitious backup passphrase"
	}
}
