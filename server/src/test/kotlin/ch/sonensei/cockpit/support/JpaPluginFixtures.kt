// Deliberately outside `ch.sonensei.cockpit`: the application's entity scan must not pick these up
// (Hibernate `ddl-auto=validate` would look for their tables). Used only by JpaCompilerPluginTests.
package ch.sonensei.jpafixture

import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass

@MappedSuperclass
abstract class FixtureBase(
	@Id
	val id: String,
)

@Entity
class FixtureEntity(
	id: String,
	val title: String,
) : FixtureBase(id)

@Embeddable
class FixtureEmbeddable(
	val street: String,
)

/** No JPA annotation: the compiler plugins must leave it final and without a no-arg constructor. */
class PlainFixture(
	val name: String,
)
