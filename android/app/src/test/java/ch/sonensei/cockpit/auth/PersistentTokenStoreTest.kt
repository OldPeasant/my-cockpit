package ch.sonensei.cockpit.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.AuthTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** [PersistentTokenStore] with a software AES-GCM cipher standing in for the Keystore one (Robolectric has no Keystore). */
@RunWith(AndroidJUnit4::class)
class PersistentTokenStoreTest {
    private lateinit var prefs: SharedPreferences
    private val cipher = SoftwareCipher()

    @Before
    fun setUp() {
        prefs =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSharedPreferences(PersistentTokenStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun `a login survives a restart`() {
        PersistentTokenStore(prefs, cipher).login(TOKENS, DEVICE_ID)

        val restarted = PersistentTokenStore(prefs, cipher)

        assertEquals(TOKENS, restarted.current())
        assertEquals(DeviceSession(TOKENS, DEVICE_ID), restarted.session.value)
    }

    @Test
    fun `only ciphertext reaches the disk`() {
        PersistentTokenStore(prefs, cipher).login(TOKENS, DEVICE_ID)

        val stored = prefs.all.values.joinToString()
        assertTrue(stored.isNotEmpty())
        listOf(TOKENS.accessToken, TOKENS.refreshToken, DEVICE_ID).forEach { assertFalse(it, stored.contains(it)) }
    }

    @Test
    fun `clear forgets the tokens in memory and on disk`() {
        val store = PersistentTokenStore(prefs, cipher)
        store.login(TOKENS, DEVICE_ID)

        store.clear()

        assertNull(store.current())
        assertNull(store.session.value)
        assertTrue(prefs.all.isEmpty())
        assertNull(PersistentTokenStore(prefs, cipher).current())
    }

    @Test
    fun `a refresh replaces the tokens and keeps the device id`() {
        val store = PersistentTokenStore(prefs, cipher)
        store.login(TOKENS, DEVICE_ID)
        val rotated = AuthTokens("access-2", "refresh-2")

        assertTrue(store.replace(TOKENS, rotated))

        assertEquals(DeviceSession(rotated, DEVICE_ID), PersistentTokenStore(prefs, cipher).session.value)
    }

    @Test
    fun `logins and rotated tokens are written to disk synchronously`() {
        val recording = RecordingPrefs(prefs)
        val store = PersistentTokenStore(recording, cipher)

        store.login(TOKENS, DEVICE_ID)
        assertTrue(store.replace(TOKENS, AuthTokens("access-2", "refresh-2")))

        assertEquals(listOf("commit", "commit"), recording.writes)
        assertEquals(AuthTokens("access-2", "refresh-2"), PersistentTokenStore(prefs, cipher).current())
    }

    @Test
    fun `replace refuses when the tokens changed meanwhile`() {
        val store = PersistentTokenStore(prefs, cipher)
        store.login(TOKENS, DEVICE_ID)

        assertFalse(store.replace(AuthTokens("other", "other"), null))
        assertEquals(TOKENS, store.current())
    }

    @Test
    fun `a refresh finishing after a logout does not log back in`() {
        val store = PersistentTokenStore(prefs, cipher)
        store.login(TOKENS, DEVICE_ID)
        store.clear()

        assertFalse(store.replace(TOKENS, AuthTokens("access-2", "refresh-2")))

        assertNull(store.current())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `replace with null logs out`() {
        val store = PersistentTokenStore(prefs, cipher)
        store.login(TOKENS, DEVICE_ID)

        assertTrue(store.replace(TOKENS, null))

        assertNull(store.current())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `a record that no longer decrypts counts as logged out and is deleted`() {
        PersistentTokenStore(prefs, cipher).login(TOKENS, DEVICE_ID)

        // Another key, as after the Keystore key was invalidated.
        val restarted = PersistentTokenStore(prefs, SoftwareCipher())

        assertNull(restarted.current())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `a corrupted record counts as logged out`() {
        prefs.edit().putString(PersistentTokenStore.KEY_SESSION, "%%% not base64 %%%").commit()

        assertNull(PersistentTokenStore(prefs, cipher).current())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `without a usable key the tokens stay in memory and are never written in plain text`() {
        val noKeystore =
            object : TokenCipher {
                override fun encrypt(plain: ByteArray): ByteArray? = null

                override fun decrypt(encrypted: ByteArray): ByteArray? = null
            }
        val store = PersistentTokenStore(prefs, noKeystore)

        store.login(TOKENS, DEVICE_ID)

        assertEquals(TOKENS, store.current())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `when a new login cannot be encrypted the previous record does not stay on disk`() {
        PersistentTokenStore(prefs, cipher).login(TOKENS, DEVICE_ID)
        val encryptFails =
            object : TokenCipher {
                override fun encrypt(plain: ByteArray): ByteArray? = null

                override fun decrypt(encrypted: ByteArray): ByteArray? = cipher.decrypt(encrypted)
            }
        val store = PersistentTokenStore(prefs, encryptFails)
        assertEquals(TOKENS, store.current())
        val other = AuthTokens("access-other", "refresh-other")

        store.login(other, null)

        assertEquals(other, store.current())
        assertTrue(prefs.all.isEmpty())
        assertNull("a restart must not bring back the previous account", PersistentTokenStore(prefs, cipher).current())
    }

    @Test
    fun `a stored record without a device id still loads`() {
        val record = """{"accessToken":"access-old","refreshToken":"refresh-old"}"""
        prefs
            .edit()
            .putString(PersistentTokenStore.KEY_SESSION, Base64.encodeToString(cipher.encrypt(record.toByteArray()), Base64.NO_WRAP))
            .commit()

        assertEquals(DeviceSession(AuthTokens("access-old", "refresh-old"), null), PersistentTokenStore(prefs, cipher).session.value)
    }

    @Test
    fun `a decrypted record that is not a token record counts as logged out and is deleted`() {
        prefs
            .edit()
            .putString(PersistentTokenStore.KEY_SESSION, Base64.encodeToString(cipher.encrypt("[1,2,3]".toByteArray()), Base64.NO_WRAP))
            .commit()

        assertNull(PersistentTokenStore(prefs, cipher).current())
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `the Keystore cipher fails closed where there is no Android Keystore`() {
        val keystore = KeystoreTokenCipher()

        assertNull(keystore.encrypt("secret".toByteArray()))
        assertNull(keystore.decrypt(byteArrayOf(12, 1, 2, 3)))
    }

    @Test
    fun `the Keystore cipher rejects records too short to hold an iv and ciphertext`() {
        val keystore = KeystoreTokenCipher()

        assertNull(keystore.decrypt(byteArrayOf()))
        assertNull(keystore.decrypt(byteArrayOf(0, 1, 2)))
        assertNull(keystore.decrypt(byteArrayOf(-1, 1, 2)))
        assertNull(keystore.decrypt(byteArrayOf(12, 1, 2)))
    }

    @Test
    fun `tokens are redacted from the session's string form`() {
        val text = DeviceSession(TOKENS, DEVICE_ID).toString()

        assertFalse(text.contains(TOKENS.accessToken))
        assertFalse(text.contains(TOKENS.refreshToken))
    }

    /** Records whether each write is committed synchronously or applied asynchronously. */
    private class RecordingPrefs(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        val writes = mutableListOf<String>()

        override fun edit(): SharedPreferences.Editor = RecordingEditor(delegate.edit())

        private inner class RecordingEditor(
            private val editor: SharedPreferences.Editor,
        ) : SharedPreferences.Editor by editor {
            override fun commit(): Boolean = editor.commit().also { writes += "commit" }

            override fun apply() {
                editor.apply()
                writes += "apply"
            }
        }
    }

    /** AES-GCM with an in-memory key: same format as the Keystore cipher. */
    private class SoftwareCipher : TokenCipher {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun encrypt(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain)
        }

        override fun decrypt(encrypted: ByteArray): ByteArray? {
            val ivLength = encrypted[0].toInt()
            val cipher =
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted, 1, ivLength))
                }
            return try {
                cipher.doFinal(encrypted, 1 + ivLength, encrypted.size - 1 - ivLength)
            } catch (_: java.security.GeneralSecurityException) {
                null
            }
        }
    }

    private companion object {
        val TOKENS = AuthTokens("access-fictitious-1", "refresh-fictitious-1")
        const val DEVICE_ID = "0192f1a0-0000-7000-8000-000000000001"
    }
}
