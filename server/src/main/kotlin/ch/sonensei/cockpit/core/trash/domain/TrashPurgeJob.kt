package ch.sonensei.cockpit.core.trash.domain

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Purges items deleted more than [TrashService.RETENTION] ago (SPEC.md §4.2). Runs daily (`cockpit.trash.purge-cron`,
 * default 03:30 UTC); time comes from the application [Clock], so tests drive it with the test clock.
 */
@Component
class TrashPurgeJob(
	private val trash: TrashService,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * Purges every item whose `deletedAt` is strictly before now minus [TrashService.RETENTION], each in its own
	 * transaction: an item that fails is logged and left for the next run, the others are still purged.
	 *
	 * @return the number of items purged
	 */
	@Scheduled(cron = "\${cockpit.trash.purge-cron:0 30 3 * * *}", zone = "UTC")
	fun purgeExpired(): Int {
		val cutoff = clock.instant().minus(TrashService.RETENTION)
		var purged = 0
		trash.entries().filter { it.item.deletedAt.isBefore(cutoff) }.forEach { entry ->
			try {
				trash.purge(entry.ref)
				purged++
			} catch (e: RuntimeException) {
				log.warn("Purging {} from the Trash failed", entry.ref, e)
			}
		}
		if (purged > 0) log.info("Purged {} items deleted before {} from the Trash", purged, cutoff)
		return purged
	}
}

/**
 * Enables `@Scheduled` for [TrashPurgeJob]. Other modules may enable it as well (it is idempotent); the class name is
 * module-specific so their configuration beans do not clash.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class TrashSchedulingConfiguration
