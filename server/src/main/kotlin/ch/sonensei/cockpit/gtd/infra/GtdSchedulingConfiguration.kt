package ch.sonensei.cockpit.gtd.infra

import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.gtd.domain.TicklerJob
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.TriggerContext
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.scheduling.support.CronTrigger
import java.time.Instant
import java.time.ZoneId

/**
 * Fires on `cockpit.gtd.tickler-cron` (default 00:05) in the user's time zone, read anew for every run, so a changed
 * zone applies after the next run. Off with `cockpit.gtd.tickler.scheduler.enabled=false` (the tests run the job
 * themselves).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["cockpit.gtd.tickler.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class GtdSchedulingConfiguration(
	private val job: TicklerJob,
	private val settings: SettingsService,
	@Value("\${cockpit.gtd.tickler-cron:0 5 0 * * *}") private val cron: String,
) : SchedulingConfigurer {
	override fun configureTasks(registrar: ScheduledTaskRegistrar) {
		registrar.addTriggerTask({ job.run() }, UserZoneCronTrigger(cron, settings::timeZone))
	}
}

/** A cron trigger in the zone [zone] returns at the time the next run is planned. */
class UserZoneCronTrigger(
	private val cron: String,
	private val zone: () -> ZoneId,
) : Trigger {
	override fun nextExecution(context: TriggerContext): Instant? = CronTrigger(cron, zone()).nextExecution(context)
}
