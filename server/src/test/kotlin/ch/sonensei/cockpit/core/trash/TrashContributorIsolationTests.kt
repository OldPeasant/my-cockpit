package ch.sonensei.cockpit.core.trash

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.trash.domain.TrashContributor
import ch.sonensei.cockpit.core.trash.domain.TrashContributorRegistry
import ch.sonensei.cockpit.core.trash.domain.TrashPurgeJob
import ch.sonensei.cockpit.core.trash.domain.TrashService
import ch.sonensei.cockpit.core.trash.domain.TrashedItem
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

/**
 * Contributors that use the database, like real modules: one whose listing fails with an SQL error inside a
 * transaction (which aborts a Postgres transaction and marks a shared Spring transaction rollback-only) must not take
 * the other contributors' items out of the list or stop the purge job.
 */
@SpringBootTest
@Import(MutableClockTestConfiguration::class, TrashContributorIsolationTests.DbContributorsConfiguration::class)
class TrashContributorIsolationTests(
	@Autowired private val trash: TrashService,
	@Autowired private val job: TrashPurgeJob,
	@Autowired private val contributors: DbContributors,
	@Autowired private val clock: MutableClock,
) : PostgresTestBase() {
	private val start = MutableClockTestConfiguration.START

	/** Reads through the database before answering from [delegate]; [failing] runs an invalid SQL statement instead. */
	class DbTrashContributor(
		private val delegate: FakeTrashContributor,
		private val jdbc: JdbcTemplate,
		transactions: PlatformTransactionManager,
	) : TrashContributor by delegate {
		private val tx = TransactionTemplate(transactions)

		@Volatile
		var failing = false

		override fun listDeleted(): List<TrashedItem> =
			tx.execute {
				if (failing) jdbc.queryForObject("select missing_column from no_such_table", Int::class.java)
				jdbc.queryForObject("select 1", Int::class.java)
				delegate.listDeleted()
			}!!

		override fun purge(id: UUID): Boolean =
			tx.execute {
				jdbc.queryForObject("select 1", Int::class.java)
				delegate.purge(id)
			}!!
	}

	class DbContributors(
		jdbc: JdbcTemplate,
		transactions: PlatformTransactionManager,
	) {
		val taskFake = FakeTrashContributor(ItemType.TASK)
		val noteFake = FakeTrashContributor(ItemType.NOTE)

		/** Listed first, so a broken shared transaction would also break [notes]. */
		val tasks = DbTrashContributor(taskFake, jdbc, transactions)
		val notes = DbTrashContributor(noteFake, jdbc, transactions)
	}

	@TestConfiguration(proxyBeanMethods = false)
	class DbContributorsConfiguration {
		@Bean
		fun dbContributors(
			jdbc: JdbcTemplate,
			transactions: PlatformTransactionManager,
		) = DbContributors(jdbc, transactions)

		@Bean
		@Primary
		fun dbTrashContributorRegistry(contributors: DbContributors): TrashContributorRegistry =
			TrashContributorRegistry(listOf(contributors.tasks, contributors.notes))
	}

	@BeforeEach
	fun setUp() {
		contributors.taskFake.reset()
		contributors.noteFake.reset()
		contributors.tasks.failing = false
		clock.set(start)
	}

	@Test
	fun `a contributor failing in the database is left out of the list, the others are listed`() {
		contributors.taskFake.addDeleted("Task", start)
		val note = contributors.noteFake.addDeleted("Note", start)
		contributors.tasks.failing = true

		trash.list(CursorRequest.of(null, null)).items.map { it.ref } shouldContainExactly listOf(note)
	}

	@Test
	fun `a contributor failing in the database does not stop the purge job for the others`() {
		val task = contributors.taskFake.addDeleted("Task", start)
		val note = contributors.noteFake.addDeleted("Note", start)
		clock.advance(Duration.ofDays(31))
		contributors.tasks.failing = true

		job.purgeExpired() shouldBe 1

		contributors.noteFake.purged shouldContainExactly listOf(note.id)
		contributors.noteFake.isDeleted(note.id) shouldBe false
		contributors.taskFake.isDeleted(task.id) shouldBe true
	}
}
