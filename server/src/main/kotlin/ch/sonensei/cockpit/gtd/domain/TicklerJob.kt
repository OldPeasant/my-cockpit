package ch.sonensei.cockpit.gtd.domain

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The tickler job (SPEC.md §5.1): due ticklers back to the Inbox, due scheduled tasks to Next ([TaskService.releaseDue]).
 * It runs daily ([ch.sonensei.cockpit.gtd.infra.GtdSchedulingConfiguration]) and before tasks are read. The dates come
 * from the application clock, so tests drive it with the test clock.
 */
@Component
class TicklerJob(
	private val tasks: TaskService,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * Releases the due tasks. A failure (e.g. a concurrent run moved the same task) is logged and left for the next run
	 * or read, so a read never fails because of it.
	 *
	 * @return the number of tasks moved
	 */
	fun run(): Int =
		try {
			tasks.releaseDue().also { if (it > 0) log.info("Tickler job moved {} due tasks", it) }
		} catch (e: RuntimeException) {
			log.warn("Tickler job failed", e)
			0
		}
}
