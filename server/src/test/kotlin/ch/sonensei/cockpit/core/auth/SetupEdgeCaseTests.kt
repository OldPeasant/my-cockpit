package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.RecoveryCodes
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Boundaries, malformed input, time drift, concurrency and secret hygiene of the setup flow, with a fixed clock. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class SetupEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val passwordEncoder: PasswordEncoder,
	@Autowired private val clock: MutableClock,
	@Autowired private val setup: SetupService,
) : PostgresTestBase() {
	private val totp = Totp()

	@BeforeEach
	fun reset() {
		jdbc.update("delete from app_user")
		clock.set(NOW)
	}

	@Test
	fun `password of exactly 12 and 256 characters is accepted, 257 is rejected`() {
		post(SETUP, setupBody(password = "p".repeat(12))).andExpect { status { isCreated() } }
		jdbc.update("delete from app_user")
		post(SETUP, setupBody(password = "p".repeat(256))).andExpect { status { isCreated() } }
		post(SETUP, setupBody(password = "p".repeat(257))).isProblem(400).andExpect {
			jsonPath("$.errors[0].field") { value("password") }
		}

		passwordEncoder.matches("p".repeat(256), jdbc.queryForObject("select password_hash from app_user", String::class.java)) shouldBe
			true
	}

	@Test
	fun `username of 64 characters with letters, digits and dot, underscore, at and dash is accepted`() {
		post(SETUP, setupBody(username = "j".repeat(64))).andExpect { status { isCreated() } }
		jdbc.update("delete from app_user")
		post(SETUP, setupBody(username = "Zoë_O.Example-1@example.test")).andExpect { status { isCreated() } }

		jdbc.queryForObject("select username from app_user", String::class.java) shouldBe "Zoë_O.Example-1@example.test"
	}

	@Test
	fun `whitespace-only, slash and colon usernames are rejected`() {
		listOf("   ", "jane/doe", "jane:doe", "jane\tdoe").forEach { username ->
			withClue(username) { post(SETUP, setupBody(username = username)).isProblem(400) }
		}
		jdbc.queryForObject("select count(*) from app_user", Int::class.java) shouldBe 0
	}

	@Test
	fun `missing, null and malformed request bodies are 400 problems, not server errors`() {
		post(SETUP, """{"username":"jane.doe"}""").isProblem(400)
		post(SETUP, """{"password":"$PASSWORD"}""").isProblem(400)
		post(SETUP, """{"username":"jane.doe","password":null}""").isProblem(400)
		post(SETUP, """{"username":"jane.doe",""").isProblem(400)
		post(SETUP, "").isProblem(400)
		post(CONFIRM, "{}").isProblem(400)
		post(CONFIRM, """{"code":null}""").isProblem(400)
		post(CONFIRM, """{"code":"   "}""").isProblem(400)

		jdbc.queryForObject("select count(*) from app_user", Int::class.java) shouldBe 0
	}

	@Test
	fun `otpauth URI percent-encodes the username in the label`() {
		val uri = startSetup(username = "zoë@example.test")["otpauthUri"] as String

		uri shouldStartWith "otpauth://totp/My%20Cockpit:zo%C3%AB%40example.test?secret="
	}

	@Test
	fun `a code from the previous or next step and a code with spaces confirm the account`() {
		val secret = Base32.decode(startSetup()["totpSecret"] as String)
		val previous = totp.generate(secret, NOW.minus(Totp.STEP))

		confirm(previous).andExpect { status { isNoContent() } }

		jdbc.update("delete from app_user")
		val next = Base32.decode(startSetup()["totpSecret"] as String)
		confirm(totp.generate(next, NOW.plus(Totp.STEP))).andExpect { status { isNoContent() } }

		jdbc.update("delete from app_user")
		val spaced = Base32.decode(startSetup()["totpSecret"] as String)
		val code = totp.generate(spaced, NOW)
		confirm("${code.take(3)} ${code.drop(3)}").andExpect { status { isNoContent() } }
	}

	@Test
	fun `a code two steps away is rejected`() {
		val secret = Base32.decode(startSetup()["totpSecret"] as String)

		confirm(totp.generate(secret, NOW.minus(Totp.STEP.multipliedBy(2)))).isProblem(400)
		confirm(totp.generate(secret, NOW.plus(Totp.STEP.multipliedBy(2)))).isProblem(400)
		jdbc.queryForObject("select count(*) from app_user where totp_confirmed_at is null", Int::class.java) shouldBe 1
	}

	@Test
	fun `the confirmation time comes from the application clock`() {
		val secret = Base32.decode(startSetup()["totpSecret"] as String)
		clock.advance(Duration.ofMinutes(3))

		confirm(totp.generate(secret, clock.instant())).andExpect { status { isNoContent() } }

		jdbc.queryForObject("select totp_confirmed_at from app_user", Timestamp::class.java)!!.toInstant() shouldBe
			NOW.plus(Duration.ofMinutes(3))
	}

	@Test
	fun `stored recovery code hashes accept the code in any case and without dash`() {
		@Suppress("UNCHECKED_CAST")
		val code = (startSetup()["recoveryCodes"] as List<String>).first()
		val hashes = jdbc.queryForList("select code_hash from recovery_code", String::class.java)

		listOf(code, code.uppercase(), code.replace("-", ""), code.replace("-", " ")).forEach { input ->
			withClue(input) { hashes.count { passwordEncoder.matches(RecoveryCodes.normalize(input), it) } shouldBe 1 }
		}
		hashes.forEach { it shouldStartWith "\$argon2id\$" }
	}

	@Test
	fun `setup never logs or echoes the password, TOTP secret or recovery codes`(output: CapturedOutput) {
		post(SETUP, setupBody(password = "short-pw-x1")).isProblem(400).andExpect {
			content { string(not(containsString("short-pw-x1"))) }
		}
		val enrollment = startSetup()
		val secret = enrollment["totpSecret"] as String

		@Suppress("UNCHECKED_CAST")
		val codes = enrollment["recoveryCodes"] as List<String>
		val valid = totp.generate(Base32.decode(secret), NOW)
		confirm(((valid.toInt() + 500_000) % 1_000_000).toString().padStart(6, '0')).isProblem(400)
		confirm(valid).andExpect { status { isNoContent() } }

		val logs = output.all
		logs shouldContain "Setup started"
		logs shouldContain "Setup complete"
		logs shouldNotContain PASSWORD
		logs shouldNotContain "short-pw-x1"
		logs shouldNotContain secret
		codes.forEach { logs shouldNotContain it }
	}

	@Test
	fun `concurrent setup requests leave exactly one account and fail only with a conflict`() {
		val outcomes = concurrently(THREADS) { setup.start("jane.doe-$it", PASSWORD) }

		outcomes.forEach { outcome ->
			outcome.exceptionOrNull()?.let { error ->
				withClue(error.toString()) {
					(error is ConflictException || error is OptimisticLockingFailureException) shouldBe true
				}
			}
		}
		val enrollments = outcomes.mapNotNull { it.getOrNull() }
		(enrollments.isNotEmpty()) shouldBe true
		jdbc.queryForObject("select count(*) from app_user", Int::class.java) shouldBe 1
		jdbc.queryForObject("select count(*) from recovery_code", Int::class.java) shouldBe 10
		// The surviving account belongs to one of the successful requests and its secret works.
		val username = jdbc.queryForObject("select username from app_user", String::class.java)!!
		val winner = enrollments.single { it.otpauthUri.contains(":$username?") }
		setup.confirmTotp(totp.generate(Base32.decode(winner.totpSecret), NOW))
		setup.isSetupRequired() shouldBe false
	}

	@Test
	fun `concurrent confirmations activate the account once and fail only with a conflict`() {
		val enrollment = setup.start("jane.doe", PASSWORD)
		val code = totp.generate(Base32.decode(enrollment.totpSecret), NOW)

		val outcomes = concurrently(THREADS) { setup.confirmTotp(code) }

		outcomes.count { it.isSuccess } shouldBe 1
		outcomes.mapNotNull { it.exceptionOrNull() }.forEach { error ->
			withClue(error.toString()) {
				(error is ConflictException || error is OptimisticLockingFailureException) shouldBe true
			}
		}
		jdbc.queryForObject("select count(*) from app_user where totp_confirmed_at is not null", Int::class.java) shouldBe 1
	}

	@Test
	fun `setup endpoints stay closed after activation even with a fresh valid body`() {
		val secret = Base32.decode(startSetup()["totpSecret"] as String)
		confirm(totp.generate(secret, NOW)).andExpect { status { isNoContent() } }

		post(SETUP, setupBody(username = "mallory.example", password = "another-fictitious-pass")).isProblem(409)
		jdbc.queryForObject("select username from app_user", String::class.java) shouldBe USERNAME
		jdbc.queryForObject("select count(*) from recovery_code", Int::class.java) shouldBe 10
	}

	@Test
	fun `a pending account older than one hour can be replaced by anyone, a younger one cannot`() {
		startSetup()
		clock.advance(SetupService.ABANDONED_AFTER)

		post(SETUP, setupBody(username = "john.doe", password = "another-fictitious-pass")).isProblem(409)
		jdbc.queryForObject("select username from app_user", String::class.java) shouldBe USERNAME

		clock.advance(Duration.ofSeconds(1))
		val secret = Base32.decode(startSetup(username = "john.doe", password = "another-fictitious-pass")["totpSecret"] as String)

		jdbc.queryForObject("select count(*) from app_user", Int::class.java) shouldBe 1
		jdbc.queryForObject("select username from app_user", String::class.java) shouldBe "john.doe"
		passwordEncoder.matches("another-fictitious-pass", jdbc.queryForObject("select password_hash from app_user", String::class.java)) shouldBe
			true
		jdbc.queryForObject("select count(*) from recovery_code", Int::class.java) shouldBe 10
		confirm(totp.generate(secret, clock.instant())).andExpect { status { isNoContent() } }
	}

	@Test
	fun `the one-hour abandonment rule never reopens setup once the account is active`() {
		val secret = Base32.decode(startSetup()["totpSecret"] as String)
		confirm(totp.generate(secret, NOW)).andExpect { status { isNoContent() } }
		val before = jdbc.queryForMap("select id, username, password_hash, totp_secret, totp_confirmed_at from app_user")
		clock.advance(Duration.ofDays(2))

		post(SETUP, setupBody(username = "mallory.example", password = "another-fictitious-pass")).isProblem(409).andExpect {
			jsonPath("$.detail") { value("Setup is already complete") }
		}
		post(SETUP, setupBody()).isProblem(409).andExpect {
			jsonPath("$.detail") { value("Setup is already complete") }
		}

		jdbc.queryForMap("select id, username, password_hash, totp_secret, totp_confirmed_at from app_user") shouldBe before
		jdbc.queryForObject("select count(*) from recovery_code", Int::class.java) shouldBe 10
	}

	@Test
	fun `the database rejects a second app_user row`() {
		setup.start(USERNAME, PASSWORD)
		val now = Timestamp.from(NOW)

		val error =
			shouldThrow<DataIntegrityViolationException> {
				jdbc.update(
					"insert into app_user (id, created_at, updated_at, version, username, password_hash, totp_secret) " +
						"values (?, ?, ?, 0, 'mallory.example', 'not-a-hash', 'not-a-secret')",
					UuidV7.generate(),
					now,
					now,
				)
			}

		error.message shouldContain "app_user_single_row"
		jdbc.queryForObject("select count(*) from app_user", Int::class.java) shouldBe 1
		jdbc.queryForObject("select username from app_user", String::class.java) shouldBe USERNAME
	}

	private fun <T> concurrently(
		threads: Int,
		action: (Int) -> T,
	): List<Result<T>> {
		val barrier = CyclicBarrier(threads)
		val pool = Executors.newFixedThreadPool(threads)
		try {
			val futures =
				(0 until threads).map { index ->
					pool.submit<Result<T>> {
						barrier.await(10, TimeUnit.SECONDS)
						runCatching { action(index) }
					}
				}
			return futures.map { it.get(60, TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}
	}

	private fun startSetup(
		username: String = USERNAME,
		password: String = PASSWORD,
	): Map<*, *> =
		post(SETUP, setupBody(username = username, password = password))
			.andExpect { status { isCreated() } }
			.andReturn()
			.response.contentAsString
			.let { jsonMapper.readValue(it, Map::class.java) }

	private fun confirm(code: String) = post(CONFIRM, jsonMapper.writeValueAsString(mapOf("code" to code)))

	private fun setupBody(
		username: String = USERNAME,
		password: String = PASSWORD,
	) = jsonMapper.writeValueAsString(mapOf("username" to username, "password" to password))

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
		const val CONFIRM = "$SETUP/confirm-totp"
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-fictitious"
		const val THREADS = 4
		val NOW: Instant = Instant.parse("2026-10-20T10:00:15Z")
	}
}
