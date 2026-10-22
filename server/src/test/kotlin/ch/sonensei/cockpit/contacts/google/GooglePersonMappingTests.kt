package ch.sonensei.cockpit.contacts.google

import ch.sonensei.cockpit.contacts.domain.Change
import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactAddress
import ch.sonensei.cockpit.contacts.domain.ContactChanges
import ch.sonensei.cockpit.contacts.domain.ContactEmail
import ch.sonensei.cockpit.contacts.domain.ContactPhone
import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.contacts.domain.ContactSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/**
 * [GooglePersonMapping] without Spring: which entries of a People API `Person` are read (primary or first), how
 * values Google accepts but a contact does not are cut or dropped, and the `Person` body written for a contact.
 * All names, addresses and numbers are fictitious.
 */
class GooglePersonMappingTests {
	private val json: JsonMapper = JsonMapper.builder().build()

	private fun person(body: String): JsonNode = json.readTree(body)

	private fun changes(body: String): ContactChanges = GooglePersonMapping.toChanges(person(body))

	private fun contact(
		displayName: String,
		configure: Contact.() -> Unit = {},
	): Contact = Contact(displayName = displayName, source = ContactSource.GOOGLE).apply(configure)

	// --- toChanges: names, organizations, biographies

	@Test
	fun `the entry Google marks primary is read, not the first one`() {
		val changes =
			changes(
				"""
				{
				  "names": [
				    {"metadata": {}, "displayName": "Old Name", "givenName": "Old", "familyName": "Name"},
				    {"metadata": {"primary": true}, "displayName": "Robin Fiktiv", "givenName": "Robin", "familyName": "Fiktiv"}
				  ],
				  "organizations": [
				    {"name": "Former AG", "title": "Intern"},
				    {"metadata": {"primary": true}, "name": "Beispiel GmbH", "title": "Lead"}
				  ],
				  "biographies": [
				    {"value": "old notes"},
				    {"metadata": {"primary": true}, "value": "current notes"}
				  ]
				}
				""",
			)

		changes.displayName shouldBe Change("Robin Fiktiv")
		changes.givenName shouldBe Change("Robin")
		changes.familyName shouldBe Change("Fiktiv")
		changes.organization shouldBe Change("Beispiel GmbH")
		changes.title shouldBe Change("Lead")
		changes.notesMd shouldBe Change("current notes")
	}

	@Test
	fun `without a primary entry the first one is read`() {
		val changes =
			changes(
				"""
				{
				  "names": [{"givenName": "Kim", "familyName": "Erste"}, {"givenName": "Kim", "familyName": "Zweite"}],
				  "organizations": [{"name": "First AG"}, {"name": "Second AG"}]
				}
				""",
			)

		changes.familyName shouldBe Change("Erste")
		changes.organization shouldBe Change("First AG")
	}

	@Test
	fun `a name without displayName uses unstructuredName`() {
		changes("""{"names": [{"unstructuredName": "  Dr. Pat Beispiel  "}]}""").displayName shouldBe Change("Dr. Pat Beispiel")
	}

	@Test
	fun `a name with only given and family name leaves the display name to be derived`() {
		val changes = changes("""{"names": [{"givenName": "Lou", "familyName": "Muster"}]}""")

		changes.displayName shouldBe Change(null)
		changes.givenName shouldBe Change("Lou")
	}

	@Test
	fun `fields Google does not have are cleared`() {
		val changes = changes("""{"resourceName": "people/c1", "names": [{"displayName": "Only Name"}]}""")

		changes.givenName shouldBe Change(null)
		changes.familyName shouldBe Change(null)
		changes.organization shouldBe Change(null)
		changes.title shouldBe Change(null)
		changes.birthday shouldBe Change(null)
		changes.notesMd shouldBe Change(null)
		changes.emails shouldBe Change(emptyList())
		changes.phones shouldBe Change(emptyList())
		changes.addresses shouldBe Change(emptyList())
	}

	// --- toChanges: display name fallback

	@Test
	fun `a person with only phone numbers is named by the first one`() {
		changes("""{"phoneNumbers": [{"value": "+41 79 000 00 11"}, {"value": "+41 79 000 00 12"}]}""").displayName shouldBe
			Change("+41 79 000 00 11")
	}

