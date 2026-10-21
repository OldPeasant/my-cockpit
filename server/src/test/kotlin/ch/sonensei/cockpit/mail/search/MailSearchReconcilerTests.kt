package ch.sonensei.cockpit.mail.search

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * [MailSearchReconciler] removes stale documents once per run, then runs [MailSearchIndex.indexMissing] in batches,
 * bounded per run, and survives failures.
 */
class MailSearchReconcilerTests {
	private val index = mockk<MailSearchIndex>()
	private val reconciler = MailSearchReconciler(index)

	@Test
	fun `continues while a batch was full and stops after the first partial batch, removing stale documents once`() {
		every { index.removeStale() } returns 3
		every { index.indexMissing(500) } returnsMany listOf(500, 500, 20)

		reconciler.run()

		verify(exactly = 1) { index.removeStale() }
		verify(exactly = 3) { index.indexMissing(500) }
	}

	@Test
	fun `one batch when nothing is missing`() {
		every { index.removeStale() } returns 2
		every { index.indexMissing(500) } returns 0

		reconciler.run()

		verify(exactly = 1) { index.removeStale() }
		verify(exactly = 1) { index.indexMissing(500) }
	}

	@Test
	fun `a run is bounded, a larger backlog continues on the next run`() {
		every { index.removeStale() } returns 0
		every { index.indexMissing(500) } returns 500

		reconciler.run()

		verify(exactly = 1) { index.removeStale() }
		verify(exactly = 200) { index.indexMissing(500) }
	}

	@Test
	fun `a failure is logged and does not escape the scheduled run`() {
		every { index.removeStale() } throws IllegalStateException("database down")

		reconciler.run()

		verify(exactly = 0) { index.indexMissing(any()) }
	}
}
