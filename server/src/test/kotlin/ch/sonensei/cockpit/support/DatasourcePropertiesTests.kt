package ch.sonensei.cockpit.support

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.PropertiesPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource

/**
 * The datasource of every profile comes from `SPRING_DATASOURCE_*` (SPEC.md §11), with local defaults only
 * where a developer or the e2e stack needs them. Plain unit test: resolves the property files without a context.
 */
class DatasourcePropertiesTests {
	private val environmentVariables =
		mapOf(
			"SPRING_DATASOURCE_URL" to "jdbc:postgresql://db.example.test:5432/cockpit_fixture",
			"SPRING_DATASOURCE_USERNAME" to "fixture_user",
			"SPRING_DATASOURCE_PASSWORD" to "fixture-password",
		)

	@Test
	fun `base config takes the datasource from SPRING_DATASOURCE variables`() {
		val resolver = resolver(environmentVariables, "application.properties")

		resolver.getProperty("spring.datasource.url") shouldBe "jdbc:postgresql://db.example.test:5432/cockpit_fixture"
		resolver.getProperty("spring.datasource.username") shouldBe "fixture_user"
		resolver.getProperty("spring.datasource.password") shouldBe "fixture-password"
	}

	@Test
	fun `base config falls back to a local database without a default password`() {
		val resolver = resolver(emptyMap(), "application.properties")

		resolver.getProperty("spring.datasource.url") shouldBe "jdbc:postgresql://localhost:5432/cockpit"
		resolver.getProperty("spring.datasource.username") shouldBe "cockpit"
		resolver.getProperty("spring.datasource.password") shouldBe ""
	}

	@Test
	fun `dev and e2e profiles also honour SPRING_DATASOURCE variables`() {
		for (profileFile in listOf("application-dev.properties", "application-e2e.properties")) {
			val resolver = resolver(environmentVariables, profileFile, "application.properties")

			resolver.getProperty("spring.datasource.url") shouldBe "jdbc:postgresql://db.example.test:5432/cockpit_fixture"
			resolver.getProperty("spring.datasource.username") shouldBe "fixture_user"
			resolver.getProperty("spring.datasource.password") shouldBe "fixture-password"
		}
	}

	@Test
	fun `e2e profile defaults to its own database`() {
		val resolver = resolver(emptyMap(), "application-e2e.properties", "application.properties")

		resolver.getProperty("spring.datasource.url") shouldBe "jdbc:postgresql://localhost:5432/cockpit_e2e"
	}

	/** Property files in precedence order (profile first), with [variables] taking precedence over all of them. */
	private fun resolver(
		variables: Map<String, Any>,
		vararg files: String,
	): PropertySourcesPropertyResolver {
		val sources = MutablePropertySources()
		sources.addLast(MapPropertySource("environment", variables))
		files.forEach { file ->
			PropertiesPropertySourceLoader().load(file, ClassPathResource(file)).forEach(sources::addLast)
		}
		return PropertySourcesPropertyResolver(sources)
	}
}
