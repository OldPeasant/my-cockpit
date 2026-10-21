package ch.sonensei.cockpit.notes

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Guards the notes Flyway migrations against version clashes with other modules. Parallel tasks pick
 * timestamp versions independently; a duplicate version makes every Spring context fail with
 * "Found more than one migration with version ...", which hides the cause behind unrelated test failures.
 */
class NoteMigrationTests {
	private val migrations =
		PathMatchingResourcePatternResolver()
			.getResources("classpath*:db/migration/**/*.sql")
			.map { it.url.toString().substringAfterLast("db/migration/") }
			.distinct()

	@Test
	fun `notes migrations follow the module folder and naming convention`() {
		val notes = migrations.filter { it.startsWith("notes/") }
		notes.shouldNotBeEmpty()
		notes.forEach { path ->
			withClue("$path must be named notes/V<yyyyMMddHHmm>__notes_<desc>.sql") {
				NOTES_NAME.matches(path) shouldBe true
			}
		}
	}

	@Test
	fun `notes migrations use versions no other migration uses`() {
		val notes = migrations.filter { it.startsWith("notes/") }
		notes.shouldNotBeEmpty()
		val others = migrations - notes.toSet()
		others.shouldNotBeEmpty()
		notes.forEach { path ->
			val version = version(path)
			withClue("$path shares version $version with another migration") {
				others.filter { version(it) == version }.shouldBeEmpty()
			}
		}
		withClue("notes migrations must not share a version among themselves") {
			notes
				.groupBy(::version)
				.filterValues { it.size > 1 }
				.keys
				.shouldBeEmpty()
		}
	}

	@Test
	fun `version comparison treats underscore and dot separators like Flyway does`() {
		version("core/V202610200000_1__core_fixture.sql") shouldBe version("x/V202610200000.1__other.sql")
		(version("core/V202610200000_1__core_fixture.sql") == version("core/V202610200000__core_enable.sql")) shouldBe false
	}

	/** Flyway version of `<module>/V<version>__<desc>.sql`; Flyway reads `_` in a version as `.`. */
	private fun version(path: String): String =
		path
			.substringAfterLast("/")
			.removePrefix("V")
			.substringBefore("__")
			.replace('_', '.')

	private companion object {
		val NOTES_NAME = Regex("""notes/V\d{12}__notes_[a-z0-9_]+\.sql""")
	}
}