	@Test
	fun `a person without any usable field is an Unnamed contact`() {
		changes("""{"resourceName": "people/c9"}""").displayName shouldBe Change(GooglePersonMapping.UNNAMED)
		// Only blank values and an invalid address are no usable fields either.
		changes(
			"""{"names": [{"displayName": "   ", "givenName": " "}], "emailAddresses": [{"value": "nope"}], "phoneNumbers": [{"value": "  "}]}""",
		).displayName shouldBe Change(GooglePersonMapping.UNNAMED)
	}

	@Test
	fun `a person with only a job title is an Unnamed contact that keeps the title`() {
		val changes = changes("""{"organizations": [{"title": "Fictional Advisor"}]}""")

		changes.displayName shouldBe Change(GooglePersonMapping.UNNAMED)
		changes.organization shouldBe Change(null)
		changes.title shouldBe Change("Fictional Advisor")
	}

	@Test
	fun `an organization or an email address is enough for the contact to derive its name`() {
		changes("""{"organizations": [{"name": "Muster AG"}], "phoneNumbers": [{"value": "+41 44 000 00 99"}]}""").displayName shouldBe
			Change(null)
		changes("""{"emailAddresses": [{"value": "info@example.test"}], "phoneNumbers": [{"value": "+41 44 000 00 99"}]}""").displayName shouldBe
			Change(null)
	}

	// --- toChanges: emails, phones, addresses

	@Test
	fun `emails, phones and addresses keep Google's type as label, invalid and empty entries are dropped`() {
		val changes =
			changes(
				"""
				{
				  "emailAddresses": [
				    {"value": "jo@example.test", "type": "home"},
				    {"value": "jo at example"},
				    {"value": "two@@example.test"},
				    {"value": "with space@example.test"},
				    {"type": "work"},
				    {"value": "jo.work@example.test", "type": "  "}
				  ],
				  "phoneNumbers": [
				    {"value": " +41 31 000 00 01 ", "type": "Kantine"},
				    {"value": ""},
				    {"type": "mobile"}
				  ],
				  "addresses": [
				    {"type": "work", "streetAddress": " Musterstrasse 5 ", "city": "Bern", "postalCode": "3000", "region": "BE", "country": "CH"},
				    {"type": "home"},
				    {"type": "other", "formattedValue": "only formatted", "streetAddress": "  "}
				  ]
				}
				""",
			)

		changes.emails shouldBe Change(listOf(ContactEmail("jo@example.test", "home"), ContactEmail("jo.work@example.test", null)))
		changes.phones shouldBe Change(listOf(ContactPhone("+41 31 000 00 01", "Kantine")))
		changes.addresses shouldBe
			Change(
				listOf(
					ContactAddress(label = "work", street = "Musterstrasse 5", city = "Bern", region = "BE", postalCode = "3000", country = "CH"),
				),
			)
	}

	@Test
	fun `values longer than the contact limit are cut, an over-long email address is dropped`() {
		val long = "x".repeat(ContactService.MAX_FIELD_LENGTH + 20)
		val longEmail = "a".repeat(ContactService.MAX_FIELD_LENGTH) + "@example.test"
		val changes =
			changes(
				"""
				{
				  "names": [{"displayName": "$long", "givenName": "$long", "familyName": "$long"}],
				  "organizations": [{"name": "$long", "title": "$long"}],
				  "emailAddresses": [{"value": "$longEmail"}, {"value": "ok@example.test", "type": "$long"}],
				  "phoneNumbers": [{"value": "$long", "type": "$long"}],
				  "addresses": [{"streetAddress": "$long", "city": "$long", "type": "$long"}]
				}
				""",
			)
		val cut = "x".repeat(ContactService.MAX_FIELD_LENGTH)

		changes.displayName shouldBe Change(cut)
		changes.givenName shouldBe Change(cut)
		changes.familyName shouldBe Change(cut)
		changes.organization shouldBe Change(cut)
		changes.title shouldBe Change(cut)
		changes.emails shouldBe Change(listOf(ContactEmail("ok@example.test", cut)))
		changes.phones shouldBe Change(listOf(ContactPhone(cut, cut)))
		changes.addresses shouldBe Change(listOf(ContactAddress(label = cut, street = cut, city = cut)))
	}

	@Test
	fun `an email address of exactly the field limit is kept`() {
		val address = "b".repeat(ContactService.MAX_FIELD_LENGTH - "@example.test".length) + "@example.test"

		changes("""{"emailAddresses": [{"value": "$address"}]}""").emails shouldBe Change(listOf(ContactEmail(address)))
	}

