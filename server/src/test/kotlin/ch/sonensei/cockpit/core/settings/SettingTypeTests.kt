package ch.sonensei.cockpit.core.settings

import ch.sonensei.cockpit.core.settings.domain.CoreSettings
import ch.sonensei.cockpit.core.settings.domain.SettingDefinition
import ch.sonensei.cockpit.core.settings.domain.SettingType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigInteger

class SettingTypeTests {
	@Test
	fun `text has a maximum length`() {
		SettingType.Text(3).parse("abc") shouldBe "abc"
		SettingType.Text(3).parse("abcd").shouldBeNull()
		SettingType.Text(3).parse(1).shouldBeNull()
	}

	@Test
	fun `flag accepts booleans only`() {
		SettingType.Flag.parse(true) shouldBe true
		SettingType.Flag.parse("true").shouldBeNull()
	}

	@Test
	fun `whole numbers are integral and in range`() {
		val type = SettingType.WholeNumber(1..10)
		type.parse(10) shouldBe 10
		type.parse(3L) shouldBe 3
		type.parse(11).shouldBeNull()
		type.parse(2.5).shouldBeNull()
		type.parse(BigInteger.TWO.pow(70)).shouldBeNull()
		type.parse("5").shouldBeNull()
	}

	@Test
	fun `one of accepts the listed values only, case-sensitively`() {
		val type = SettingType.OneOf("light", "dark")
		type.parse("dark") shouldBe "dark"
		type.parse("Dark").shouldBeNull()
	}

	@Test
	fun `time zones are IANA ids`() {
		SettingType.TimeZone.parse("Europe/Zurich") shouldBe "Europe/Zurich"
		SettingType.TimeZone.parse("Europe/Atlantis").shouldBeNull()
		SettingType.TimeZone.parse("+02:00").shouldBeNull()
	}

	@Test
	fun `labels need every key with a non-blank trimmed label`() {
		val type = SettingType.Labels(listOf("A", "B"), maxLength = 5)
		type.parse(mapOf("A" to " x ", "B" to "y")) shouldBe mapOf("A" to "x", "B" to "y")
		type.parse(mapOf("A" to "x")).shouldBeNull()
		type.parse(mapOf("A" to "x", "B" to "y", "C" to "z")).shouldBeNull()
		type.parse(mapOf("A" to " ", "B" to "y")).shouldBeNull()
		type.parse(mapOf("A" to "toolong", "B" to "y")).shouldBeNull()
		type.parse(mapOf("A" to 1, "B" to "y")).shouldBeNull()
		type.parse(listOf("x", "y")).shouldBeNull()
	}

	@Test
	fun `text lists hold distinct non-blank strings`() {
		val type = SettingType.TextList(maxItems = 2, maxLength = 5)
		type.parse(listOf(" a ", "b")) shouldBe listOf("a", "b")
		type.parse(emptyList<String>()) shouldBe emptyList()
		type.parse(listOf("a", "b", "c")).shouldBeNull()
		type.parse(listOf("a", "a")).shouldBeNull()
		type.parse(listOf("")).shouldBeNull()
		type.parse(listOf("toolong")).shouldBeNull()
		type.parse(listOf(1)).shouldBeNull()
		type.parse("a").shouldBeNull()
	}

	@Test
	fun `definitions need a lowerCamelCase key and a valid default`() {
		CoreSettings.ALL.forEach { it.type.parse(it.default) shouldBe it.default }
		shouldThrow<IllegalArgumentException> { SettingDefinition("Theme", SettingType.Flag, true, "Bad key") }
		shouldThrow<IllegalArgumentException> { SettingDefinition("mail.policy", SettingType.Flag, true, "Bad key") }
		shouldThrow<IllegalArgumentException> { SettingDefinition("theme", SettingType.OneOf("a"), "b", "Bad default") }
	}
}
