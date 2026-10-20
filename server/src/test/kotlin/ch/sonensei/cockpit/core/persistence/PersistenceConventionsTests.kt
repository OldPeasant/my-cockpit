package ch.sonensei.cockpit.core.persistence

import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.hibernate.Hibernate
import org.hibernate.LazyInitializationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant

@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class PersistenceConventionsTests(
	@Autowired private val repository: PersistenceFixtureRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val appClock: Clock,
	@Autowired private val tx: TransactionTemplate,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val flyway: Flyway,
) : PostgresTestBase() {
	@BeforeEach
	fun reset() {
		repository.deleteAllInBatch()
		clock.set(MutableClockTestConfiguration.START)
	}

	@Test
	fun `the injectable clock is the one tests substitute`() {
		appClock shouldBe clock
	}

	@Test
	fun `create assigns a UUIDv7 id, timestamps from the clock and version 0`() {
		val saved = repository.save(PersistenceFixture("Buy milk"))

		UuidV7.isUuidV7(saved.id) shouldBe true
		saved.createdAt shouldBe MutableClockTestConfiguration.START
		saved.updatedAt shouldBe MutableClockTestConfiguration.START
		saved.version shouldBe 0
		saved.isNew shouldBe false

		val loaded = repository.findById(saved.id).orElseThrow()
		loaded.title shouldBe "Buy milk"
		loaded.createdAt shouldBe MutableClockTestConfiguration.START
	}

	@Test
	fun `the first core migration ran and enabled pg_trgm`() {
		jdbc.queryForObject(
			"select count(*) from flyway_schema_history where success and version = '202610200000' " +
				"and script = 'core/V202610200000__core_enable_pg_trgm.sql'",
			Int::class.java,
		) shouldBe 1
		jdbc.queryForObject("select count(*) from pg_extension where extname = 'pg_trgm'", Int::class.java) shouldBe 1
	}

	@Test
	fun `timestamps are stored as timestamptz`() {
		jdbc.queryForList(
			"select data_type from information_schema.columns where table_name = 'test_persistence_fixture' " +
				"and column_name in ('created_at', 'updated_at', 'deleted_at')",
			String::class.java,
		) shouldContainExactly List(3) { "timestamp with time zone" }
	}

	@Test
	fun `update moves updatedAt to the clock's time, keeps createdAt and bumps the version`() {
		val created = repository.save(PersistenceFixture("Draft"))
		clock.advance(Duration.ofHours(3))

		val updated =
			tx.execute {
				repository.findById(created.id).orElseThrow().apply { title = "Final" }
			}!!

		updated.createdAt shouldBe MutableClockTestConfiguration.START
		updated.updatedAt shouldBe MutableClockTestConfiguration.START.plus(Duration.ofHours(3))
		updated.version shouldBe 1
	}

	@Test
	fun `a client-supplied UUIDv7 id is kept, and creating it again is detectable`() {
		val clientId = UuidV7.generate()

		repository.save(PersistenceFixture("Offline capture", clientId)).id shouldBe clientId

		repository.existsById(clientId) shouldBe true
		repository.findById(clientId).orElseThrow().title shouldBe "Offline capture"
	}

	@Test
	fun `ids that are not UUIDv7 are rejected`() {
		shouldThrow<IllegalArgumentException> {
			PersistenceFixture("Bad id", java.util.UUID.randomUUID())
		}
	}

	@Test
	fun `saving a stale version raises an optimistic locking conflict`() {
		val created = repository.save(PersistenceFixture("Original"))
		val stale = repository.findById(created.id).orElseThrow()
		val fresh = repository.findById(created.id).orElseThrow()

		fresh.title = "Edited elsewhere"
		repository.save(fresh).version shouldBe 1

		stale.title = "Edited on an old copy"
		shouldThrow<OptimisticLockingFailureException> { repository.save(stale) }
		repository.findById(created.id).orElseThrow().title shouldBe "Edited elsewhere"
	}

	@Test
	fun `soft-deleted rows are excluded by the repository helper`() {
		val kept = repository.save(PersistenceFixture("Kept"))
		val deleted = repository.save(PersistenceFixture("Deleted"))
		clock.advance(Duration.ofMinutes(5))

		tx.executeWithoutResult { repository.findById(deleted.id).orElseThrow().softDelete(clock) }

		repository.findActiveById(kept.id).shouldNotBeNull()
		repository.findActiveById(deleted.id).shouldBeNull()
		repository.findAllActive().map { it.id } shouldContainExactly listOf(kept.id)
		repository.findAllDeleted().map { it.id } shouldContainExactly listOf(deleted.id)
		repository.findById(deleted.id).orElseThrow().deletedAt shouldBe
			MutableClockTestConfiguration.START.plus(Duration.ofMinutes(5))
	}

	@Test
	fun `soft delete keeps the first deletion time and restore brings the row back`() {
		val entity = repository.save(PersistenceFixture("Note"))
		val firstDeletion: Instant = clock.instant()

		tx.executeWithoutResult { repository.findById(entity.id).orElseThrow().softDelete(clock) }
		clock.advance(Duration.ofDays(1))
		tx.executeWithoutResult {
			val loaded = repository.findById(entity.id).orElseThrow()
			loaded.softDelete(clock)
			loaded.deletedAt shouldBe firstDeletion
			loaded.isDeleted shouldBe true
		}

		tx.executeWithoutResult { repository.findById(entity.id).orElseThrow().restore() }

		repository.findActiveById(entity.id).shouldNotBeNull().isDeleted shouldBe false
	}

	@Test
	fun `creating the same client id twice fails instead of overwriting the first row`() {
		val clientId = UuidV7.generate()
		repository.save(PersistenceFixture("First capture", clientId))

		shouldThrow<DataIntegrityViolationException> {
			repository.save(PersistenceFixture("Replayed capture", clientId))
		}

		val stored = repository.findById(clientId).orElseThrow()
		stored.title shouldBe "First capture"
		stored.version shouldBe 0
	}

	@Test
	fun `timestamps are stored as the exact UTC instant of the clock`() {
		val saved = repository.save(PersistenceFixture("Stored instant"))

		jdbc.queryForObject(
			"select to_char(created_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') " +
				"from test_persistence_fixture where id = ?",
			String::class.java,
			saved.id,
		) shouldBe MutableClockTestConfiguration.START.toString()
	}

	@Test
	fun `sub-microsecond clock instants are stored so that saved and reloaded timestamps are equal`() {
		clock.set(Instant.parse("2026-03-01T08:00:00.123456789Z"))
		val expected = Instant.parse("2026-03-01T08:00:00.123456Z")

		val saved = repository.save(PersistenceFixture("Nanos"))
		saved.createdAt shouldBe expected
		saved.updatedAt shouldBe expected
		val deleted = tx.execute { repository.findById(saved.id).orElseThrow().apply { softDelete(clock) } }!!
		deleted.deletedAt shouldBe expected

		val reloaded = repository.findById(saved.id).orElseThrow()
		reloaded.createdAt shouldBe saved.createdAt
		reloaded.updatedAt shouldBe deleted.updatedAt
		reloaded.deletedAt shouldBe deleted.deletedAt
	}

	@Test
	fun `active rows come in creation order and deleted rows newest deletion first`() {
		val ids = List(4) { repository.save(PersistenceFixture("Item $it")).id }
		clock.advance(Duration.ofMinutes(1))
		tx.executeWithoutResult { repository.findById(ids[0]).orElseThrow().softDelete(clock) }
		clock.advance(Duration.ofMinutes(1))
		tx.executeWithoutResult { repository.findById(ids[2]).orElseThrow().softDelete(clock) }

		repository.findAllActive().map { it.id } shouldContainExactly listOf(ids[1], ids[3])
		repository.findAllDeleted().map { it.id } shouldContainExactly listOf(ids[2], ids[0])
		// The inherited reads still see the Trash (restore, backup).
		repository.findAll().map { it.id } shouldContainExactlyInAnyOrder ids
	}

	@Test
	fun `a lazy proxy equals the loaded entity with the same id`() {
		val saved = repository.save(PersistenceFixture("Proxied"))
		val other = repository.save(PersistenceFixture("Other"))

		tx.executeWithoutResult {
			val proxy = repository.getReferenceById(saved.id)
			Hibernate.isInitialized(proxy) shouldBe false

			(saved == proxy) shouldBe true
			Hibernate.isInitialized(proxy) shouldBe false
			(proxy == saved) shouldBe true
			proxy.hashCode() shouldBe saved.hashCode()
			(proxy == other) shouldBe false
			proxy.toString() shouldBe "PersistenceFixture(id=${saved.id})"
		}
	}

	@Test
	fun `an entity compares with a detached uninitialized proxy without touching the session`() {
		val saved = repository.save(PersistenceFixture("Detached"))
		val other = repository.save(PersistenceFixture("Unrelated"))

		// Outside a transaction the repository call closes its session, so the proxy is detached.
		val detached = repository.getReferenceById(saved.id)
		Hibernate.isInitialized(detached) shouldBe false

		// Only the receiving side is safe: methods called on the proxy itself (hashCode, toString) still load it.
		(saved == detached) shouldBe true
		(other == detached) shouldBe false
		Hibernate.isInitialized(detached) shouldBe false
	}

	@Test
	fun `toString names the entity class and id, and on a detached proxy it fails as documented`() {
		val saved = repository.save(PersistenceFixture("Printable"))

		repository.findById(saved.id).orElseThrow().toString() shouldBe "PersistenceFixture(id=${saved.id})"

		// BaseEntity KDoc and ARCHITECTURE.md: toString/hashCode on an uninitialized proxy load it, so they fail once detached.
		val detached = repository.getReferenceById(saved.id)
		shouldThrow<LazyInitializationException> { detached.toString() }
		shouldThrow<LazyInitializationException> { detached.hashCode() }
	}

	@Test
	fun `the first core migration creates no tables`() {
		val schema = "core_first_migration"
		try {
			Flyway
				.configure()
				.configuration(flyway.configuration)
				.schemas(schema)
				.defaultSchema(schema)
				.locations("classpath:db/migration/core")
				.target("202610200000")
				.load()
				.migrate()
				.migrationsExecuted shouldBe 1

			jdbc.queryForList(
				"select table_name from information_schema.tables where table_schema = ?",
				String::class.java,
				schema,
			) shouldContainExactly listOf("flyway_schema_history")
		} finally {
			jdbc.execute("drop schema if exists $schema cascade")
		}
	}
}
