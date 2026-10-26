package ch.sonensei.cockpit.contacts.carddav

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

/**
 * [CardDavAccountService] without Spring: which URLs and names an account may have (absolute http(s) URL without
 * credentials in it, non-blank name), how values are normalized, and removal. Names and credentials are fictitious.
 */
class CardDavAccountServiceTests {
	private val accounts = mockk<CardDavAccountRepository>()
	private val service = CardDavAccountService(accounts)

	init {
		every { accounts.saveAndFlush(any<CardDavAccount>()) } answers { firstArg() }
	}

	@Test
	fun `an account is stored with a trimmed name and URL and the password as a secret`() {
		val account = service.add("  Home server ", " https://dav.example.test/dav/ ", "alice", "fictitious-pw")

		account.name shouldBe "Home server"
		account.url shouldBe "https://dav.example.test/dav/"
		account.username shouldBe "alice"
		account.password shouldBe EncryptedSecret("fictitious-pw")
		account.addressbookUrl.shouldBeNull()
		account.syncToken.shouldBeNull()
		verify(exactly = 1) { accounts.saveAndFlush(account) }
	}

	@Test
	fun `http and upper-case schemes are accepted, a blank user name and no password are stored as none`() {
		val account = service.add("Local", "HTTP://localhost:5232/", " ", null)

		account.url shouldBe "HTTP://localhost:5232/"
		account.username.shouldBeNull()
		account.password.shouldBeNull()
	}

	@Test
	fun `a blank name is refused`() {
		listOf("", "   ").forEach { name ->
			val e = shouldThrow<InvalidRequestException> { service.add(name, "https://dav.example.test/", null, null) }
			e.fieldErrors shouldContainExactly listOf(FieldError("name", "must not be blank"))
		}
		verify(exactly = 0) { accounts.saveAndFlush(any<CardDavAccount>()) }
	}

	@Test
	fun `URLs that are not absolute http(s) URLs, or that carry credentials, are refused`() {
		listOf(
			"",
			"dav.example.test/dav/",
			"/dav/",
			"ftp://dav.example.test/",
			"file:///etc/passwd",
			"javascript:alert(1)",
			"https:///dav/",
			"https://alice:secret@dav.example.test/",
			"https://dav.example.test/a b",
			"http://[bad/",
		).forEach { url ->
			val e = shouldThrow<InvalidRequestException> { service.add("Server", url, null, null) }
			e.fieldErrors shouldContainExactly listOf(FieldError("url", "must be an http or https URL without credentials"))
		}
		verify(exactly = 0) { accounts.saveAndFlush(any<CardDavAccount>()) }
	}

	@Test
	fun `removing an account deletes it, an unknown one is not found`() {
		val account = CardDavAccount("Server", "https://dav.example.test/", null, null)
		val unknown = UUID.fromString("0192a5f0-0000-7000-8000-0000000000c1")
		every { accounts.findById(account.id) } returns Optional.of(account)
		every { accounts.findById(unknown) } returns Optional.empty()
		every { accounts.delete(account) } returns Unit

		service.remove(account.id)

		verify(exactly = 1) { accounts.delete(account) }
		shouldThrow<NotFoundException> { service.remove(unknown) }
	}
}
