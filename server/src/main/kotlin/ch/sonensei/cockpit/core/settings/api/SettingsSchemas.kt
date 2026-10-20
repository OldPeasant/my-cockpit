package ch.sonensei.cockpit.core.settings.api

import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode

// Documentation only: the API reads and writes plain JSON objects (SettingsService validates them), and these classes
// describe the core keys (CoreSettings) for the generated clients. Settings of other modules are additional properties.

@Schema(
	name = "Settings",
	description = "Every setting with its current value; settings registered by other modules are additional properties",
	additionalProperties = AdditionalPropertiesValue.TRUE,
)
class Settings(
	@field:Schema(description = "IANA time zone", example = "Europe/Zurich", requiredMode = RequiredMode.REQUIRED)
	val timeZone: String,
	@field:Schema(
		allowableValues = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"],
		requiredMode = RequiredMode.REQUIRED,
	)
	val weekStart: String,
	@field:Schema(allowableValues = ["light", "dark", "system"], requiredMode = RequiredMode.REQUIRED)
	val theme: String,
	@field:Schema(description = "Labels of LOW, MEDIUM, HIGH", requiredMode = RequiredMode.REQUIRED)
	val energyLabels: Map<String, String>,
	@field:Schema(description = "Labels of ROUTINE, MIXED, CREATIVE", requiredMode = RequiredMode.REQUIRED)
	val creativityLabels: Map<String, String>,
	@field:Schema(allowableValues = ["block", "allow"], requiredMode = RequiredMode.REQUIRED)
	val mailImagePolicy: String,
	@field:Schema(description = "Senders (addresses or domains) whose remote images are always loaded", requiredMode = RequiredMode.REQUIRED)
	val mailImageAllowedSenders: List<String>,
)

@Schema(
	name = "SettingsPatch",
	description = "The settings to change; null resets a setting to its default, absent ones stay unchanged",
	additionalProperties = AdditionalPropertiesValue.TRUE,
)
class SettingsPatch(
	@field:Schema(description = "IANA time zone", example = "Europe/Zurich", types = ["string", "null"])
	val timeZone: String?,
	@field:Schema(allowableValues = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"], types = ["string", "null"])
	val weekStart: String?,
	@field:Schema(allowableValues = ["light", "dark", "system"], types = ["string", "null"])
	val theme: String?,
	@field:Schema(description = "A non-blank label (max. 40 characters) for each of LOW, MEDIUM, HIGH", types = ["object", "null"])
	val energyLabels: Map<String, String>?,
	@field:Schema(description = "A non-blank label (max. 40 characters) for each of ROUTINE, MIXED, CREATIVE", types = ["object", "null"])
	val creativityLabels: Map<String, String>?,
	@field:Schema(allowableValues = ["block", "allow"], types = ["string", "null"])
	val mailImagePolicy: String?,
	@field:Schema(description = "Distinct sender addresses or domains", types = ["array", "null"])
	val mailImageAllowedSenders: List<String>?,
)
