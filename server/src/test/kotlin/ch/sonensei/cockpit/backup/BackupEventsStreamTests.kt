package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupJobService
import ch.sonensei.cockpit.backup.domain.BackupJobState
import ch.sonensei.cockpit.core.auth.domain.AuthSession
import ch.sonensei.cockpit.core.auth.domain.LoginService
import ch.sonensei.cockpit.core.auth.domain.UserAccount
import ch.sonensei.cockpit.core.auth.infra.AuthSessionRepository
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Backup progress over the real SSE stream (`GET /api/v1/events`, embedded server, session cookie of a stored login):
 * progress events of type `BACKUP` reach a connected client while the export is still running inside its snapshot
 * transaction, and the completion follows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ControllableBackupConfiguration::class)
class BackupEventsStreamTests(
	@Autowired private val accounts: UserAccountRepository,
	@Autowired private val sessions: AuthSessionRepository,
	@Autowired private val jobs: BackupJobService,
	@Autowired private val contributor: ControllableBackupContributor,
	@Autowired private val clock: Clock,
	@LocalServerPort private val port: Int,
) : PostgresTestBase() {
	private val client = HttpClient.newHttpClient()
	private val reader = Executors.newSingleThreadExecutor()
	private lateinit var account: UserAccount
	private var jobId: UUID? = null

	@BeforeEach
	fun login() {
		val now = clock.instant()
		account = accounts.save(UserAccount("backup.sse.tester", "fictitious-password-hash", EncryptedSecret("fictitious-totp-secret")))
		sessions.save(AuthSession(account, LoginService.hash(TOKEN), rememberDevice = false, now, now.plus(Duration.ofHours(1))))
	}

	@AfterEach
	fun cleanUp() {
		contributor.gate?.countDown()
		contributor.gate = null
		jobId?.let { awaitFinished(it) }
		reader.shutdownNow()
		sessions.deleteAll(sessions.findAll().filter { it.tokenHash == LoginService.hash(TOKEN) })
		accounts.deleteById(account.id)
	}

	@Test
	fun `progress of a running backup reaches the SSE stream before the export finishes`() {
		val response =
			client.send(
				request().header("Cookie", "${SessionCookies.NAME}=$TOKEN").build(),
				HttpResponse.BodyHandlers.ofInputStream(),
			)
		response.statusCode() shouldBe 200
		val lines = response.body().bufferedReader()
		lines.awaitLine { it == ":connected" }

		contributor.gate = CountDownLatch(1)
		val job = jobs.start().also { jobId = it.id }

		val created = lines.awaitLine { it.startsWith("data:") && it.contains(job.id.toString()) }
		created shouldContain "\"type\":\"${BackupJobService.EVENT_TYPE}\""
		created shouldContain "\"op\":\"CREATED\""
		// Other contributors may come before the gated one: read their steps until the gated step's own event arrived.
		// Step n (counted from 0) is announced by the (n + 1)th UPDATED event.
		var updates = 0
		do {
			lines.awaitUpdate(job.id)
			updates++
			val gated = jobs.get(job.id).let { it.step == GATED_STEP && it.stepsDone + 1 == updates }
		} while (!gated)
		// The export is still held inside its snapshot transaction, yet its progress has been delivered.
		jobs.get(job.id).state shouldBe BackupJobState.RUNNING
		jobs.get(job.id).step shouldBe GATED_STEP

		contributor.gate!!.countDown()
		awaitFinished(job.id) shouldBe BackupJobState.COMPLETED
		// Every remaining step and the completion are announced too: all steps, "done", then COMPLETED.
		repeat(jobs.get(job.id).stepsTotal + 2 - updates) { lines.awaitUpdate(job.id) }
		response.body().close()
	}

	private fun BufferedReader.awaitUpdate(id: UUID) =
		awaitLine { it.startsWith("data:") && it.contains(id.toString()) } shouldContain "\"op\":\"UPDATED\""

	private fun awaitFinished(id: UUID): BackupJobState {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		while (jobs.get(id).state == BackupJobState.RUNNING) {
			check(System.nanoTime() < deadline) { "Backup $id did not finish" }
			Thread.sleep(20)
		}
		return jobs.get(id).state
	}

	private fun request() =
		HttpRequest
			.newBuilder(URI.create("http://localhost:$port/api/v1/events"))
			.header("Accept", "text/event-stream")
			.GET()

	private fun BufferedReader.awaitLine(condition: (String) -> Boolean): String =
		reader
			.submit<String> {
				generateSequence { readLine() }.first(condition)
			}.get(5, TimeUnit.SECONDS)

	private companion object {
		const val TOKEN = "fictitious-backup-sse-session-token"
		const val GATED_STEP = "controllable: data"
	}
}
