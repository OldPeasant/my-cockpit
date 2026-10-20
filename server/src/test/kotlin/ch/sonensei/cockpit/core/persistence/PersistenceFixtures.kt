package ch.sonensei.cockpit.core.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.util.UUID

/** Entity on the test-only table `test_persistence_fixture` (test migration in `db/migration/core`). */
@Entity
@Table(name = "test_persistence_fixture")
class PersistenceFixture(
	@Column(name = "title", nullable = false)
	var title: String,
	id: UUID? = null,
) : SoftDeletableEntity(id)

interface PersistenceFixtureRepository : SoftDeletableRepository<PersistenceFixture>

/** Replaces the system clock with a [MutableClock] for tests that control time. */
@TestConfiguration(proxyBeanMethods = false)
class MutableClockTestConfiguration {
	@Bean
	@Primary
	fun mutableClock(): MutableClock = MutableClock(START)

	companion object {
		val START: Instant = Instant.parse("2026-03-01T08:00:00Z")
	}
}
