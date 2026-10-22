package ch.sonensei.cockpit.contacts.google

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * Runs [GoogleContactsSyncScheduler] unless `cockpit.contacts.google.sync.scheduler.enabled=false` (the test
 * configuration turns it off, so tests sync explicitly).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["cockpit.contacts.google.sync.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class GoogleContactsSyncSchedulingConfiguration {
	@Bean
	fun googleContactsSyncScheduler(sync: GoogleContactsSync) = GoogleContactsSyncScheduler(sync)
}

/** Syncs the Google contacts every `cockpit.contacts.google.sync.interval` (default 5 min) after the last run ended. */
class GoogleContactsSyncScheduler(
	private val sync: GoogleContactsSync,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(
		fixedDelayString = "\${cockpit.contacts.google.sync.interval:PT5M}",
		initialDelayString = "\${cockpit.contacts.google.sync.interval:PT5M}",
	)
	fun run() {
		try {
			sync.syncAll()
		} catch (e: RuntimeException) {
			// Failures of an account are recorded in its state; this is the database or a bug. Retried on the next run.
			log.warn("Google contacts sync failed: {}", e.javaClass.name)
		}
	}
}
