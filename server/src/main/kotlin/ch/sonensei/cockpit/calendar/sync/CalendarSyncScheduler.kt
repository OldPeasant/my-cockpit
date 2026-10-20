package ch.sonensei.cockpit.calendar.sync

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * Runs [CalendarSyncScheduler] unless `cockpit.calendar.sync.scheduler.enabled=false` (the test configuration turns it
 * off, so tests sync explicitly).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["cockpit.calendar.sync.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class CalendarSyncSchedulingConfiguration {
	@Bean
	fun calendarSyncScheduler(sync: CalendarSyncService) = CalendarSyncScheduler(sync)
}

/**
 * Checks every `cockpit.calendar.sync.tick` (default 30 s) after the previous check ended which sources are due (their
 * adapter's poll interval passed since the last attempt) and syncs them.
 */
class CalendarSyncScheduler(
	private val sync: CalendarSyncService,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(fixedDelayString = "\${cockpit.calendar.sync.tick:PT30S}", initialDelayString = "\${cockpit.calendar.sync.tick:PT30S}")
	fun run() {
		try {
			sync.syncDue()
		} catch (e: RuntimeException) {
			// Failures of a source are recorded in its status; this is the database or a bug. Retried on the next tick.
			log.warn("Calendar sync check failed: {}", e.javaClass.name)
		}
	}
}
