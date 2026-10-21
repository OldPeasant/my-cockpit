package ch.sonensei.cockpit.mail.testsupport

import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.sync.FolderSyncResult
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import java.util.UUID

/** The e2e hook `POST /api/test/mail/sync`. */
class MailTestSupportControllerTests {
	private val accounts = mockk<MailAccountService>()
	private val folders = mockk<MailFolderService>()
	private val headers = mockk<MailHeaderSyncService>()
	private val controller = MailTestSupportController(accounts, folders, headers)

	@Test
	fun `sync reads the folder list, then the headers of every account`() {
		val first = account()
		val second = account()
		every { accounts.list() } returns listOf(first, second)
		every { folders.sync(any()) } returns emptyList()
		every { headers.syncAccount(first.id) } returns listOf(result(2), result(1))
		every { headers.syncAccount(second.id) } returns listOf(result(4))

		controller.sync() shouldBe MailSyncResponse(accounts = 2, created = 7)

		verifyOrder {
			folders.sync(first.id)
			headers.syncAccount(first.id)
			folders.sync(second.id)
			headers.syncAccount(second.id)
		}
	}

	@Test
	fun `sync without accounts does nothing`() {
		every { accounts.list() } returns emptyList()

		controller.sync() shouldBe MailSyncResponse(accounts = 0, created = 0)
	}

	@Nested
	@SpringBootTest
	inner class DefaultProfile(
		@Autowired private val context: ApplicationContext,
	) : PostgresTestBase() {
		@Test
		fun `the hook does not exist outside the e2e profile`() {
			context.getBeanNamesForType(MailTestSupportController::class.java).toList().shouldBeEmpty()
		}
	}

	private fun account() = mockk<MailAccount> { every { id } returns UUID.randomUUID() }

	private fun result(created: Int) = FolderSyncResult(UUID.randomUUID(), full = true, created = created, updated = 0, removed = 0)
}
