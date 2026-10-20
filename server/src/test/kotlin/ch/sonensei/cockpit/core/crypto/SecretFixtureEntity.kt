// Deliberately outside `ch.sonensei.cockpit`: only EncryptedSecretPersistenceTests scans this entity (other contexts
// validate the schema with Hibernate and have no table for it).
package ch.sonensei.cryptofixture

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** No `@Convert`: relies on [ch.sonensei.cockpit.core.crypto.EncryptedSecretConverter] being applied automatically. */
@Entity
@Table(name = "crypto_secret_fixture")
class SecretFixtureEntity(
	@Id
	val id: UUID,
	val secret: EncryptedSecret?,
)
