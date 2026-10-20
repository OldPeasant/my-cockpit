package ch.sonensei.cockpit.core.settings

import ch.sonensei.cockpit.core.settings.domain.CoreSettings
import ch.sonensei.cockpit.core.settings.domain.SettingDefinition
import ch.sonensei.cockpit.core.settings.domain.SettingType
import ch.sonensei.cockpit.core.settings.domain.SettingsChangedEvent
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.settings.infra.UserSettingsRepository
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import tools.jackson.databind.json.JsonMapper
import java.time.ZoneId
import java.util.stream.Stream

@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class SettingsServiceTests(
	@Autowired private val settings: SettingsService,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	fun noSettingsChanged() {
		jdbc.update("delete from user_settings")
	}

	@Test
	fun `unset settings read as their defaults`() {
		settings.get(CoreSettings.TIME_ZONE) shouldBe "Europe/Zurich"
		settings.timeZone() shouldBe ZoneId.of("Europe/Zurich")
		settings.get(CoreSettings.ENERGY_LABELS) shouldBe mapOf("LOW" to "Low", "MEDIUM" to "Medium", "HIGH" to "High")
		settings.current().version shouldBe 0
		settings
			.current()
			.values.keys
			.toList() shouldContainExactly CoreSettings.ALL.map { it.key }.sorted()
	}

	@Test
	fun `changes are stored, null resets to the default, and the version counts changes`() {
		val versions = mutableListOf<Long>()
		settings.update(mapOf("timeZone" to "Asia/Tokyo", "weekStart" to "SUNDAY")) { versions += it }.version shouldBe 1
		settings.timeZone() shouldBe ZoneId.of("Asia/Tokyo")
		settings.get(CoreSettings.WEEK_START) shouldBe "SUNDAY"

		settings.update(mapOf("timeZone" to null)) { versions += it }.version shouldBe 2
		settings.get(CoreSettings.TIME_ZONE) shouldBe "Europe/Zurich"
		settings.get(CoreSettings.WEEK_START) shouldBe "SUNDAY"
		versions shouldContainExactly listOf(0L, 1L)
	}

	@Test
	fun `a change publishes the changed keys only`(events: ApplicationEvents) {
		settings.update(mapOf("theme" to "dark", "weekStart" to "MONDAY"))
		events.stream(SettingsChangedEvent::class.java).toList() shouldContainExactly listOf(SettingsChangedEvent(setOf("theme")))
		settings.update(mapOf("theme" to "dark"))
		events.stream(SettingsChangedEvent::class.java).count() shouldBe 1
	}

	@Test
	fun `invalid changes are rejected as a whole`() {
		val ex =
			shouldThrow<InvalidRequestException> {
				settings.update(mapOf("theme" to "dark", "creativityLabels" to mapOf("ROUTINE" to "x"), "nope" to true))
			}
		ex.fieldErrors.map { it.field } shouldContainExactly listOf("creativityLabels", "nope")
		settings.get(CoreSettings.THEME) shouldBe "system"
	}

	@Test
	fun `a stored value its definition no longer accepts reads as the default`() {
		settings.update(mapOf("theme" to "dark"))
		jdbc.update("update user_settings set overrides = '{\"theme\": \"sepia\", \"removedKey\": 1}'::jsonb")
		settings.get(CoreSettings.THEME) shouldBe "system"
		settings
			.current()
			.values.keys
			.toList() shouldContainExactly CoreSettings.ALL.map { it.key }.sorted()
	}

	@Test
	fun `modules register their own settings, as beans or at runtime`() {
		val repository = mockk<UserSettingsRepository> { every { findFirstBy() } returns null }
		val bean = SettingDefinition("calendarDefaultView", SettingType.OneOf("day", "week"), "week", "Default calendar view")
		val beans = mockk<ObjectProvider<SettingDefinition<*>>> { every { orderedStream() } returns Stream.of(bean) }
		val service = SettingsService(repository, JsonMapper(), mockk<ApplicationEventPublisher>(), beans)
		val runtime = SettingDefinition("journalPrompt", SettingType.Text(), "How was your day?", "Journal prompt")
		service.register(runtime)
		service.register(runtime)

		service.get(bean) shouldBe "week"
		service.get(runtime) shouldBe "How was your day?"
		service.current().values shouldContainKey "calendarDefaultView"
		shouldThrow<IllegalArgumentException> {
			service.register(SettingDefinition("theme", SettingType.Text(), "x", "Duplicate"))
		}
		shouldThrow<IllegalArgumentException> {
			service.get(SettingDefinition("unregistered", SettingType.Flag, false, "Not registered"))
		}
	}
}
