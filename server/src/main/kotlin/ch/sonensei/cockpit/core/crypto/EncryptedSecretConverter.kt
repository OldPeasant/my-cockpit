package ch.sonensei.cockpit.core.crypto

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Stores [EncryptedSecret] attributes as [SecretCipher] ciphertext (a `text` column). Applied automatically to
 * every entity attribute of that type. Hibernate gets the instance from Spring, so the cipher is injected.
 */
@Converter(autoApply = true)
class EncryptedSecretConverter(
	private val cipher: SecretCipher,
) : AttributeConverter<EncryptedSecret, String> {
	override fun convertToDatabaseColumn(attribute: EncryptedSecret?): String? = attribute?.let { cipher.encrypt(it.reveal()) }

	override fun convertToEntityAttribute(dbData: String?): EncryptedSecret? = dbData?.let { EncryptedSecret(cipher.decrypt(it)) }
}
