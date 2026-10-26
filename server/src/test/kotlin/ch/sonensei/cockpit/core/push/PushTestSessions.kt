package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.auth.TestCockpitUser
import ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal
import ch.sonensei.cockpit.core.persistence.UuidV7
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Login sessions for push tests, written straight into `auth_session` for the account in `app_user` (a fictitious one
 * is inserted when there is none). Web Push subscriptions must belong to such a session.
 */
class PushTestSessions(
	private val jdbc: JdbcTemplate,
) {
	/** A new session that is valid until [expiresAt]; returns its id. */
	fun create(expiresAt: Instant = FAR_FUTURE): UUID {
		val id = UuidV7.generate()
		jdbc.update(
			"insert into auth_session (id, created_at, updated_at, version, user_id, token_hash, remember_device, " +
				"last_seen_at, expires_at) values (?, now(), now(), 0, ?, ?, false, now(), ?)",
			id,
			userId(),
			"fictitious-token-hash-$id",
			Timestamp.from(expiresAt),
		)
		return id
	}

	/** The test owner logged in with a new session, for `with(cockpitUser(...))`. */
	fun principal(expiresAt: Instant = FAR_FUTURE): CockpitPrincipal =
		TestCockpitUser.principal.copy(userId = userId(), sessionId = create(expiresAt))

	fun expire(
		sessionId: UUID,
		at: Instant,
	) {
		jdbc.update("update auth_session set expires_at = ? where id = ?", Timestamp.from(at), sessionId)
	}

	fun delete(sessionId: UUID) {
		jdbc.update("delete from auth_session where id = ?", sessionId)
	}

	private fun userId(): UUID =
		jdbc.queryForList("select id from app_user", UUID::class.java).firstOrNull()
			?: UuidV7.generate().also { id ->
				jdbc.update(
					"insert into app_user (id, created_at, updated_at, version, username, password_hash, totp_secret) " +
						"values (?, now(), now(), 0, ?, 'not-a-hash', 'not-a-secret')",
					id,
					TestCockpitUser.USERNAME,
				)
			}

	companion object {
		val FAR_FUTURE: Instant = Instant.parse("2100-01-01T00:00:00Z")
	}
}
