package ch.sonensei.cockpit.contacts.carddav

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * Runs [CardDavContactsSyncScheduler] unless `cockpit.contacts.carddav.sync.scheduler.enabled=false` (the test
 * configuration turns it off, so tests sync explicitly).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["cockpit.contacts.carddav.sync.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class CardDavContactsSyncSchedulingConfiguration {
	@Bean
	fun cardDavContactsSyncScheduler(sync: CardDavContactsSync) = CardDavContactsSyncScheduler(sync)
}

/** Syncs the CardDAV contacts every `cockpit.contacts.carddav.sync.interval` (default 5 min) after the last run ended. */
class CardDavContactsSyncScheduler(
	private val sync: CardDavContactsSync,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(
		fixedDelayString = "\${cockpit.contacts.carddav.sync.interval:PT5M}",
		initialDelayString = "\${cockpit.contacts.carddav.sync.interval:PT5M}",
	)
	fun run() {
		try {
			sync.syncAll()
		} catch (e: RuntimeException) {
			// Failures of an account are recorded in its state; this is the database or a bug. Retried on the next run.
			log.warn("CardDAV contacts sync failed: {}", e.javaClass.name)
		}
	}
}
