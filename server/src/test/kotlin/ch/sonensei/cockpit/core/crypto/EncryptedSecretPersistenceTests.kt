package ch.sonensei.cockpit.core.crypto

import ch.sonensei.cockpit.CockpitServerApplication
import ch.sonensei.cockpit.support.PostgresTestBase
import ch.sonensei.cryptofixture.SecretFixtureEntity
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import java.util.UUID

/**
 * The converter against real Postgres. The fixture entity lives outside the application's package (other
 * contexts must not scan it), and its table is created here, so Hibernate does not validate the schema.
 */
@SpringBootTest(properties = ["spring.jpa.hibernate.ddl-auto=none"])
class EncryptedSecretPersistenceTests(
	@Autowired private val entityManager: EntityManager,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val cipher: SecretCipher,
	@Autowired private val jsonMapper: JsonMapper,
) : PostgresTestBase() {
	@TestConfiguration(proxyBeanMethods = false)
	@EntityScan(basePackageClasses = [CockpitServerApplication::class, SecretFixtureEntity::class])
	class FixtureScan

	@BeforeEach
	fun createTable() {
		jdbc.execute("create table if not exists crypto_secret_fixture (id uuid primary key, secret text)")
	}

	@Test
	fun `secret is stored as ciphertext and read back as plaintext`() {
		val id = UUID.randomUUID()
		transactions.executeWithoutResult {
			entityManager.persist(SecretFixtureEntity(id, EncryptedSecret("oauth-refresh-token-fictitious")))
		}

		val stored = jdbc.queryForObject("select secret from crypto_secret_fixture where id = ?", String::class.java, id)!!
		stored shouldNotContain "oauth-refresh-token"
		cipher.decrypt(stored) shouldBe "oauth-refresh-token-fictitious"

		val loaded = transactions.execute { entityManager.find(SecretFixtureEntity::class.java, id) }!!
		loaded.secret!!.reveal() shouldBe "oauth-refresh-token-fictitious"
	}

	@Test
	fun `null secret stays null`() {
		val id = UUID.randomUUID()
		transactions.executeWithoutResult { entityManager.persist(SecretFixtureEntity(id, null)) }

		jdbc.queryForObject("select secret from crypto_secret_fixture where id = ?", String::class.java, id) shouldBe null
		transactions.execute { entityManager.find(SecretFixtureEntity::class.java, id) }!!.secret shouldBe null
	}

	@Test
	fun `stored secret written with another master key or tampered is not loaded`() {
		val foreignKey = SecretCipher(ByteArray(32) { 42 }).encrypt("oauth-refresh-token-fictitious")
		val tampered =
			Base64.getDecoder().decode(cipher.encrypt("oauth-refresh-token-fictitious")).let { bytes ->
				bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
				Base64.getEncoder().encodeToString(bytes)
			}

		listOf(foreignKey, tampered).forEach { stored ->
			val id = UUID.randomUUID()
			jdbc.update("insert into crypto_secret_fixture (id, secret) values (?, ?)", id, stored)

			val error = shouldThrowAny { transactions.execute { entityManager.find(SecretFixtureEntity::class.java, id) } }
			generateSequence(error) { it.cause }.any { it is SecretDecryptionException } shouldBe true
			generateSequence(error) { it.cause }.forEach { (it.message ?: "") shouldNotContain "oauth-refresh-token" }
		}
	}

	@Test
	fun `application JSON mapper redacts the secret`() {
		jsonMapper.writeValueAsString(mapOf("password" to EncryptedSecret("imap-password-fictitious"))) shouldBe
			"""{"password":"******"}"""
	}
}
