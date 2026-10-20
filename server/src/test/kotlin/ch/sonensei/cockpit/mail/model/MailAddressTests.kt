package ch.sonensei.cockpit.mail.model

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MailAddressTests {
	private val converter = MailAddressListConverter()

	@Test
	fun `null, empty and blank headers give no addresses`() {
		MailAddress.parseList(null).shouldBeEmpty()
		MailAddress.parseList("").shouldBeEmpty()
		MailAddress.parseList("   \t ").shouldBeEmpty()
	}

	@Test
	fun `a list with names, bare addresses and angle-only addresses is parsed in order`() {
		MailAddress.parseList("Ann Example <ann@example.com>, bob@example.org, <carol@example.net>") shouldContainExactly
			listOf(
				MailAddress("ann@example.com", "Ann Example"),
				MailAddress("bob@example.org"),
				MailAddress("carol@example.net"),
			)
	}

	@Test
	fun `quoted names with commas and escaped quotes are unquoted`() {
		MailAddress.parseList("\"Doe, Ann\" <ann@example.com>, \"Bob \\\"the Builder\\\"\" <bob@example.org>") shouldContainExactly
			listOf(
				MailAddress("ann@example.com", "Doe, Ann"),
				MailAddress("bob@example.org", "Bob \"the Builder\""),
			)
	}

	@Test
	fun `RFC 2047 encoded names are decoded`() {
		MailAddress.parseList("=?UTF-8?Q?Zo=C3=AB_M=C3=BCller?= <zoe@example.ch>") shouldContainExactly
			listOf(MailAddress("zoe@example.ch", "Zoë Müller"))
		MailAddress.parseList("=?UTF-8?B?5bGx55Sw?= <yamada@example.jp>") shouldContainExactly
			listOf(MailAddress("yamada@example.jp", "山田"))
	}

	@Test
	fun `groups are flattened to their members and empty groups vanish`() {
		MailAddress.parseList("Team: ann@example.com, Bob <bob@example.org>;, carol@example.net") shouldContainExactly
			listOf(
				MailAddress("ann@example.com"),
				MailAddress("bob@example.org", "Bob"),
				MailAddress("carol@example.net"),
			)
		MailAddress.parseList("undisclosed-recipients:;").shouldBeEmpty()
	}

	@Test
	fun `blank display names count as no name`() {
		MailAddress.parseList("\"  \" <ann@example.com>") shouldContainExactly listOf(MailAddress("ann@example.com"))
	}

	@Test
	fun `malformed headers are parsed leniently without failing, keeping valid neighbours and no blank addresses`() {
		val malformed =
			listOf(
				"\"unterminated <ann@example.com>",
				"(unclosed comment ann@example.com",
				"Ann <ann@example.com",
				"<<>>",
				">",
				"@@@",
				"Ann <>",
			)
		malformed.forEach { header ->
			MailAddress.parseList(header).forEach { it.address.isNotBlank() shouldBe true }
		}
		MailAddress.parseList("Ann <>").shouldBeEmpty()
		MailAddress.parseList("ann@example.com, \"bad") shouldContainExactly listOf(MailAddress("ann@example.com"))
	}

	@Test
	fun `entries without an at sign between local part and domain are dropped`() {
		listOf("<<>>", "<", ">", "@@@", "@example.com", "ann@", "(unclosed comment", "\"bad", "a b c").forEach { header ->
			MailAddress.parseList(header).shouldBeEmpty()
		}
		MailAddress.parseList("Ann <ann@example.com> c <") shouldContainExactly listOf(MailAddress("ann@example.com", "Ann"))
	}

	@Test
	fun `the mailbox shape is checked per group member and allows an at sign in a quoted local part`() {
		MailAddress.parseList("Team: <>, Ann <ann@example.com>, @@@;") shouldContainExactly
			listOf(MailAddress("ann@example.com", "Ann"))
		MailAddress.parseList("\"a@b\"@example.com") shouldContainExactly listOf(MailAddress("\"a@b\"@example.com"))
	}

	@Test
	fun `other malformed entries are kept as the lenient parser read them`() {
		MailAddress.parseList("Ann <ann@example.com") shouldContainExactly listOf(MailAddress("Ann <ann@example.com"))
	}

	@Test
	fun `formatting quotes names only where RFC 5322 needs it`() {
		MailAddress("ann@example.com").toString() shouldBe "ann@example.com"
		MailAddress("ann@example.com", "Ann Example").toString() shouldBe "Ann Example <ann@example.com>"
		MailAddress("ann@example.com", "Doe, Ann").toString() shouldBe "\"Doe, Ann\" <ann@example.com>"
		MailAddress.formatList(emptyList()) shouldBe ""
		MailAddress.formatList(listOf(MailAddress("a@example.com"), MailAddress("b@example.com", "B"))) shouldBe
			"a@example.com, B <b@example.com>"
	}

	@Test
	fun `formatted lists parse back to the same addresses, including special characters and Unicode`() {
		val addresses =
			listOf(
				MailAddress("ann@example.com", "Doe, Ann"),
				MailAddress("bob@example.org", "Bob \"the Builder\" <Boss>"),
				MailAddress("zoe@example.ch", "Zoë Müller"),
				MailAddress("yamada@example.jp", "山田 太郎"),
				MailAddress("smile@example.com", "Smile 😀; (team)"),
				MailAddress("plain@example.com"),
			)

		MailAddress.parseList(MailAddress.formatList(addresses)) shouldContainExactly addresses
	}

	@Test
	fun `the converter stores empty lists as null and reads null back as an empty list`() {
		converter.convertToDatabaseColumn(null).shouldBeNull()
		converter.convertToDatabaseColumn(emptyList()).shouldBeNull()
		converter.convertToEntityAttribute(null).shouldBeEmpty()
		converter.convertToEntityAttribute("").shouldBeEmpty()
	}

	@Test
	fun `the converter round-trips an address list through its column text`() {
		val addresses = listOf(MailAddress("ann@example.com", "Doe, Ann"), MailAddress("team@example.com"))

		val column = converter.convertToDatabaseColumn(addresses)

		column shouldBe "\"Doe, Ann\" <ann@example.com>, team@example.com"
		converter.convertToEntityAttribute(column) shouldContainExactly addresses
	}
}
