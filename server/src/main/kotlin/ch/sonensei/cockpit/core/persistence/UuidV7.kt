package ch.sonensei.cockpit.core.persistence

import com.fasterxml.uuid.Generators
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator
import java.util.UUID

/**
 * UUIDv7 ids (SPEC.md §4.2): time-ordered, so ids created later sort after earlier ones, also within the
 * same millisecond (the generator is monotonic).
 */
object UuidV7 {
	private val generator: TimeBasedEpochGenerator = Generators.timeBasedEpochGenerator()

	fun generate(): UUID = generator.generate()

	fun isUuidV7(id: UUID): Boolean = id.variant() == 2 && id.version() == 7
}
