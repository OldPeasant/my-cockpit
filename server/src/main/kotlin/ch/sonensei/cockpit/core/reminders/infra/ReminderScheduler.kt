package ch.sonensei.cockpit.core.reminders.infra

import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * Runs [ReminderScheduler] unless `cockpit.reminders.scheduler.enabled=false` (the test configuration turns it off, so
 * tests fire reminders explicitly with their own clock).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["cockpit.reminders.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class ReminderSchedulingConfiguration {
	@Bean
	fun reminderScheduler(reminders: ReminderService) = ReminderScheduler(reminders)
}

/** Fires due reminders every `cockpit.reminders.poll-interval` (default 15 s) after the previous run ended. */
class ReminderScheduler(
	private val reminders: ReminderService,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(
		fixedDelayString = "\${cockpit.reminders.poll-interval:PT15S}",
		initialDelayString = "\${cockpit.reminders.poll-interval:PT15S}",
	)
	fun run() {
		try {
			// One transaction per batch, so a long backlog (server was down) commits as it goes.
			while (reminders.fireDue() == ReminderService.FIRE_BATCH) continue
		} catch (e: RuntimeException) {
			// Retried on the next run; the reminders stay due.
			log.warn("Firing due reminders failed", e)
		}
	}
}