	@Test
	fun `notes are cut to the notes limit, not the field limit`() {
		val notes = "n".repeat(ContactService.MAX_NOTES_LENGTH + 5)

		val cut = changes("""{"biographies": [{"value": "$notes"}]}""").notesMd!!.value!!

		cut.length shouldBe ContactService.MAX_NOTES_LENGTH
		changes("""{"biographies": [{"value": "${"m".repeat(ContactService.MAX_FIELD_LENGTH + 1)}"}]}""").notesMd!!.value!!.length shouldBe
			ContactService.MAX_FIELD_LENGTH + 1
	}

	@Test
	fun `at most MAX_ENTRIES entries are kept, emails first, then phones, then addresses`() {
		val emails = (1..60).joinToString(",") { """{"value": "p$it@example.test"}""" }
		val phones = (1..60).joinToString(",") { """{"value": "+41 44 000 ${it.toString().padStart(4, '0')}"}""" }
		val addresses = (1..10).joinToString(",") { """{"city": "Stadt $it"}""" }

		val changes = changes("""{"emailAddresses": [$emails], "phoneNumbers": [$phones], "addresses": [$addresses]}""")

		changes.emails!!.value.size shouldBe 60
		changes.phones!!.value.size shouldBe ContactService.MAX_ENTRIES - 60
		changes.phones
			.value
			.last()
			.number shouldBe "+41 44 000 0040"
		changes.addresses!!.value.shouldBeEmpty()
	}

	@Test
	fun `exactly MAX_ENTRIES entries are all kept`() {
		val phones = (1..ContactService.MAX_ENTRIES - 1).joinToString(",") { """{"value": "+41 44 111 $it"}""" }

		val changes = changes("""{"phoneNumbers": [$phones], "addresses": [{"city": "Letzte Stadt"}, {"city": "Zu viel"}]}""")

		changes.phones!!.value.size shouldBe ContactService.MAX_ENTRIES - 1
		changes.addresses shouldBe Change(listOf(ContactAddress(city = "Letzte Stadt")))
	}

	@Test
	fun `values that are not strings are ignored`() {
		val changes =
			changes("""{"names": [{"givenName": 42, "familyName": {"x": 1}, "displayName": true}], "phoneNumbers": [{"value": 4144}]}""")

		changes.givenName shouldBe Change(null)
		changes.familyName shouldBe Change(null)
		changes.phones shouldBe Change(emptyList())
		changes.displayName shouldBe Change(GooglePersonMapping.UNNAMED)
	}

	// --- toChanges: birthdays

	@Test
	fun `a birthday needs a year, a month and a day that form a real date`() {
		fun birthday(date: String) = changes("""{"birthdays": [{"date": $date}]}""").birthday

		birthday("""{"year": 1985, "month": 2, "day": 28}""") shouldBe Change(LocalDate.of(1985, 2, 28))
		birthday("""{"year": 1984, "month": 2, "day": 29}""") shouldBe Change(LocalDate.of(1984, 2, 29))
		birthday("""{"month": 7, "day": 3}""") shouldBe Change(null)
		birthday("""{"year": 0, "month": 7, "day": 3}""") shouldBe Change(null)
		birthday("""{"year": 1990, "day": 3}""") shouldBe Change(null)
		birthday("""{"year": 1990, "month": 7}""") shouldBe Change(null)
		birthday("""{"year": 1985, "month": 2, "day": 30}""") shouldBe Change(null)
		birthday("""{"year": 1985, "month": 13, "day": 1}""") shouldBe Change(null)
		changes("""{"birthdays": [{"text": "1st of May"}]}""").birthday shouldBe Change(null)
	}

	@Test
	fun `the first birthday with a full date is taken`() {
		val changes =
			changes(
				"""
				{"birthdays": [
				  {"metadata": {"primary": true}, "date": {"month": 5, "day": 1}},
				  {"text": "01.05.1970"},
				  {"date": {"year": 1970, "month": 5, "day": 1}},
				  {"date": {"year": 1971, "month": 6, "day": 2}}
				]}
				""",
			)

		changes.birthday shouldBe Change(LocalDate.of(1970, 5, 1))
	}

	// --- resource name, etag, deleted

	@Test
	fun `resource name, etag and the deleted flag are read from the person`() {
		val deleted = person("""{"resourceName": "people/c77", "etag": "%abc", "metadata": {"deleted": true}}""")
		val listed = person("""{"resourceName": "people/c78", "metadata": {"sources": []}}""")

		GooglePersonMapping.resourceName(deleted) shouldBe "people/c77"
		GooglePersonMapping.etag(deleted) shouldBe "%abc"
		GooglePersonMapping.isDeleted(deleted) shouldBe true
		GooglePersonMapping.isDeleted(listed) shouldBe false
		GooglePersonMapping.etag(listed).shouldBeNull()
		GooglePersonMapping.resourceName(person("{}")).shouldBeNull()
	}

