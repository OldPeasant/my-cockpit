package ch.sonensei.cockpit.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/** Encrypts the stored tokens. Both calls return null instead of throwing when the key is unusable or the data was tampered with. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray?

    fun decrypt(encrypted: ByteArray): ByteArray?
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore and never leaves it. The output is
 * `[iv length][iv][ciphertext + tag]`; the associated data binds it to the token record.
 */
class KeystoreTokenCipher
    @Inject
    constructor() : TokenCipher {
        override fun encrypt(plain: ByteArray): ByteArray? =
            guarded {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key())
                cipher.updateAAD(ASSOCIATED_DATA)
                val iv = cipher.iv
                val sealed = cipher.doFinal(plain)
                ByteBuffer
                    .allocate(1 + iv.size + sealed.size)
                    .put(iv.size.toByte())
                    .put(iv)
                    .put(sealed)
                    .array()
            }

        override fun decrypt(encrypted: ByteArray): ByteArray? =
            guarded {
                val ivLength = encrypted.firstOrNull()?.toInt() ?: return@guarded null
                if (ivLength <= 0 || encrypted.size <= 1 + ivLength) return@guarded null
                val existing = existingKey() ?: return@guarded null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(TAG_BITS, encrypted, 1, ivLength))
                cipher.updateAAD(ASSOCIATED_DATA)
                cipher.doFinal(encrypted, 1 + ivLength, encrypted.size - 1 - ivLength)
            }

        private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

        private fun key(): SecretKey =
            existingKey() ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                .apply {
                    init(
                        KeyGenParameterSpec
                            .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(KEY_BITS)
                            .build(),
                    )
                }.generateKey()

        /** Keystore failures (no Keystore, key invalidated, tampered data) mean "cannot encrypt/decrypt", not a crash. */
        private inline fun guarded(block: () -> ByteArray?): ByteArray? =
            try {
                block()
            } catch (_: GeneralSecurityException) {
                null
            } catch (_: ProviderException) {
                null
            } catch (_: IOException) {
                null
            }

        private companion object {
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
            const val KEY_ALIAS = "cockpit_auth_tokens"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val KEY_BITS = 256
            const val TAG_BITS = 128
            val ASSOCIATED_DATA = "cockpit-auth-tokens-v1".toByteArray()
        }
    }
