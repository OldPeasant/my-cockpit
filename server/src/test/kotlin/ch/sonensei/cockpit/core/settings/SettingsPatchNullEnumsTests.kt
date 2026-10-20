package ch.sonensei.cockpit.core.settings

import ch.sonensei.cockpit.core.settings.api.SettingsPatchNullEnums
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.JsonSchema
import io.swagger.v3.oas.models.media.Schema
import org.junit.jupiter.api.Test

/** The customizer that documents `null` (reset) for the enum settings of SettingsPatch. */
class SettingsPatchNullEnumsTests {
	private fun openApi(vararg schemas: Pair<String, Schema<*>>): OpenAPI = OpenAPI().components(Components().schemas(linkedMapOf(*schemas)))

	private fun patchSchema(): Schema<*> =
		JsonSchema().apply {
			addProperty(
				"theme",
				JsonSchema().apply {
					types = setOf("string", "null")
					description = "Color theme"
					enum = mutableListOf<Any?>("light", "dark", "system")
				},
			)
			addProperty(
				"timeZone",
				JsonSchema().apply {
					types = setOf("string", "null")
					description = "IANA time zone"
				},
			)
		}

	@Test
	fun `adds null once to every enum and keeps type and description`() {
		val api = openApi("SettingsPatch" to patchSchema())
		SettingsPatchNullEnums().customise(api)
		SettingsPatchNullEnums().customise(api)
		val theme =
			api.components.schemas
				.getValue("SettingsPatch")
				.properties
				.getValue("theme")
		theme.enum.shouldContainExactly("light", "dark", "system", null)
		theme.types shouldBe setOf("string", "null")
		theme.description shouldBe "Color theme"
		val timeZone =
			api.components.schemas
				.getValue("SettingsPatch")
				.properties
				.getValue("timeZone")
		timeZone.enum.shouldBeNull()
		timeZone.description shouldBe "IANA time zone"
	}

	@Test
	fun `leaves other schemas alone and tolerates a document without the patch schema`() {
		val settings = patchSchema()
		val api = openApi("Settings" to settings)
		SettingsPatchNullEnums().customise(api)
		settings.properties
			.getValue("theme")
			.enum
			.shouldContainExactly("light", "dark", "system")
		SettingsPatchNullEnums().customise(OpenAPI())
	}
}
