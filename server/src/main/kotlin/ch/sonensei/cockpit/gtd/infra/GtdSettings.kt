package ch.sonensei.cockpit.gtd.infra

import ch.sonensei.cockpit.core.settings.domain.SettingDefinition
import ch.sonensei.cockpit.core.settings.domain.SettingType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The settings of the GTD module (SPEC.md §4.7), registered with the settings service. */
@Configuration(proxyBeanMethods = false)
class GtdSettings {
	@Bean
	fun nextActionPresetsSetting(): SettingDefinition<List<String>> = NEXT_ACTION_PRESETS

	companion object {
		/**
		 * Saved filter presets of the Next Actions view (SPEC.md §5.3), e.g. "Low energy @home ≤ 15 min". Each entry is
		 * a preset as JSON (`{"name": ..., "filter": {...}}`) that only the clients read; the server keeps them as text.
		 */
		val NEXT_ACTION_PRESETS =
			SettingDefinition(
				"nextActionPresets",
				SettingType.TextList(maxItems = 50, maxLength = 2000),
				emptyList(),
				"Saved filter presets of the Next Actions view (JSON per preset)",
			)
	}
}
