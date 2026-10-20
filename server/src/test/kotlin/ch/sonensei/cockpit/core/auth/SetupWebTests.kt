package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.RecoveryCodes
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.crypto.SecretCipher
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

@SpringBootTest
@AutoConfigureMockMvc
class SetupWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val cipher: SecretCipher,
	@Autowired private val passwordEncoder: PasswordEncoder,
	@Autowired private val clock: Clock,
) : PostgresTestBase() {
	private val totp = Totp()

	@BeforeEach
	fun noAccount() {
		jdbc.update("delete from app_user")
	}

	@Test
	fun `setup is required while no account exists`() {
		status() shouldBe true
	}

	@Test
	fun `setup creates the account and confirming the TOTP code activates it`() {
		val enrollment = startSetup()

		val secret = enrollment["totpSecret"] as String
		secret shouldMatch Regex("[A-Z2-7]{32}")
		enrollment["otpauthUri"] as String shouldBe
			"otpauth://totp/My%20Cockpit:jane.doe?secret=$secret&issuer=My%20Cockpit&algorithm=SHA1&digits=6&period=30"
		@Suppress("UNCHECKED_CAST")
		val codes = enrollment["recoveryCodes"] as List<String>
		codes shouldHaveSize 10
		codes.toSet() shouldHaveSize 10
		codes.forEach { it shouldMatch Regex("[a-z2-9]{5}-[a-z2-9]{5}") }
		status() shouldBe true

		confirm(totp.generate(Base32.decode(secret), clock.instant())).andExpect { status { isNoContent() } }

		status() shouldBe false
		jdbc.queryForObject("select count(*) from app_user where totp_confirmed_at is not null", Int::class.java) shouldBe 1
	}

	@Test
	fun `password is stored as Argon2id hash and recovery codes only hashed`() {
		val enrollment = startSetup()

		val hash = jdbc.queryForObject("select password_hash from app_user", String::class.java)!!
		hash shouldStartWith "\$argon2id\$"
		passwordEncoder.matches(PASSWORD, hash) shouldBe true

		@Suppress("UNCHECKED_CAST")
		val codes = enrollment["recoveryCodes"] as List<String>
		val hashes = jdbc.queryForList("select code_hash from recovery_code where used_at is null", String::class.java)
		hashes shouldHaveSize 10
		codes.forEach { code ->
			hashes.forEach { it shouldNotContain RecoveryCodes.normalize(code) }
			hashes.count { passwordEncoder.matches(RecoveryCodes.normalize(code), it) } shouldBe 1
		}
	}

	@Test
	fun `TOTP secret is stored encrypted`() {
		val secret = startSetup()["totpSecret"] as String

		val stored = jdbc.queryForObject("select totp_secret from app_user", String::class.java)!!
		stored shouldNotContain secret
		cipher.decrypt(stored) shouldBe secret
	}

	@Test
	fun `every setup endpoint answers 409 once the account is active`() {
		val secret = startSetup()["totpSecret"] as String
		confirm(totp.generate(Base32.decode(secret), clock.instant())).andExpect { status { isNoContent() } }

		post(SETUP, setupBody()).isProblem(409)
		confirm(totp.generate(Base32.decode(secret), clock.instant())).isProblem(409)
		status() shouldBe false
		jdbc.queryForObject("select count(*) from app_user", Int::class.java) shouldBe 1
		jdbc.queryForObject("select username from app_user", String::class.java) shouldBe USERNAME
	}

	@Test
	fun `a wrong or expired code is rejected and leaves setup pending`() {
		val secret = Base32.decode(startSetup()["totpSecret"] as String)
		val wrong = ((totp.generate(secret, clock.instant()).toInt() + 1) % 1_000_000).toString().padStart(6, '0')

		confirm(wrong).isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("code") } }
		confirm(totp.generate(secret, clock.instant().minus(Duration.ofMinutes(5)))).isProblem(400)
		confirm("").isProblem(400)

		status() shouldBe true
	}

	@Test
	fun `confirming before setup was started is a conflict`() {
		confirm("123456").isProblem(409)
	}

	@Test
	fun `starting again before confirmation replaces the pending account`() {
		val first = startSetup()["totpSecret"] as String
		val second = startSetup(username = "john.doe")["totpSecret"] as String

		jdbc.queryForObject("select username from app_user", String::class.java) shouldBe "john.doe"
		jdbc.queryForObject("select count(*) from recovery_code", Int::class.java) shouldBe 10
		confirm(totp.generate(Base32.decode(first), clock.instant())).isProblem(400)
		confirm(totp.generate(Base32.decode(second), clock.instant())).andExpect { status { isNoContent() } }
	}

	@Test
	fun `password shorter than 12 characters and invalid usernames are rejected`() {
		post(SETUP, setupBody(password = "short-pass1")).isProblem(400).andExpect {
			jsonPath("$.errors[0].field") { value("password") }
		}
		post(SETUP, setupBody(username = "")).isProblem(400)
		post(SETUP, setupBody(username = "jane doe")).isProblem(400)
		post(SETUP, setupBody(username = "j".repeat(65))).isProblem(400)

		jdbc.queryForObject("select count(*) from app_user", Int::class.java) shouldBe 0
	}

	@Test
	fun `responses never echo the password`() {
		val body =
			post(SETUP, setupBody())
				.andExpect { status { isCreated() } }
				.andReturn()
				.response.contentAsString
		body shouldNotContain PASSWORD
	}

	private fun status(): Boolean =
		mockMvc
			.get("$SETUP/status")
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsString
			.let { jsonMapper.readValue(it, Map::class.java)["setupRequired"] as Boolean }

	private fun startSetup(username: String = USERNAME): Map<*, *> =
		post(SETUP, setupBody(username = username))
			.andExpect { status { isCreated() } }
			.andReturn()
			.response.contentAsString
			.let { jsonMapper.readValue(it, Map::class.java) }

	private fun confirm(code: String) = post("$SETUP/confirm-totp", jsonMapper.writeValueAsString(mapOf("code" to code)))

	private fun setupBody(
		username: String = USERNAME,
		password: String = PASSWORD,
	) = jsonMapper.writeValueAsString(mapOf("username" to username, "password" to password))

	// No CSRF token and no authentication: the setup flow runs before either exists.
	private fun post(
		path: String,
		body: String,
	) = mockMvc.post(path) {
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	private companion object {
		const val SETUP = "/api/v1/setup"
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-fictitious"
	}
}
