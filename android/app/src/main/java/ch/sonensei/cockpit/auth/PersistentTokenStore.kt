package ch.sonensei.cockpit.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.core.network.TokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** The logged-in device: its tokens and, when the login returned it, the server's id for this device. */
data class DeviceSession(
    val tokens: AuthTokens,
    val deviceId: String?,
)

/**
 * Keeps the tokens in memory for the fast reads OkHttp needs and persists them encrypted with [TokenCipher]
 * (Keystore-backed), so the device stays logged in across restarts. Only ciphertext reaches the disk. If the stored
 * record cannot be decrypted (key lost or data tampered with) it is deleted and the device counts as logged out; if
 * the tokens cannot be encrypted they are kept in memory only, never written in plain text.
 */
@Singleton
class PersistentTokenStore(
    private val prefs: SharedPreferences,
    private val cipher: TokenCipher,
) : TokenStore {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        cipher: TokenCipher,
    ) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), cipher)

    private val state = MutableStateFlow(load())

    /** The current session; null while logged out. */
    val session: StateFlow<DeviceSession?> = state.asStateFlow()

    override fun current(): AuthTokens? = state.value?.tokens

    /** Stores a new login. */
    @Synchronized
    fun login(
        tokens: AuthTokens,
        deviceId: String?,
    ) = set(DeviceSession(tokens, deviceId))

    @Synchronized
    override fun save(tokens: AuthTokens) = set(DeviceSession(tokens, null))

    @Synchronized
    override fun clear() = set(null)

    @Synchronized
    override fun replace(
        expected: AuthTokens,
        new: AuthTokens?,
    ): Boolean {
        val current = state.value
        if (current?.tokens != expected) return false
        set(new?.let { DeviceSession(it, current.deviceId) })
        return true
    }

    private fun set(session: DeviceSession?) {
        state.value = session
        val encrypted =
            session
                ?.let { Json.encodeToString(Record(it.tokens.accessToken, it.tokens.refreshToken, it.deviceId)) }
                ?.let { cipher.encrypt(it.toByteArray()) }
        prefs.edit {
            if (encrypted == null) remove(KEY_SESSION) else putString(KEY_SESSION, Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
    }

    private fun load(): DeviceSession? {
        val stored = prefs.getString(KEY_SESSION, null) ?: return null
        val record =
            try {
                cipher
                    .decrypt(Base64.decode(stored, Base64.NO_WRAP))
                    ?.let { Json.decodeFromString<Record>(it.decodeToString()) }
            } catch (_: IllegalArgumentException) {
                // Not Base64, or a record that does not decode (SerializationException is an IllegalArgumentException).
                null
            } catch (_: SerializationException) {
                null
            }
        if (record == null) {
            prefs.edit { remove(KEY_SESSION) }
            return null
        }
        return DeviceSession(AuthTokens(record.accessToken, record.refreshToken), record.deviceId)
    }

    @Serializable
    private data class Record(
        val accessToken: String,
        val refreshToken: String,
        val deviceId: String? = null,
    )

    internal companion object {
        const val PREFS_NAME = "auth_tokens"
        const val KEY_SESSION = "session"
    }
}
