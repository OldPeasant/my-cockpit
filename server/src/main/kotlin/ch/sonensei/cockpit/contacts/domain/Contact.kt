package ch.sonensei.cockpit.contacts.domain

import ch.sonensei.cockpit.core.persistence.SoftDeletableEntity
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.time.LocalDate
import java.util.UUID

/**
 * A contact (SPEC.md §8.3). [displayName] is what lists show; [photoFileId] is a file of the drive. [source] tells where
 * the contact lives: LOCAL ones are edited here, the others are kept in sync with [externalId] at their provider.
 * The lists are replaced as a whole on change (they are immutable), so dirty checking sees the change.
 */
@Entity
@Table(name = "contact")
class Contact(
	@Column(name = "display_name", nullable = false)
	var displayName: String,
	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, updatable = false)
	val source: ContactSource = ContactSource.LOCAL,
	id: UUID? = null,
) : SoftDeletableEntity(id) {
	@Column(name = "given_name")
	var givenName: String? = null

	@Column(name = "family_name")
	var familyName: String? = null

	@Convert(converter = EmailsConverter::class)
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "emails", nullable = false)
	var emails: List<ContactEmail> = emptyList()

	@Convert(converter = PhonesConverter::class)
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "phones", nullable = false)
	var phones: List<ContactPhone> = emptyList()

	@Convert(converter = AddressesConverter::class)
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "addresses", nullable = false)
	var addresses: List<ContactAddress> = emptyList()

	@Column(name = "organization")
	var organization: String? = null

	@Column(name = "title")
	var title: String? = null

	@Column(name = "birthday")
	var birthday: LocalDate? = null

	@Column(name = "notes_md")
	var notesMd: String? = null

	@Column(name = "photo_file_id")
	var photoFileId: UUID? = null

	@Column(name = "external_id")
	var externalId: String? = null
}

enum class ContactSource {
	LOCAL,
	GOOGLE,
	MICROSOFT,
	CARDDAV,
}

/** An email address; [label] is free text such as `work` or `home`. */
data class ContactEmail(
	val address: String,
	val label: String? = null,
)

/** A phone number as entered (not normalized); [label] is free text such as `mobile`. */
data class ContactPhone(
	val number: String,
	val label: String? = null,
)

/** A postal address; every part is optional, but one of them is set. */
data class ContactAddress(
	val label: String? = null,
	val street: String? = null,
	val city: String? = null,
	val region: String? = null,
	val postalCode: String? = null,
	val country: String? = null,
) {
	/** The parts that are set, in reading order, for search and display. */
	fun parts(): List<String> = listOfNotNull(street, postalCode, city, region, country).filter { it.isNotBlank() }
}

private val listJson: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

@Converter
class EmailsConverter : AttributeConverter<List<ContactEmail>, String> {
	override fun convertToDatabaseColumn(attribute: List<ContactEmail>?): String = listJson.writeValueAsString(attribute.orEmpty())

	override fun convertToEntityAttribute(dbData: String?): List<ContactEmail> = dbData?.let { listJson.readValue(it) } ?: emptyList()
}

@Converter
class PhonesConverter : AttributeConverter<List<ContactPhone>, String> {
	override fun convertToDatabaseColumn(attribute: List<ContactPhone>?): String = listJson.writeValueAsString(attribute.orEmpty())

	override fun convertToEntityAttribute(dbData: String?): List<ContactPhone> = dbData?.let { listJson.readValue(it) } ?: emptyList()
}

@Converter
class AddressesConverter : AttributeConverter<List<ContactAddress>, String> {
	override fun convertToDatabaseColumn(attribute: List<ContactAddress>?): String = listJson.writeValueAsString(attribute.orEmpty())

	override fun convertToEntityAttribute(dbData: String?): List<ContactAddress> = dbData?.let { listJson.readValue(it) } ?: emptyList()
}
