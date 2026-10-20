package ch.sonensei.cockpit.core.items

import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.core.web.ConflictException
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException

/**
 * A concurrent request can link the same pair between [LinkService.create]'s check and its insert. The repository is
 * mocked to force that interleaving deterministically: the check sees no link, the insert hits the unique index.
 */
class LinkServiceRaceTests {
	private val repository = mockk<LinkRepository>()
	private val tasks = FakeItemResolver(ItemType.TASK)
	private val notes = FakeItemResolver(ItemType.NOTE)
	private val service = LinkService(repository, ItemResolverRegistry(listOf(tasks, notes)))

	@Test
	fun `a pair linked concurrently between check and insert is a conflict`() {
		val task = tasks.add("Fix the fence")
		val note = notes.add("Fence measurements")
		every { repository.findTouching(any(), any()) } returns emptyList()
		every { repository.saveAndFlush(any()) } throws DataIntegrityViolationException("duplicate key value violates link_manual_pair")

		shouldThrow<ConflictException> { service.create(task, note, null) }
	}
}