	// --- toPerson

	@Test
	fun `a contact without given and family name is sent with its display name as unstructuredName`() {
		val person = GooglePersonMapping.toPerson(json, contact("Hausverwaltung Fiktiv"), etag = null)

		val name = person.get("names").only()
		name.get("unstructuredName").asString() shouldBe "Hausverwaltung Fiktiv"
		name.has("givenName") shouldBe false
		name.has("familyName") shouldBe false
		person.has("etag") shouldBe false
	}

	@Test
	fun `a contact with only a family name sends the family name and no unstructuredName`() {
		val person = GooglePersonMapping.toPerson(json, contact("Muster") { familyName = "Muster" }, etag = "%e1")

		val name = person.get("names").only()
		name.get("familyName").asString() shouldBe "Muster"
		name.has("givenName") shouldBe false
		name.has("unstructuredName") shouldBe false
		person.get("etag").asString() shouldBe "%e1"
	}

	@Test
	fun `empty fields are sent as empty lists so that an update clears them at Google`() {
		val person = GooglePersonMapping.toPerson(json, contact("Leer"), etag = "%e2")

		for (field in listOf("emailAddresses", "phoneNumbers", "addresses", "organizations", "birthdays", "biographies")) {
			person.get(field).isArray shouldBe true
			person.get(field).size() shouldBe 0
		}
	}

	@Test
	fun `a title without an organization is still sent`() {
		val person = GooglePersonMapping.toPerson(json, contact("Pat") { title = "Fictional Advisor" }, etag = null)

		val organization = person.get("organizations").only()
		organization.get("title").asString() shouldBe "Fictional Advisor"
		organization.has("name") shouldBe false
	}

	@Test
	fun `labels and address parts are only sent when set`() {
		val person =
			GooglePersonMapping.toPerson(
				json,
				contact("Jo") {
					givenName = "Jo"
					emails = listOf(ContactEmail("jo@example.test"), ContactEmail("jo.work@example.test", "work"))
					phones = listOf(ContactPhone("+41 44 000 00 31"))
					addresses = listOf(ContactAddress(city = "Basel"))
				},
				etag = null,
			)

		person.get("emailAddresses").get(0).has("type") shouldBe false
		person
			.get("emailAddresses")
			.get(1)
			.get("type")
			.asString() shouldBe "work"
		person.get("phoneNumbers").get(0).has("type") shouldBe false
		val address = person.get("addresses").only()
		address.propertyNames().asSequence().toList() shouldContainExactly listOf("city")
	}

	@Test
	fun `every field of a contact is read back unchanged`() {
		val original =
			contact("Alex Beispiel") {
				givenName = "Alex"
				familyName = "Beispiel"
				emails = listOf(ContactEmail("alex@example.test", "work"), ContactEmail("alex.home@example.test"))
				phones = listOf(ContactPhone("+41 44 000 00 41", "mobile"))
				addresses =
					listOf(ContactAddress(label = "home", street = "Teststrasse 9", city = "Luzern", region = "LU", postalCode = "6000", country = "CH"))
				organization = "Fiktiv AG"
				title = "Planner"
				birthday = LocalDate.of(1992, 11, 30)
				notesMd = "Prefers email."
			}
		val person = GooglePersonMapping.toPerson(json, original, etag = "%e3")
		person
			.get("biographies")
			.only()
			.get("contentType")
			.asString() shouldBe "TEXT_PLAIN"
		val date = person.get("birthdays").only().get("date")
		listOf(date.get("year").asInt(), date.get("month").asInt(), date.get("day").asInt()) shouldContainExactly listOf(1992, 11, 30)

		val back = GooglePersonMapping.toChanges(person)

		back.givenName shouldBe Change("Alex")
		back.familyName shouldBe Change("Beispiel")
		back.emails shouldBe Change(original.emails)
		back.phones shouldBe Change(original.phones)
		back.addresses shouldBe Change(original.addresses)
		back.organization shouldBe Change("Fiktiv AG")
		back.title shouldBe Change("Planner")
		back.birthday shouldBe Change(LocalDate.of(1992, 11, 30))
		back.notesMd shouldBe Change("Prefers email.")
	}

