package ch.sonensei.cockpit.support

import ch.sonensei.jpafixture.FixtureBase
import ch.sonensei.jpafixture.FixtureEmbeddable
import ch.sonensei.jpafixture.FixtureEntity
import ch.sonensei.jpafixture.PlainFixture
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * The build applies `kotlin("plugin.jpa")` and opens JPA-annotated classes, so Kotlin entities get the no-arg
 * constructor Hibernate needs and are not final (Hibernate proxies subclass them). Plain unit test on classes
 * compiled by this build.
 */
class JpaCompilerPluginTests {
	private val annotatedClasses =
		listOf(FixtureEntity::class.java, FixtureBase::class.java, FixtureEmbeddable::class.java)

	@Test
	fun `JPA-annotated Kotlin classes get a no-arg constructor`() {
		annotatedClasses.forEach { type ->
			hasNoArgConstructor(type) shouldBe true
		}
	}

	@Test
	fun `JPA-annotated Kotlin classes are open for Hibernate proxies`() {
		annotatedClasses.forEach { type ->
			Modifier.isFinal(type.modifiers) shouldBe false
		}
	}

	@Test
	fun `an entity can be instantiated through its no-arg constructor`() {
		val entity = FixtureEntity::class.java.getDeclaredConstructor().newInstance()

		entity.javaClass shouldBe FixtureEntity::class.java
	}

	@Test
	fun `classes without JPA annotations stay final and keep their constructors`() {
		// Control: proves the assertions above detect a missing plugin, and that allOpen is scoped to JPA.
		Modifier.isFinal(PlainFixture::class.java.modifiers) shouldBe true
		hasNoArgConstructor(PlainFixture::class.java) shouldBe false
	}

	private fun hasNoArgConstructor(type: Class<*>): Boolean = runCatching { type.getDeclaredConstructor() }.isSuccess
}
