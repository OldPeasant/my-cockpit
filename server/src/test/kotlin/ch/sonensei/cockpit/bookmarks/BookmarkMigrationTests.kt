package ch.sonensei.cockpit.bookmarks

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Guards the bookmarks Flyway migrations against version clashes with other modules (a duplicate version breaks every
 * Spring context) and checks they follow the `<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql` convention.
 */
class BookmarkMigrationTests {
	private val migrations =
		PathMatchingResourcePatternResolver()
			.getResources("classpath*:db/migration/**/*.sql")
			.map { it.url.toString().substringAfterLast("db/migration/") }
			.distinct()

	private val bookmarks = migrations.filter { it.startsWith("bookmarks/") }

	@Test
	fun `bookmarks migrations follow the module folder and naming convention`() {
		bookmarks.shouldNotBeEmpty()
		bookmarks.forEach { path ->
			withClue("$path must be named bookmarks/V<yyyyMMddHHmm>__bookmarks_<desc>.sql") {
				NAME.matches(path) shouldBe true
			}
		}
	}

	@Test
	fun `bookmarks migrations use versions no other migration uses`() {
		bookmarks.shouldNotBeEmpty()
		val others = migrations - bookmarks.toSet()
		others.shouldNotBeEmpty()
		bookmarks.forEach { path ->
			withClue("$path shares its version with another migration") {
				others.filter { version(it) == version(path) }.shouldBeEmpty()
			}
		}
		bookmarks
			.groupBy(::version)
			.filterValues { it.size > 1 }
			.keys
			.shouldBeEmpty()
	}

	/** Flyway version of `<module>/V<version>__<desc>.sql`; Flyway reads `_` in a version as `.`. */
	private fun version(path: String): String =
		path
			.substringAfterLast("/")
			.removePrefix("V")
			.substringBefore("__")
			.replace('_', '.')

	private companion object {
		val NAME = Regex("""bookmarks/V\d{12}__bookmarks_[a-z0-9_]+\.sql""")
	}
}
