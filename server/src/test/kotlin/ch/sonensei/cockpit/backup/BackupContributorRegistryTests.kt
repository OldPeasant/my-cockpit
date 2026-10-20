package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupContributorRegistry
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class BackupContributorRegistryTests {
	private class Stub(
		override val id: String,
		override val dependsOn: Set<String> = emptySet(),
	) : BackupContributor {
		override fun export(writer: BackupDataWriter) = Unit

		override fun import(reader: BackupDataReader) = Unit
	}

	private fun order(vararg contributors: Stub) = BackupContributorRegistry(contributors.toList()).ordered.map { it.id }

	@Test
	fun `dependencies come first, otherwise ids in order`() {
		order(
			Stub("notes", setOf("core")),
			Stub("gtd", setOf("core", "notes")),
			Stub("core"),
			Stub("bookmarks"),
		) shouldContainExactly listOf("bookmarks", "core", "notes", "gtd")
	}

	@Test
	fun `no contributors give an empty order`() {
		order().shouldContainExactly(emptyList())
	}

	@Test
	fun `duplicate ids fail`() {
		shouldThrow<IllegalStateException> { order(Stub("gtd"), Stub("gtd")) }.message shouldContain "More than one"
	}

	@Test
	fun `invalid ids fail`() {
		shouldThrow<IllegalStateException> { order(Stub("Gtd")) }.message shouldContain "Invalid"
		shouldThrow<IllegalStateException> { order(Stub("")) }
		shouldThrow<IllegalStateException> { order(Stub("a/b")) }
	}

	@Test
	fun `unknown dependencies fail`() {
		shouldThrow<IllegalStateException> { order(Stub("gtd", setOf("core"))) }.message shouldContain "unknown"
	}

	@Test
	fun `cycles fail`() {
		shouldThrow<IllegalStateException> {
			order(Stub("a", setOf("b")), Stub("b", setOf("c")), Stub("c", setOf("a")), Stub("d"))
		}.message shouldContain "cycle among [a, b, c]"
	}
}
