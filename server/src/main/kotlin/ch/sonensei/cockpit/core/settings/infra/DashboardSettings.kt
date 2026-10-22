package ch.sonensei.cockpit.core.settings.infra

import ch.sonensei.cockpit.core.settings.domain.SettingDefinition
import ch.sonensei.cockpit.core.settings.domain.SettingType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The settings of the clients' dashboard (SPEC.md §8.7), registered with the settings service. */
@Configuration(proxyBeanMethods = false)
class DashboardSettings {
	@Bean
	fun dashboardWidgetsSetting(): SettingDefinition<List<String>> = DASHBOARD_WIDGETS

	companion object {
		/**
		 * The dashboard widgets in the order they are shown: a widget id (e.g. `today`), or `<id>:hidden` for a hidden
		 * one. Only the clients read the ids; widgets missing from the list are shown after the others. Empty: the
		 * clients' default layout.
		 */
		val DASHBOARD_WIDGETS =
			SettingDefinition(
				"dashboardWidgets",
				SettingType.TextList(maxItems = 50, maxLength = 60),
				emptyList(),
				"Dashboard widgets in display order; `<id>:hidden` for hidden ones",
			)
	}
}
