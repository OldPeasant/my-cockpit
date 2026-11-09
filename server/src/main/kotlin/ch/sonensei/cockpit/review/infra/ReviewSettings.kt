package ch.sonensei.cockpit.review.infra

import ch.sonensei.cockpit.core.settings.domain.SettingDefinition
import ch.sonensei.cockpit.core.settings.domain.SettingType
import ch.sonensei.cockpit.review.domain.WeeklyReviewSchedule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The settings of the review module (SPEC.md §4.7), registered with the settings service. */
@Configuration(proxyBeanMethods = false)
class ReviewSettings {
	@Bean
	fun weeklyReviewReminderSetting(): SettingDefinition<String> = WEEKLY_REVIEW_REMINDER

	companion object {
		/** When to remind of the weekly review (SPEC.md §8.6): `off` or `<DAY>@HH:mm` in the owner's time zone. */
		val WEEKLY_REVIEW_REMINDER =
			SettingDefinition(
				"weeklyReviewReminder",
				SettingType.Formatted("off or a weekday and time such as SUNDAY@18:00") {
					it.takeIf(WeeklyReviewSchedule::isValid)
				},
				WeeklyReviewSchedule.OFF,
				"Weekly review reminder: off, or weekday and time (e.g. SUNDAY@18:00) in your time zone",
			)
	}
}
