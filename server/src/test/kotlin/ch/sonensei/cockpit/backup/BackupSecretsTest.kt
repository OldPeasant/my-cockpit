package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupSecrets
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.domain.SecretsEnvelope
import ch.sonensei.cockpit.backup.domain.secret
import ch.sonensei.cockpit.core.auth.cockpitUser
import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupEnrollment
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnection
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionStatus
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.infra.OAuthConnectionRepository
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * A module with a mail password (stands in for the mail module's contributor, which uses the same mechanism): exports
 * it as a secret and records what it got back on import.
 */
class MailPasswordProbeContributor : BackupContributor {
	override val id = "mail-password-probe"

	@Volatile var imported: Map<String, String>? = null

	override fun export(writer: BackupDataWriter) = writer.secret(SECRETS, mapOf("account-1" to IMAP_PASSWORD))

	override fun import(reader: BackupDataReader) {
		imported = reader.secret<Map<String, String>>(SECRETS)
	}

	companion object {
		const val SECRETS = "probe_imap_passwords"
		const val IMAP_PASSWORD = "fictitious-imap-password-4711"
	}
}

@TestConfiguration(proxyBeanMethods = false)
class MailPasswordProbeConfiguration {
	@Bean
	fun mailPasswordProbeContributor() = MailPasswordProbeContributor()
}

/**
 * Secrets in backups (SPEC.md §10.2): with a passphrase they are in `data/secrets.enc.json` (Argon2id, AES-GCM) and
 * a restore with the passphrase brings them back; without, they are omitted, OAuth connections are "reconnect
 * required" and the next login must set up TOTP again before anything else; a wrong passphrase restores nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class, MailPasswordProbeConfiguration::class)
class BackupSecretsTest(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val backups: BackupService,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val setup: SetupService,
	@Autowired private val users: UserAccountRepository,
	@Autowired private val connections: OAuthConnectionRepository,
	@Autowired private val throttle: LoginThrottle,
	@Autowired private val clock: MutableClock,
	@Autowired private val probe: MailPasswordProbeContributor,
) {
	private val totp = Totp()
	private val mapper get() = backups.canonicalJson
	private lateinit var enrollment: SetupEnrollment

	@BeforeEach
	fun setUp() {
		RestoreTestDatabases.wipe(jdbc, dataDir)
		throttle.recordSuccess()
		probe.imported = null
		clock.set(MutableClockTestConfiguration.START)
		enrollment = setup.start(USERNAME, PASSWORD)
		setup.confirmTotp(code(enrollment.totpSecret))
		transactions.executeWithoutResult {
			connections.saveAndFlush(
				OAuthConnection(OAuthProvider.GOOGLE, "100000000000000000001", "jane.doe@example.com", "Jane Doe", "openid email", null)
					.apply { markConnected(EncryptedSecret(REFRESH_TOKEN), clock.instant()) },
			)
		}
	}

	private fun code(secret: String) = totp.generate(Base32.decode(secret), clock.instant())

	private fun export(passphrase: String?): ByteArray = ByteArrayOutputStream().also { backups.export(it, passphrase) }.toByteArray()

	@Test
	fun `with a passphrase the TOTP secret, OAuth tokens and mail passwords are only in secrets enc json, encrypted`() {
		val entries = Zips.read(export(PASSPHRASE))

		entries shouldContainKey BackupSecrets.PATH
		withClue("no file of the zip has a secret in plain text") {
			entries.forEach { (name, bytes) ->
				val text = String(bytes, Charsets.UTF_8)
				listOf(enrollment.totpSecret, REFRESH_TOKEN, MailPasswordProbeContributor.IMAP_PASSWORD).forEach { secret ->
					withClue(name) { text.contains(secret) shouldBe false }
				}
			}
		}
		val envelope = mapper.readValue(entries.getValue(BackupSecrets.PATH), SecretsEnvelope::class.java)
		envelope.kdf.algorithm shouldBe "argon2id"
		envelope.cipher shouldBe "AES-256-GCM"
		val secrets = mapper.readTree(BackupSecrets.decrypt(envelope, PASSPHRASE))
		secrets["user"]["totpSecret"].asString() shouldBe enrollment.totpSecret
		secrets["oauth_refresh_tokens"].properties().single().value.asString() shouldBe REFRESH_TOKEN
		secrets[MailPasswordProbeContributor.SECRETS]["account-1"].asString() shouldBe MailPasswordProbeContributor.IMAP_PASSWORD
		mapper.readTree(entries.getValue("manifest.json"))["counts"]["secrets"].asLong() shouldBe 3
	}

	@Test
	fun `without a passphrase the secrets are omitted`() {
		val entries = Zips.read(export(null))

		entries shouldNotContainKey BackupSecrets.PATH
		entries.forEach { (name, bytes) ->
			val text = String(bytes, Charsets.UTF_8)
			listOf(enrollment.totpSecret, REFRESH_TOKEN, MailPasswordProbeContributor.IMAP_PASSWORD).forEach { secret ->
				withClue(name) { text.contains(secret) shouldBe false }
			}
		}
	}

	@Test
	fun `a restore with the passphrase brings the secrets back`() {
		val zip = export(PASSPHRASE)
		val report = report(zip)
		report["hasSecrets"].asBoolean() shouldBe true

		confirm(report["id"].asString(), PASSPHRASE).andExpect { status { isOk() } }

		val user = users.findFirstBy().shouldNotBeNull()
		user.totpReenrollRequired shouldBe false
		user.totpSecret.reveal() shouldBe enrollment.totpSecret
		val connection = connections.findAll().single()
		connection.status shouldBe OAuthConnectionStatus.CONNECTED
		connection.refreshToken?.reveal() shouldBe REFRESH_TOKEN
		probe.imported shouldBe mapOf("account-1" to MailPasswordProbeContributor.IMAP_PASSWORD)
		withClue("the old TOTP code logs in with full access") {
			val session = login(totp = code(enrollment.totpSecret)).andExpect { jsonPath("$.totpEnrollmentRequired") { value(false) } }
			mockMvc.get(DEVICES) { cookie(sessionCookie(session)) }.andExpect { status { isOk() } }
		}
	}

	@Test
	fun `after a restore without secrets accounts must reconnect and the next login forces TOTP re-enrollment`() {
		// Made with a passphrase but restored without it: the same as a backup without secrets.
		val zips = listOf(export(null), export(PASSPHRASE))
		zips.forEach { zip ->
			throttle.recordSuccess()
			confirm(report(zip)["id"].asString(), passphrase = null).andExpect { status { isOk() } }

			val connection = connections.findAll().single()
			connection.status shouldBe OAuthConnectionStatus.RECONNECT_REQUIRED
			connection.refreshToken shouldBe null
			probe.imported shouldBe null
			users.findFirstBy().shouldNotBeNull().totpReenrollRequired shouldBe true
		}

		withClue("TOTP does not log in, neither with the old secret nor at all") {
			login(totp = code(enrollment.totpSecret)).andExpect { status { isUnauthorized() } }
		}
		throttle.recordSuccess()
		withClue("the Android app cannot log in before TOTP is set up again") {
			mockMvc
				.post("/api/v1/auth/token") {
					contentType = MediaType.APPLICATION_JSON
					content =
						"""{"username":"$USERNAME","password":"$PASSWORD","recoveryCode":"${enrollment.recoveryCodes[1]}","deviceName":"Pixel"}"""
				}.andExpect { status { isForbidden() } }
		}
		throttle.recordSuccess()

		val session =
			sessionCookie(
				login(recoveryCode = enrollment.recoveryCodes[0]).andExpect {
					status { isOk() }
					jsonPath("$.totpEnrollmentRequired") { value(true) }
				},
			)
		withClue("the session may only set up TOTP again") {
			mockMvc.get(DEVICES) { cookie(session) }.andExpect { status { isForbidden() } }
			mockMvc.get("/api/v1/settings") { cookie(session) }.andExpect { status { isForbidden() } }
			mockMvc.get("/api/v1/auth/me") { cookie(session) }.andExpect {
				status { isOk() }
				jsonPath("$.totpEnrollmentRequired") { value(true) }
			}
		}

		val started =
			mapper.readTree(
				mockMvc
					.post(ENROLLMENT) {
						cookie(session)
						with(csrf())
					}.andExpect { status { isOk() } }
					.andReturn()
					.response.contentAsString,
			)
		val newSecret = started["totpSecret"].asString()
		mockMvc
			.post("$ENROLLMENT/confirm") {
				cookie(session)
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"code":"${code(newSecret)}"}"""
			}.andExpect { status { isNoContent() } }

		withClue("afterwards the same session has full access and the new TOTP secret logs in") {
			mockMvc.get(DEVICES) { cookie(session) }.andExpect { status { isOk() } }
			users.findFirstBy().shouldNotBeNull().totpReenrollRequired shouldBe false
			login(totp = code(newSecret)).andExpect {
				status { isOk() }
				jsonPath("$.totpEnrollmentRequired") { value(false) }
			}
		}
	}

	@Test
	fun `a wrong passphrase is a validation error, restores nothing and keeps the upload`() {
		val zip = export(PASSPHRASE)
		jdbc.update("update oauth_connection set email = 'changed.later@example.com'")
		val id = report(zip)["id"].asString()

		confirm(id, "a wrong fictitious passphrase").andExpect {
			status { isUnprocessableContent() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.problems[0]") { value("The passphrase is wrong") }
		}

		jdbc.queryForList("select email from oauth_connection", String::class.java) shouldContainExactly listOf("changed.later@example.com")
		Files.exists(dataDir.resolve("pre-restore")) shouldBe false
		withClue("the upload is kept for another try with the right passphrase") {
			confirm(id, PASSPHRASE).andExpect { status { isOk() } }
			jdbc.queryForList("select email from oauth_connection", String::class.java) shouldContainExactly listOf("jane.doe@example.com")
		}
	}

	private fun report(zip: ByteArray): JsonNode =
		mapper.readTree(
			mockMvc
				.post(RESTORE) {
					with(cockpitUser())
					with(csrf())
					contentType = MediaType("application", "zip")
					content = zip
				}.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString,
		)

	private fun confirm(
		id: String,
		passphrase: String?,
	): ResultActionsDsl =
		mockMvc.post("$RESTORE/$id/confirm") {
			with(cockpitUser())
			with(csrf())
			if (passphrase != null) {
				contentType = MediaType.APPLICATION_JSON
				content = mapper.writeValueAsString(mapOf("passphrase" to passphrase))
			}
		}

	private fun login(
		totp: String? = null,
		recoveryCode: String? = null,
	): ResultActionsDsl =
		mockMvc.post("/api/v1/auth/login") {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content =
				mapper.writeValueAsString(
					mapOf("username" to USERNAME, "password" to PASSWORD, "totp" to totp, "recoveryCode" to recoveryCode),
				)
		}

	private fun sessionCookie(login: ResultActionsDsl): Cookie {
		val token =
			login
				.andReturn()
				.response
				.getCookie(SessionCookies.NAME)
				.shouldNotBeNull()
				.value
		return Cookie(SessionCookies.NAME, token)
	}

	companion object {
		private const val USERNAME = "jane.doe"
		private const val PASSWORD = "fictitious-password-1234"
		private const val PASSPHRASE = "fictitious backup passphrase"
		private const val REFRESH_TOKEN = "1//fictitious-refresh-token-0815"
		private const val RESTORE = "/api/v1/restore"
		private const val ENROLLMENT = "/api/v1/auth/totp-enrollment"
		private const val DEVICES = "/api/v1/auth/devices"
		private val dataDir: Path = Files.createTempDirectory("cockpit-secrets-data")

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, "backup_secrets", dataDir)
	}
}