	@Test
	fun `a contact named after its phone number or without any field is created without a name`() {
		val phoneOnly =
			GooglePersonMapping.toPerson(
				json,
				contact("+41 44 000 00 51") { phones = listOf(ContactPhone("+41 44 000 00 51")) },
				null,
			)
		val unnamed = GooglePersonMapping.toPerson(json, contact(GooglePersonMapping.UNNAMED), null)
		val company = GooglePersonMapping.toPerson(json, contact("Fiktiv AG") { organization = "Fiktiv AG" }, null)

		phoneOnly.get("names").size() shouldBe 0
		unnamed.get("names").size() shouldBe 0
		company.get("names").size() shouldBe 0
	}

	// --- toUpdate

	/** Google's person with parts the cockpit does not model; [contactOf] gives the contact as the pull stores it. */
	private val rich =
		"""
		{"resourceName": "people/c61", "etag": "%rich",
		 "names": [{"metadata": {"primary": true}, "displayName": "Dr. Kim Lee Beispiel", "givenName": "Kim", "middleName": "Lee",
		            "familyName": "Beispiel", "honorificPrefix": "Dr.", "unstructuredName": "Dr. Kim Lee Beispiel"}],
		 "emailAddresses": [{"value": "kim@example.test", "type": "work", "displayName": "Kim at work"}, {"value": "not-an-address"}],
		 "phoneNumbers": [{"value": "+41 44 000 00 61", "canonicalForm": "+41440000061", "type": "mobile"}],
		 "addresses": [{"streetAddress": "Probeweg 2", "extendedAddress": "c/o Fiktiv", "poBox": "PF 12", "city": "Bern",
		                "formattedValue": "Probeweg 2, Bern", "country": "Schweiz", "countryCode": "CH"},
		               {"formattedValue": "Somewhere only Google shows"}],
		 "organizations": [{"name": "Fiktiv AG", "title": "Planner", "department": "Research"}],
		 "birthdays": [{"date": {"month": 2, "day": 29}}, {"text": "29. Feb"}],
		 "biographies": [{"value": "Old note.", "contentType": "TEXT_PLAIN"}]}
		"""

	private fun contactOf(person: String): Contact {
		val changes = changes(person)
		// Without a display name from Google, ContactService derives it: given and family name, organization, email.
		val displayName =
			changes.displayName?.value
				?: listOfNotNull(changes.givenName?.value, changes.familyName?.value).joinToString(" ").ifEmpty { null }
				?: changes.organization?.value
				?: changes.emails!!
					.value
					.first()
					.address
		return contact(displayName) {
			givenName = changes.givenName?.value
			familyName = changes.familyName?.value
			emails = changes.emails!!.value
			phones = changes.phones!!.value
			addresses = changes.addresses!!.value
			organization = changes.organization?.value
			title = changes.title?.value
			birthday = changes.birthday?.value
			notesMd = changes.notesMd?.value
		}
	}

	private fun update(
		contact: Contact,
		current: String = rich,
	) = GooglePersonMapping.toUpdate(json, contact, person(current))

	@Test
	fun `an unchanged contact sends no field`() {
		val update = update(contactOf(rich))

		update.fields.shouldBeEmpty()
	}

	@Test
	fun `a changed family name changes only that part of Google's name`() {
		val update = update(contactOf(rich).apply { familyName = "Muster" })

		update.fields shouldContainExactly listOf("names")
		update.person.get("etag").asString() shouldBe "%rich"
		val name = update.person.get("names").only()
		name.get("familyName").asString() shouldBe "Muster"
		name.get("middleName").asString() shouldBe "Lee"
		name.get("honorificPrefix").asString() shouldBe "Dr."
		name.has("unstructuredName") shouldBe false
		name.has("displayName") shouldBe false
	}

	@Test
	fun `a changed note sends only the biography, so the year-less birthday stays at Google`() {
		val update = update(contactOf(rich).apply { notesMd = "New note." })

		update.fields shouldContainExactly listOf("biographies")
		update.person.has("birthdays") shouldBe false
		update.person
			.get("biographies")
			.only()
			.get("value")
			.asString() shouldBe "New note."
	}

	@Test
	fun `a changed address keeps the parts the cockpit does not show, and the address it cannot read`() {
		val update = update(contactOf(rich).apply { addresses = listOf(addresses.single().copy(city = "Thun")) })

		update.fields shouldContainExactly listOf("addresses")
		val addresses = update.person.get("addresses")
		addresses.size() shouldBe 2
		val changed = addresses.get(0)
		changed.get("city").asString() shouldBe "Thun"
		changed.get("extendedAddress").asString() shouldBe "c/o Fiktiv"
		changed.get("poBox").asString() shouldBe "PF 12"
		changed.get("countryCode").asString() shouldBe "CH"
		changed.has("formattedValue") shouldBe false
		addresses.get(1).get("formattedValue").asString() shouldBe "Somewhere only Google shows"
	}

