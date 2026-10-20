package ch.sonensei.cockpit.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.AuthTokens
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * The production [KeystoreTokenCipher] against a stand-in "AndroidKeyStore" JCA provider (Robolectric has none):
 * the provider only keeps keys in memory, while the cipher's own key spec, record format, AAD and failure handling
 * run unchanged.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTokenCipherTest {
    @Before
    fun setUp() {
        FakeKeys.reset()
        Security.insertProviderAt(FakeAndroidKeyStore, 1)
    }

    @After
    fun tearDown() {
        Security.removeProvider(FakeAndroidKeyStore.name)
        FakeKeys.reset()
    }

    @Test
    fun `encrypted tokens decrypt back to the plain text and do not contain it`() {
        val cipher = KeystoreTokenCipher()

        val encrypted = checkNotNull(cipher.encrypt(PLAIN))

        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains(String(PLAIN)))
        assertArrayEquals(PLAIN, cipher.decrypt(encrypted))
    }

    @Test
    fun `the key is an AES-256 GCM key generated in the Android Keystore once and reused`() {
        val first = checkNotNull(KeystoreTokenCipher().encrypt(PLAIN))
        val second = checkNotNull(KeystoreTokenCipher().encrypt(PLAIN))

        val spec = FakeKeys.specs.single()
        assertEquals(256, spec.keySize)
        assertEquals(listOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes.toList())
        assertEquals(listOf(KeyProperties.ENCRYPTION_PADDING_NONE), spec.encryptionPaddings.toList())
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, spec.purposes)
        assertEquals(setOf(spec.keystoreAlias), FakeKeys.keys.keys)
        // A fresh instance (e.g. after an app restart) reads records written by an earlier one.
        assertArrayEquals(PLAIN, KeystoreTokenCipher().decrypt(first))
        assertArrayEquals(PLAIN, KeystoreTokenCipher().decrypt(second))
    }

    @Test
    fun `each encryption uses a fresh iv`() {
        val cipher = KeystoreTokenCipher()

        val first = checkNotNull(cipher.encrypt(PLAIN))
        val second = checkNotNull(cipher.encrypt(PLAIN))

        assertEquals(GCM_IV_BYTES, first[0].toInt())
        assertFalse(first.copyOfRange(1, 1 + GCM_IV_BYTES).contentEquals(second.copyOfRange(1, 1 + GCM_IV_BYTES)))
    }

    @Test
    fun `a tampered record does not decrypt`() {
        val cipher = KeystoreTokenCipher()
        val encrypted = checkNotNull(cipher.encrypt(PLAIN))

        for (index in listOf(1, encrypted.size / 2, encrypted.size - 1)) {
            val tampered = encrypted.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertNull("byte $index flipped", cipher.decrypt(tampered))
        }
    }

    @Test
    fun `a record sealed with the same key but without the token record's associated data does not decrypt`() {
        val cipher = KeystoreTokenCipher()
        cipher.encrypt(PLAIN) // creates the key
        val key = FakeKeys.keys.values.single()
        val raw = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val foreign = byteArrayOf(raw.iv.size.toByte()) + raw.iv + raw.doFinal(PLAIN)

        assertNull(cipher.decrypt(foreign))
    }

    @Test
    fun `after the Keystore key is lost a stored record does not decrypt and no new key is made for it`() {
        val encrypted = checkNotNull(KeystoreTokenCipher().encrypt(PLAIN))
        FakeKeys.keys.clear()

        assertNull(KeystoreTokenCipher().decrypt(encrypted))
        assertEquals(emptySet<String>(), FakeKeys.keys.keys)
    }

    @Test
    fun `a record encrypted under a replaced key does not decrypt`() {
        val encrypted = checkNotNull(KeystoreTokenCipher().encrypt(PLAIN))
        val alias = FakeKeys.keys.keys.single()
        FakeKeys.keys[alias] = KeyGenerator.getInstance("AES", "SunJCE").apply { init(256) }.generateKey()

        assertNull(KeystoreTokenCipher().decrypt(encrypted))
    }

    @Test
    fun `the token store with the Keystore cipher keeps a login across a restart and only writes ciphertext`() {
        val prefs =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSharedPreferences(PersistentTokenStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val tokens = AuthTokens("access-fictitious-k1", "refresh-fictitious-k1")

        PersistentTokenStore(prefs, KeystoreTokenCipher()).login(tokens, DEVICE_ID)
        val restarted = PersistentTokenStore(prefs, KeystoreTokenCipher())

        assertEquals(DeviceSession(tokens, DEVICE_ID), restarted.session.value)
        val onDisk = prefs.all.values.joinToString()
        listOf(tokens.accessToken, tokens.refreshToken, DEVICE_ID).forEach { assertFalse(onDisk.contains(it)) }
        prefs.edit().clear().commit()
    }

    /** Test-only "AndroidKeyStore" provider: a KeyStore and an AES KeyGenerator that keep keys in memory. */
    private object FakeAndroidKeyStore : Provider("AndroidKeyStore", 1.0, "in-memory stand-in for tests") {
        private fun readResolve(): Any = FakeAndroidKeyStore

        init {
            putService(
                object : Service(this, "KeyStore", "AndroidKeyStore", FakeKeyStoreSpi::class.java.name, null, null) {
                    override fun newInstance(constructorParameter: Any?): Any = FakeKeyStoreSpi()
                },
            )
            putService(
                object : Service(this, "KeyGenerator", "AES", FakeKeyGeneratorSpi::class.java.name, null, null) {
                    override fun newInstance(constructorParameter: Any?): Any = FakeKeyGeneratorSpi()
                },
            )
        }
    }

    /** What the fake Keystore holds: generated keys by alias, and the specs they were generated with. */
    private object FakeKeys {
        val keys: MutableMap<String, SecretKey> = Collections.synchronizedMap(mutableMapOf())
        val specs: MutableList<KeyGenParameterSpec> = Collections.synchronizedList(mutableListOf())

        fun reset() {
            keys.clear()
            specs.clear()
        }
    }

    private class FakeKeyGeneratorSpi : KeyGeneratorSpi() {
        private var spec: KeyGenParameterSpec? = null

        override fun engineInit(random: SecureRandom?) = throw UnsupportedOperationException("needs a KeyGenParameterSpec")

        override fun engineInit(
            params: AlgorithmParameterSpec?,
            random: SecureRandom?,
        ) {
            spec = params as KeyGenParameterSpec
        }

        override fun engineInit(
            keysize: Int,
            random: SecureRandom?,
        ) = throw UnsupportedOperationException("needs a KeyGenParameterSpec")

        override fun engineGenerateKey(): SecretKey {
            val spec = checkNotNull(spec)
            FakeKeys.specs += spec
            val key = KeyGenerator.getInstance("AES", "SunJCE").apply { init(spec.keySize) }.generateKey()
            FakeKeys.keys[spec.keystoreAlias] = key
            return key
        }
    }

    private class FakeKeyStoreSpi : KeyStoreSpi() {
        private val keys get() = FakeKeys.keys

        override fun engineGetKey(
            alias: String?,
            password: CharArray?,
        ): Key? = keys[alias]

        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String?): Certificate? = null

        override fun engineGetCreationDate(alias: String?): Date? = null

        override fun engineSetKeyEntry(
            alias: String?,
            key: Key?,
            password: CharArray?,
            chain: Array<out Certificate>?,
        ) = throw UnsupportedOperationException()

        override fun engineSetKeyEntry(
            alias: String?,
            key: ByteArray?,
            chain: Array<out Certificate>?,
        ) = throw UnsupportedOperationException()

        override fun engineSetCertificateEntry(
            alias: String?,
            cert: Certificate?,
        ) = throw UnsupportedOperationException()

        override fun engineDeleteEntry(alias: String?) {
            keys.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())

        override fun engineContainsAlias(alias: String?): Boolean = keys.containsKey(alias)

        override fun engineSize(): Int = keys.size

        override fun engineIsKeyEntry(alias: String?): Boolean = keys.containsKey(alias)

        override fun engineIsCertificateEntry(alias: String?): Boolean = false

        override fun engineGetCertificateAlias(cert: Certificate?): String? = null

        override fun engineStore(
            stream: OutputStream?,
            password: CharArray?,
        ) = Unit

        override fun engineLoad(
            stream: InputStream?,
            password: CharArray?,
        ) = Unit

        override fun engineLoad(param: KeyStore.LoadStoreParameter?) = Unit
    }

    private companion object {
        val PLAIN = """{"accessToken":"access-fictitious-k0","refreshToken":"refresh-fictitious-k0"}""".toByteArray()
        const val DEVICE_ID = "0192f1a0-0000-7000-8000-00000000000k"
        const val GCM_IV_BYTES = 12
    }
}
