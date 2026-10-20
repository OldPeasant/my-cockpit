package ch.sonensei.cockpit.support

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Base class for Spring tests that need the database.
 *
 * One Postgres 16 container is started per test JVM and shared by every subclass; `@ServiceConnection`
 * points the datasource (and Flyway) at it. Spring's context cache reuses contexts across test classes
 * with the same configuration. With Podman, set `DOCKER_HOST` and `TESTCONTAINERS_RYUK_DISABLED`
 * (docs/ARCHITECTURE.md "Install").
 */
abstract class PostgresTestBase {
	companion object {
		@JvmField
		@ServiceConnection
		val postgres: PostgreSQLContainer =
			PostgreSQLContainer("postgres:16-alpine").apply { start() }
	}
}
