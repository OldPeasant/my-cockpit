package ch.sonensei.cockpit.core.backupcontrib

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/*
 * The core contributors read and write rows with JDBC rather than JPA: a restore must keep ids, `created_at`,
 * `updated_at` and `version` exactly as backed up, which JPA auditing and versioning would overwrite.
 */

internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

internal fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

internal fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

internal fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)

internal fun Instant?.toTimestampOrNull(): Timestamp? = this?.let(Timestamp::from)

/** Escapes a value for a Markdown table cell of a readable mirror. */
internal fun String.markdownCell(): String = replace("\\", "\\\\").replace("|", "\\|").replace(Regex("\r\n|\r|\n"), " ")
