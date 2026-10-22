package ch.sonensei.cockpit.core.persistence

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Guards the documented layout (docs/ARCHITECTURE.md "Persistence conventions"):
 * `db/migration/<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql`, with the `pg_trgm` migration first.
 * Test-only migrations add `_<n>` to the version and `test_` to the description
 * (`V<yyyyMMddHHmm>_<n>__<module>_test_<desc>.sql`), so they never collide with real migrations.
 */
class MigrationConventionTests {
	private val migrations =
		PathMatchingResourcePatternResolver()
			.getResources("classpath*:db/migration/**/*.sql")
			.map { it.url.toString().substringAfterLast("db/migration/") }

	@Test
	fun `every migration lives in a module folder and is named V yyyyMMddHHmm __ module _ desc`() {
		migrations.shouldNotBeEmpty()
		migrations.forEach { path ->
			withClue(path) {
				val parts = path.split("/")
				parts shouldHaveSize 2
				val (module, file) = parts
				val match = NAME.matchEntire(file).shouldNotBeNull()
				match.groupValues[3] shouldBe module
				LocalDateTime.parse(match.groupValues[1], VERSION_FORMAT)
				withClue("a _<n> version suffix is reserved for, and required by, test-only (test_) migrations") {
					match.groupValues[2].isNotEmpty() shouldBe match.groupValues[4].startsWith("test_")
				}
			}
		}
	}

	@Test
	fun `no two migrations share a Flyway version, even across module folders`() {
		val duplicates =
			migrations
				.groupBy { path -> NAME.matchEntire(path.substringAfter("/"))?.groupValues?.let { v -> v[1] + v[2] } }
				.filterKeys { it != null }
				.filterValues { it.size > 1 }
		withClue("pick a later V<yyyyMMddHHmm> for one of them; Flyway refuses to start otherwise") {
			duplicates.shouldBeEmpty()
		}
	}

	@Test
	fun `the core pg_trgm migration has the lowest version`() {
		migrations.minBy { NAME.matchEntire(it.substringAfter("/"))!!.groupValues.let { v -> v[1] + v[2] } } shouldBe
			"core/V202610200000__core_enable_pg_trgm.sql"
	}

	private companion object {
		val NAME = Regex("""V(\d{12})(_\d+)?__([a-z0-9]+)_([a-z0-9_]+)\.sql""")
		val VERSION_FORMAT: DateTimeFormatter =
			DateTimeFormatter.ofPattern("uuuuMMddHHmm").withResolverStyle(ResolverStyle.STRICT)
	}
}
