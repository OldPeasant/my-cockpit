package ch.sonensei.cockpit.core.backupcontrib

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.json
import ch.sonensei.cockpit.backup.domain.secret
import ch.sonensei.cockpit.core.crypto.SecretCipher
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionStatus
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * `data/oauth_connections.json`: the connected Google/Microsoft accounts (SPEC.md §7.1, §10.2) that calendar sources,
 * mail accounts and contacts use; module contributors that reference them depend on [ID]. The refresh tokens are
 * secrets: only in the backup secret [SECRETS] (id → token, with a passphrase). A connection restored with its token is
 * [OAuthConnectionStatus.CONNECTED]; without, it is [OAuthConnectionStatus.RECONNECT_REQUIRED] and the owner connects
 * the account again. The status and the last error follow from that and are not backed up; access tokens are only ever
 * in memory.
 */
@Component
class OAuthBackupContributor(
	private val jdbc: JdbcTemplate,
	private val cipher: SecretCipher,
) : BackupContributor {
	private val log = LoggerFactory.getLogger(javaClass)

	override val id = ID

	override fun export(writer: BackupDataWriter) {
		val tokens = sortedMapOf<String, String>()
		val connections =
			jdbc.query("select * from oauth_connection order by id") { rs, _ ->
				val connection =
					OAuthConnectionBackup(
						id = rs.uuid("id"),
						createdAt = rs.instant("created_at"),
						updatedAt = rs.instant("updated_at"),
						version = rs.getLong("version"),
						provider = rs.getString("provider"),
						subject = rs.getString("subject"),
						email = rs.getString("email"),
						displayName = rs.getString("display_name"),
						scopes = rs.getString("scopes"),
						lastRefreshedAt = rs.instantOrNull("last_refreshed_at"),
					)
				if (writer.includesSecrets) rs.getString("refresh_token")?.let { tokens[connection.id.toString()] = cipher.decrypt(it) }
				connection
			}
		if (connections.isEmpty()) return
		writer.json(CONNECTIONS, connections)
		if (tokens.isNotEmpty()) writer.secret(SECRETS, tokens)
	}

	override fun import(reader: BackupDataReader) {
		val connections = reader.json<List<OAuthConnectionBackup>>(CONNECTIONS) ?: return
		val tokens = reader.secret<Map<String, String>>(SECRETS).orEmpty()
		var reconnect = 0
		connections.forEach { connection ->
			val token = tokens[connection.id.toString()]
			if (token == null) reconnect++
			jdbc.update(
				"""insert into oauth_connection
				(id, created_at, updated_at, version, provider, subject, email, display_name, scopes, refresh_token, status, last_error,
				last_refreshed_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				connection.id,
				connection.createdAt.toTimestamp(),
				connection.updatedAt.toTimestamp(),
				connection.version,
				connection.provider,
				connection.subject,
				connection.email,
				connection.displayName,
				connection.scopes,
				token?.let(cipher::encrypt),
				(if (token == null) OAuthConnectionStatus.RECONNECT_REQUIRED else OAuthConnectionStatus.CONNECTED).name,
				if (token == null) RESTORED_WITHOUT_TOKEN else null,
				connection.lastRefreshedAt.toTimestampOrNull(),
			)
		}
		if (reconnect > 0) log.warn("Restored {} OAuth connections without their refresh token: they must be reconnected", reconnect)
	}

	override fun restoreWarnings(reader: BackupDataReader): List<String> {
		val count = reader.json<List<OAuthConnectionBackup>>(CONNECTIONS)?.size ?: 0
		if (count == 0) return emptyList()
		return listOf(
			if (reader.hasSecrets) {
				"Without the backup's passphrase, the $count connected Google/Microsoft accounts must be connected again"
			} else {
				"The backup has no OAuth tokens: the $count connected Google/Microsoft accounts must be connected again"
			},
		)
	}

	companion object {
		const val ID = "oauth"
		const val CONNECTIONS = "oauth_connections"

		/** Name of the refresh tokens (connection id → token) in the backup's secrets. */
		const val SECRETS = "oauth_refresh_tokens"

		/** `last_error` of a connection restored without its refresh token. */
		const val RESTORED_WITHOUT_TOKEN = "restored_without_token"
	}
}

/** One OAuth connection in `data/oauth_connections.json`; no tokens (see [OAuthBackupContributor]). */
data class OAuthConnectionBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val provider: String,
	val subject: String,
	val email: String?,
	val displayName: String?,
	/** Granted scopes, space-separated. */
	val scopes: String,
	val lastRefreshedAt: Instant?,
)
