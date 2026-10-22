package ch.sonensei.cockpit.contacts.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupReadableWriter
import ch.sonensei.cockpit.backup.domain.jsonLines
import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactAddress
import ch.sonensei.cockpit.contacts.domain.ContactEmail
import ch.sonensei.cockpit.contacts.domain.ContactPhone
import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.contacts.domain.ContactSource
import ch.sonensei.cockpit.contacts.domain.VCardService
import ch.sonensei.cockpit.core.backupcontrib.instant
import ch.sonensei.cockpit.core.backupcontrib.instantOrNull
import ch.sonensei.cockpit.core.backupcontrib.toTimestamp
import ch.sonensei.cockpit.core.backupcontrib.toTimestampOrNull
import ch.sonensei.cockpit.core.backupcontrib.uuid
import ch.sonensei.cockpit.files.backup.FilesBackupContributor
import ezvcard.VCardVersion
import ezvcard.io.text.VCardWriter
import ezvcard.parameter.ImageType
import ezvcard.property.Photo
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.io.OutputStreamWriter
import java.net.URLEncoder
import java.sql.Date
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Contacts in backups (SPEC.md §8.3, §10.2): `data/contacts.jsonl` with every contact, those in the Trash and those of
 * external sources included (with their external id, so links to them survive; the next sync matches them by it).
 * Photos are drive files: the files contributor backs them up, a contact keeps only the photo's file id.
 *
 * Mirror: `readable/contacts/contacts.vcf`, one vCard 3.0 file with every contact not in the Trash (sorted by name, the
 * contact id as `UID`), as the vCard export writes them. A photo is a `PHOTO` URI pointing to the file in the backup,
 * `../../files/<path>` (paths from [FilesBackupContributor]). No file when there is no such contact.
 *
 * Restore keeps ids, times and versions and indexes the contacts not in the Trash for search again. Links are restored
 * by the links contributor.
 *
 * Rows are read and written with JDBC rather than JPA: a restore must keep ids, `created_at`, `updated_at` and
 * `version` exactly as backed up, which JPA auditing and versioning would overwrite.
 */
@Component
class ContactsBackupContributor(
	private val jdbc: JdbcTemplate,
	private val jsonMapper: JsonMapper,
	private val vcards: VCardService,
	private val contacts: ContactService,
	private val files: FilesBackupContributor,
) : BackupContributor {
	override val id = ID

	override fun export(writer: BackupDataWriter) {
		contactRows("select * from contact order by id") { writer.jsonLines(CONTACTS, it) }
	}

	override fun readable(writer: BackupReadableWriter) {
		val active = "select * from contact where deleted_at is null order by display_name, id"
		val (count, photos) =
			contactRows(active) { all ->
				var count = 0
				val photos = mutableSetOf<UUID>()
				all.forEach { contact ->
					count++
					contact.photoFileId?.let(photos::add)
				}
				count to photos
			}
		if (count == 0) return
		val paths = files.paths(photos)
		writer.stream("contacts/contacts.vcf") { out ->
			// Not closed: that would close the backup's stream.
			val vcf = VCardWriter(OutputStreamWriter(out, Charsets.UTF_8), VCardVersion.V3_0).apply { isAddProdId = false }
			contactRows(active) { all ->
				all.forEach { contact ->
					val card = vcards.toVCard(contact.toContact())
					contact.photoFileId?.let(paths::get)?.let { path ->
						card.addPhoto(Photo("../../files/${encoded(path)}", ImageType.find(null, null, path.substringAfterLast('.', ""))))
					}
					vcf.write(card)
				}
			}
			vcf.flush()
		}
	}

	override fun import(reader: BackupDataReader) {
		reader.jsonLines<ContactBackup>(CONTACTS) { contact ->
			jdbc.update(
				"""insert into contact (id, created_at, updated_at, version, deleted_at, display_name, given_name, family_name,
				emails, phones, addresses, organization, title, birthday, notes_md, photo_file_id, source, external_id)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)""",
				contact.id,
				contact.createdAt.toTimestamp(),
				contact.updatedAt.toTimestamp(),
				contact.version,
				contact.deletedAt.toTimestampOrNull(),
				contact.displayName,
				contact.givenName,
				contact.familyName,
				jsonMapper.writeValueAsString(contact.emails),
				jsonMapper.writeValueAsString(contact.phones),
				jsonMapper.writeValueAsString(contact.addresses),
				contact.organization,
				contact.title,
				contact.birthday?.let(Date::valueOf),
				contact.notesMd,
				contact.photoFileId,
				contact.source,
				contact.externalId,
			)
			if (contact.deletedAt == null) contacts.indexContact(contact.toContact())
		}
	}

	/** The contacts [sql] selects (`contact.*`), streamed from the database. */
	private fun <R> contactRows(
		sql: String,
		block: (Sequence<ContactBackup>) -> R,
	): R =
		jdbc
			.queryForStream(
				PreparedStatementCreator { connection -> connection.prepareStatement(sql).apply { fetchSize = FETCH_SIZE } },
			) { rs, _ -> row(rs) }
			.use { block(it.iterator().asSequence()) }

	private fun row(rs: ResultSet) =
		ContactBackup(
			id = rs.uuid("id"),
			createdAt = rs.instant("created_at"),
			updatedAt = rs.instant("updated_at"),
			version = rs.getLong("version"),
			deletedAt = rs.instantOrNull("deleted_at"),
			displayName = rs.getString("display_name"),
			givenName = rs.getString("given_name"),
			familyName = rs.getString("family_name"),
			emails = jsonMapper.readValue(rs.getString("emails")),
			phones = jsonMapper.readValue(rs.getString("phones")),
			addresses = jsonMapper.readValue(rs.getString("addresses")),
			organization = rs.getString("organization"),
			title = rs.getString("title"),
			birthday = rs.getObject("birthday", LocalDate::class.java),
			notesMd = rs.getString("notes_md"),
			photoFileId = rs.getObject("photo_file_id", UUID::class.java),
			source = rs.getString("source"),
			externalId = rs.getString("external_id"),
		)

	private fun ContactBackup.toContact(): Contact =
		Contact(displayName, ContactSource.valueOf(source), id).also {
			it.givenName = givenName
			it.familyName = familyName
			it.emails = emails
			it.phones = phones
			it.addresses = addresses
			it.organization = organization
			it.title = title
			it.birthday = birthday
			it.notesMd = notesMd
			it.photoFileId = photoFileId
			it.externalId = externalId
		}

	companion object {
		const val ID = "contacts"
		const val CONTACTS = "contacts"
		private const val FETCH_SIZE = 1000

		/** [path] with each segment percent-encoded, so names with spaces or brackets stay one URI. */
		private fun encoded(path: String): String =
			path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
	}
}

/** One line of `data/contacts.jsonl`. [source] by name, as stored. */
data class ContactBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val deletedAt: Instant?,
	val displayName: String,
	val givenName: String?,
	val familyName: String?,
	val emails: List<ContactEmail>,
	val phones: List<ContactPhone>,
	val addresses: List<ContactAddress>,
	val organization: String?,
	val title: String?,
	val birthday: LocalDate?,
	val notesMd: String?,
	val photoFileId: UUID?,
	val source: String,
	val externalId: String?,
)
