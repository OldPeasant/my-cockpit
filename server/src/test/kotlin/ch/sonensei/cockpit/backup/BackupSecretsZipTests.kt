package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupManifest
import ch.sonensei.cockpit.backup.domain.BackupSecrets
import ch.sonensei.cockpit.backup.domain.InvalidBackupException
import ch.sonensei.cockpit.backup.domain.WrongPassphraseException
import ch.sonensei.cockpit.backup.domain.secret
import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import ch.sonensei.cockpit.backup.infra.ZipBackupWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.outputStream

/**
 * `data/secrets.enc.json` in the zip (SPEC.md §10.2): [ZipBackupWriter] keeps the secrets only with a passphrase and
 * writes them encrypted, listed in the manifest; [ZipBackupReader] decrypts them up front when opened with a
 * passphrase, so a wrong one fails before anything is imported, and hands them to contributors by name.
 */
class BackupSecretsZipTests {
	@TempDir
	lateinit var dir: Path

	/** Like the application's mapper: Kotlin module, then canonical. */
	private val mapper = BackupFormat.canonical(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
	private val createdAt = Instant.parse("2026-10-20T15:30:12Z")

	data class MailPasswords(
		val accounts: Map<String, String>,
	)

	private fun write(
		passphrase: String?,
		block: (ZipBackupWriter) -> Unit = { writer ->
			writer.data.json("settings", mapOf("theme" to "dark"))
			writer.data.secret("mail_passwords", MailPasswords(mapOf("account-1" to IMAP_PASSWORD)))
			writer.data.secret("oauth_refresh_tokens", mapOf("conn-1" to REFRESH_TOKEN))
		},
	): Path {
		val path = dir.resolve("backup-${System.nanoTime()}.zip")
		path.outputStream().use { out ->
			val writer = ZipBackupWriter(out, mapper, createdAt, passphrase)
			block(writer)
			writer.finish("1.2.3") { "# Readme\n" }
		}
		return path
	}

	private fun entries(zip: Path) = Zips.read(Files.readAllBytes(zip))

	private fun manifest(zip: Path) = mapper.readValue(entries(zip).getValue(BackupFormat.MANIFEST), BackupManifest::class.java)

	private fun allText(zip: Path) = entries(zip).values.joinToString("\n") { String(it, Charsets.UTF_8) }

	@Test
	fun `without a passphrase secrets are dropped, no secrets file is written and contributors are told so`() {
		var includes: Boolean? = null
		val zip =
			write(null) { writer ->
				includes = writer.data.includesSecrets
				writer.data.secret("mail_passwords", MailPasswords(mapOf("account-1" to IMAP_PASSWORD)))
			}

		includes shouldBe false
		entries(zip) shouldNotContainKey BackupSecrets.PATH
		manifest(zip).counts shouldNotContainKey BackupSecrets.COUNT_NAME
		allText(zip) shouldNotContain IMAP_PASSWORD
		ZipBackupReader.open(zip, mapper).use { reader ->
			reader.hasSecrets shouldBe false
			reader.secretsRead shouldBe false
			reader.secret<MailPasswords>("mail_passwords") shouldBe null
		}
	}

	@Test
	fun `with a passphrase the secrets are encrypted into the secrets file, listed in the manifest and counted by name`() {
		var includes: Boolean? = null
		val zip =
			write(PASSPHRASE) { writer ->
				includes = writer.data.includesSecrets
				writer.data.secret("mail_passwords", MailPasswords(mapOf("account-1" to IMAP_PASSWORD)))
				writer.data.secret("oauth_refresh_tokens", mapOf("conn-1" to REFRESH_TOKEN))
			}

		includes shouldBe true
		entries(zip) shouldContainKey BackupSecrets.PATH
		manifest(zip).files.single { it.path == BackupSecrets.PATH }.sha256 shouldBe
			Zips.sha256(entries(zip).getValue(BackupSecrets.PATH))
		manifest(zip).counts[BackupSecrets.COUNT_NAME] shouldBe 2L
		withClue("no secret in plain text anywhere in the zip") {
			allText(zip) shouldNotContain IMAP_PASSWORD
			allText(zip) shouldNotContain REFRESH_TOKEN
		}
	}

	@Test
	fun `opened with the passphrase, the reader returns each secret by name with its type`() {
		val zip = write(PASSPHRASE)

		ZipBackupReader.open(zip, mapper, PASSPHRASE).use { reader ->
			reader.hasSecrets shouldBe true
			reader.secretsRead shouldBe true
			reader.secret<MailPasswords>("mail_passwords") shouldBe MailPasswords(mapOf("account-1" to IMAP_PASSWORD))
			reader.secret<Map<String, String>>("oauth_refresh_tokens") shouldBe mapOf("conn-1" to REFRESH_TOKEN)
			reader.secret<Map<String, String>>("absent") shouldBe null
		}
	}

	@Test
	fun `opened without the passphrase, the reader knows there are secrets but returns none`() {
		val zip = write(PASSPHRASE)

		ZipBackupReader.open(zip, mapper).use { reader ->
			reader.hasSecrets shouldBe true
			reader.secretsRead shouldBe false
			reader.secret<MailPasswords>("mail_passwords") shouldBe null
		}
	}

	@Test
	fun `a wrong passphrase fails open, before anything can be imported`() {
		val zip = write(PASSPHRASE)

		val e = shouldThrow<WrongPassphraseException> { ZipBackupReader.open(zip, mapper, "a wrong fictitious passphrase") }

		e.problems shouldContainExactly listOf("The passphrase is wrong")
		withClue("open closes the zip on failure, so the upload can be deleted") {
			Files.delete(zip)
		}
	}

	@Test
	fun `a backup made with a passphrase but no secrets still has a secrets file, so a wrong passphrase is detected`() {
		val zip = write(PASSPHRASE) { writer -> writer.data.json("settings", mapOf("theme" to "dark")) }

		entries(zip) shouldContainKey BackupSecrets.PATH
		manifest(zip).counts[BackupSecrets.COUNT_NAME] shouldBe 0L
		ZipBackupReader.open(zip, mapper, PASSPHRASE).use { it.secretsRead shouldBe true }
		shouldThrow<WrongPassphraseException> { ZipBackupReader.open(zip, mapper, "a wrong fictitious passphrase") }
	}

	@Test
	fun `a passphrase for a backup without secrets is ignored`() {
		val zip = write(null)

		ZipBackupReader.open(zip, mapper, PASSPHRASE).use { reader ->
			reader.hasSecrets shouldBe false
			reader.secretsRead shouldBe false
			reader.secret<MailPasswords>("mail_passwords") shouldBe null
		}
	}

	@Test
	fun `a secret of another type than expected fails with its name only, not its value`() {
		val zip = write(PASSPHRASE)

		ZipBackupReader.open(zip, mapper, PASSPHRASE).use { reader ->
			val e = shouldThrowExactly<InvalidBackupException> { reader.secret<List<Int>>("mail_passwords") }
			e.problems shouldContainExactly listOf("Secret 'mail_passwords' in data/secrets.enc.json is not valid")
			(e.message ?: "") shouldNotContain IMAP_PASSWORD
		}
	}

	@Test
	fun `invalid secret names and a secret written twice are programming errors`() {
		listOf("Mail", "mail-passwords", "", "_x", "a__b").forEach { name ->
			withClue("'$name'") {
				shouldThrow<IllegalArgumentException> { write(PASSPHRASE) { it.data.secret(name, "x") } }.message shouldContain
					"Invalid backup secret name"
			}
		}
		shouldThrow<IllegalArgumentException> {
			write(PASSPHRASE) { writer ->
				writer.data.secret("mail_passwords", "x")
				writer.data.secret("mail_passwords", "y")
			}
		}.message shouldContain "written twice"
	}

	@Test
	fun `data named like the secrets' count cannot be written together with secrets`() {
		shouldThrow<IllegalArgumentException> {
			write(PASSPHRASE) { writer -> writer.data.json(BackupSecrets.COUNT_NAME, mapOf("a" to 1)) }
		}.message shouldContain "clashes"
	}

	/** [zip] with its secrets file replaced by [content] and the manifest updated, like a careful hand-edit. */
	private fun withSecretsFile(
		zip: Path,
		content: ByteArray,
	): Path {
		val edited = Zips.edit(Files.readAllBytes(zip), mapper, updateManifest = true) { it[BackupSecrets.PATH] = content }
		return dir.resolve("edited-${System.nanoTime()}.zip").also { Files.write(it, edited) }
	}

	private fun encrypted(plaintext: String) =
		mapper.writeValueAsBytes(
			BackupSecrets.encrypt(plaintext.toByteArray(), PASSPHRASE, BackupSecrets.Kdf(memoryKiB = 8, iterations = 1, parallelism = 1)),
		)

	@Test
	fun `a secrets file that is not an envelope is not valid when opened with a passphrase`() {
		val zip = write(PASSPHRASE)
		listOf("not json", "null", "[]", "{}", """{"formatVersion":1}""").forEach { content ->
			withClue(content) {
				val e =
					shouldThrowExactly<InvalidBackupException> { ZipBackupReader.open(withSecretsFile(zip, content.toByteArray()), mapper, PASSPHRASE) }
				e.problems shouldContainExactly listOf("data/secrets.enc.json is not valid")
			}
		}
	}

	@Test
	fun `encrypted secrets that are not a JSON object are not valid`() {
		val zip = write(PASSPHRASE)
		listOf("[\"$IMAP_PASSWORD\"]", "\"$IMAP_PASSWORD\"", "not json $IMAP_PASSWORD", "null").forEach { plaintext ->
			withClue(plaintext) {
				val e =
					shouldThrowExactly<InvalidBackupException> { ZipBackupReader.open(withSecretsFile(zip, encrypted(plaintext)), mapper, PASSPHRASE) }
				e.problems shouldContainExactly listOf("data/secrets.enc.json is not valid")
				(e.message ?: "") shouldNotContain IMAP_PASSWORD
			}
		}
	}

	@Test
	fun `a secrets file changed without updating the manifest fails the sha256 check, with or without passphrase`() {
		val zip = write(PASSPHRASE)
		val edited =
			dir.resolve("tampered.zip").also {
				Files.write(it, Zips.edit(Files.readAllBytes(zip), mapper, updateManifest = false) { e -> e[BackupSecrets.PATH] = encrypted("{}") })
			}

		shouldThrow<InvalidBackupException> { ZipBackupReader.open(edited, mapper) }.problems shouldContainExactly
			listOf("data/secrets.enc.json does not match its sha256 in the manifest")
		shouldThrow<InvalidBackupException> { ZipBackupReader.open(edited, mapper, PASSPHRASE) }.problems shouldContainExactly
			listOf("data/secrets.enc.json does not match its sha256 in the manifest")
	}

	companion object {
		private const val PASSPHRASE = "fictitious backup passphrase"
		private const val IMAP_PASSWORD = "fictitious-imap-password-4711"
		private const val REFRESH_TOKEN = "1//fictitious-refresh-token-0815"
	}
}
