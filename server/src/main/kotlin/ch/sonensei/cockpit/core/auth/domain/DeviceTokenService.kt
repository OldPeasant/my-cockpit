package ch.sonensei.cockpit.core.auth.domain

import ch.sonensei.cockpit.core.auth.domain.LoginService.Companion.hash
import ch.sonensei.cockpit.core.auth.domain.LoginService.Companion.newToken
import ch.sonensei.cockpit.core.auth.domain.LoginService.Companion.principalOf
import ch.sonensei.cockpit.core.auth.infra.AuthDeviceRepository
import ch.sonensei.cockpit.core.auth.infra.AuthDeviceRetiredTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Bearer tokens for the Android app (SPEC.md §4.1). [issue] logs a device in with the same credentials and throttle
 * as the web login; [refresh] rotates both tokens; a refresh token presented again after its rotation revokes the
 * device (it must have leaked). Revoked or expired devices are deleted, so their tokens are simply unknown.
 */
@Service
class DeviceTokenService(
	private val login: LoginService,
	private val devices: AuthDeviceRepository,
	private val retiredTokens: AuthDeviceRetiredTokenRepository,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val random = SecureRandom()

	@Transactional
	fun issue(
		credentials: LoginCredentials,
		deviceName: String,
	): DeviceTokenResult {
		val (account, now) =
			when (val check = login.verify(credentials)) {
				is CredentialCheck.Rejected -> return DeviceTokenResult.Rejected(check.result)
				is CredentialCheck.Verified -> check
			}
		deleteExpired(now)
		val access = newToken(random)
		val refresh = newToken(random)
		val device = devices.saveAndFlush(AuthDevice(account, deviceName, hash(access), hash(refresh), now))
		login.recordSuccess()
		log.info("Device {} logged in", device.id)
		return DeviceTokenResult.Issued(grantOf(device, access, refresh))
	}

	/** Rotates the token pair of the device holding [refreshToken]; null (→ 401) if the token is not current. */
	@Transactional
	fun refresh(refreshToken: String): DeviceTokens? {
		val now = now()
		val tokenHash = hash(refreshToken)
		val device = devices.findByRefreshTokenHashForUpdate(tokenHash)
		if (device == null) {
			retiredTokens.findDeviceIdByTokenHash(tokenHash)?.let { deviceId ->
				devices.deleteDevice(deviceId)
				log.warn("Rotated refresh token of device {} was used again; device revoked", deviceId)
			}
			return null
		}
		if (device.isRefreshExpiredAt(now)) {
			devices.deleteDevice(device.id)
			return null
		}
		retiredTokens.save(AuthDeviceRetiredToken(device, tokenHash))
		val access = newToken(random)
		val refresh = newToken(random)
		device.rotate(hash(access), hash(refresh), now)
		devices.saveAndFlush(device)
		deleteExpired(now)
		return grantOf(device, access, refresh)
	}

	/** The owner behind a current access token, or null if it is unknown, expired or its device was revoked. */
	@Transactional(readOnly = true)
	fun authenticate(accessToken: String): CockpitPrincipal? {
		val device = devices.findByAccessTokenHash(hash(accessToken)) ?: return null
		if (device.isAccessExpiredAt(now())) return null
		return principalOf(device.user)
	}

	@Transactional(readOnly = true)
	fun list(): List<DeviceInfo> = devices.findAllByOrderByCreatedAtAsc().filterNot { it.isRefreshExpiredAt(now()) }.map(::infoOf)

	/** Revokes the device: its tokens stop working at once. False if there is no such device. */
	@Transactional
	fun revoke(id: UUID): Boolean {
		val revoked = devices.deleteDevice(id) > 0
		if (revoked) log.info("Device {} revoked", id)
		return revoked
	}

	private fun deleteExpired(now: Instant) {
		devices.deleteExpired(now)
		// A retired token older than the refresh lifetime belongs to a token pair that has expired anyway.
		retiredTokens.deleteCreatedBefore(now.minus(AuthDevice.REFRESH_LIFETIME))
	}

	private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

	private fun grantOf(
		device: AuthDevice,
		access: String,
		refresh: String,
	) = DeviceTokens(device.id, access, device.accessExpiresAt, refresh, device.refreshExpiresAt)

	private fun infoOf(device: AuthDevice) = DeviceInfo(device.id, device.name, device.createdAt ?: device.lastSeenAt, device.lastSeenAt)
}

sealed interface DeviceTokenResult {
	class Issued(
		val tokens: DeviceTokens,
	) : DeviceTokenResult

	class Rejected(
		val result: LoginResult,
	) : DeviceTokenResult
}

/** A fresh token pair; the tokens are returned to the device once and not stored. `toString` hides them. */
class DeviceTokens(
	val deviceId: UUID,
	val accessToken: String,
	val accessTokenExpiresAt: Instant,
	val refreshToken: String,
	val refreshTokenExpiresAt: Instant,
) {
	override fun toString(): String = "DeviceTokens(deviceId=$deviceId, ******)"
}

data class DeviceInfo(
	val id: UUID,
	val name: String,
	val createdAt: Instant,
	val lastSeenAt: Instant,
)
