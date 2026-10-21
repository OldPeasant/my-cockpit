package ch.sonensei.cockpit.mail.search

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/** [MailSearchReconciler] runs [MailSearchIndex.reconcile] in batches, bounded per run, and survives failures. */
class MailSearchReconcilerTests {
	private val index = mockk<MailSearchIndex>()
	private val reconciler = MailSearchReconciler(index)

	@Test
	fun `continues while a batch was full and stops after the first partial batch`() {
		every { index.reconcile(500) } returnsMany listOf(3 to 500, 0 to 500, 0 to 20)

		reconciler.run()

		verify(exactly = 3) { index.reconcile(500) }
	}

	@Test
	fun `one call when nothing is missing`() {
		every { index.reconcile(500) } returns (2 to 0)

		reconciler.run()

		verify(exactly = 1) { index.reconcile(500) }
	}

	@Test
	fun `a run is bounded, a larger backlog continues on the next run`() {
		every { index.reconcile(500) } returns (0 to 500)

		reconciler.run()

		verify(exactly = 200) { index.reconcile(500) }
	}

	@Test
	fun `a failure is logged and does not escape the scheduled run`() {
		every { index.reconcile(500) } throws IllegalStateException("database down")

		reconciler.run()

		verify(exactly = 1) { index.reconcile(500) }
	}
}