	@Test
	fun `changed emails keep the entry details Google has and the entries the cockpit cannot read`() {
		val update =
			update(contactOf(rich).apply { emails = listOf(emails.single().copy(label = "home"), ContactEmail("kim.new@example.test")) })

		update.fields shouldContainExactly listOf("emailAddresses")
		val emails = update.person.get("emailAddresses")
		emails.size() shouldBe 3
		emails.get(0).get("type").asString() shouldBe "home"
		emails.get(0).get("displayName").asString() shouldBe "Kim at work"
		emails.get(1).get("value").asString() shouldBe "kim.new@example.test"
		emails.get(1).has("type") shouldBe false
		emails.get(2).get("value").asString() shouldBe "not-an-address"
	}

	@Test
	fun `a changed phone number drops Google's canonical form of the old one`() {
		val update = update(contactOf(rich).apply { phones = listOf(ContactPhone("+41 44 000 00 62", "mobile")) })

		update.fields shouldContainExactly listOf("phoneNumbers")
		val phone = update.person.get("phoneNumbers").only()
		phone.get("value").asString() shouldBe "+41 44 000 00 62"
		phone.has("canonicalForm") shouldBe false
		phone.get("type").asString() shouldBe "mobile"
	}

	@Test
	fun `a changed title keeps the department, a cleared organization drops the entry`() {
		val retitled = update(contactOf(rich).apply { title = "Lead" })
		val cleared =
			update(
				contactOf(rich).apply {
					organization = null
					title = null
				},
			)

		retitled.fields shouldContainExactly listOf("organizations")
		val organization = retitled.person.get("organizations").only()
		organization.get("title").asString() shouldBe "Lead"
		organization.get("department").asString() shouldBe "Research"
		cleared.person.get("organizations").size() shouldBe 0
	}

	@Test
	fun `a full birthday replaces the year-less one and keeps the text`() {
		val update = update(contactOf(rich).apply { birthday = LocalDate.of(1988, 2, 29) })

		update.fields shouldContainExactly listOf("birthdays")
		val birthdays = update.person.get("birthdays")
		birthdays.size() shouldBe 2
		val date = birthdays.get(0).get("date")
		listOf(date.get("year").asInt(), date.get("month").asInt(), date.get("day").asInt()) shouldContainExactly listOf(1988, 2, 29)
		birthdays.get(1).get("text").asString() shouldBe "29. Feb"
	}

	@Test
	fun `a cleared birthday drops only the full date`() {
		val current = """{"etag": "%b", "birthdays": [{"date": {"year": 1990, "month": 1, "day": 2}}, {"date": {"month": 3, "day": 4}}]}"""

		val update = update(contactOf(current).apply { birthday = null }, current)

		update.fields shouldContainExactly listOf("birthdays")
		val birthdays = update.person.get("birthdays")
		birthdays.size() shouldBe 1
		birthdays
			.get(0)
			.get("date")
			.has("year") shouldBe false
	}

	@Test
	fun `a derived display name is not sent as a name, a name the user gave is`() {
		val current = """{"etag": "%p", "phoneNumbers": [{"value": "+41 44 000 00 71"}]}"""
		val renumbered = contactOf(current).apply { phones = listOf(ContactPhone("+41 44 000 00 72")) }
		val named = contactOf(current).apply { displayName = "Hauswart" }

		update(renumbered, current).fields shouldContainExactly listOf("phoneNumbers")
		val update = update(named, current)
		update.fields shouldContainExactly listOf("names")
		update.person
			.get("names")
			.only()
			.get("unstructuredName")
			.asString() shouldBe "Hauswart"
	}

	@Test
	fun `a contact whose organization names it sends no name when the organization changes`() {
		val current = """{"etag": "%o", "organizations": [{"name": "Fictional GmbH"}]}"""
		val contact = contactOf(current)
		contact.displayName shouldBe "Fictional GmbH"

		update(contact.apply { organization = "Other GmbH" }, current).fields shouldContainExactly listOf("organizations")
	}

	private fun JsonNode.only(): JsonNode {
		size() shouldBe 1
		return get(0)
	}
}
