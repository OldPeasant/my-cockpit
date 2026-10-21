package ch.sonensei.cockpit.mail.testsupport

import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import io.swagger.v3.oas.annotations.Hidden
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * End-to-end test hook of the mail module (profile `e2e` only, not part of the API contract, public like the other
 * `/api/test/` hooks of `core.testsupport`): `POST /api/test/mail/sync` reads the folder list and then the headers of
 * every folder of every account, so a spec sees mail it just delivered without waiting for a scheduled sync.
 */
@RestController
@Profile("e2e")
@Hidden
@RequestMapping("/api/test/mail")
class MailTestSupportController(
	private val accounts: MailAccountService,
	private val folders: MailFolderService,
	private val headers: MailHeaderSyncService,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@PostMapping("/sync")
	fun sync(): MailSyncResponse {
		var created = 0
		val synced = accounts.list()
		for (account in synced) {
			folders.sync(account.id)
			created += headers.syncAccount(account.id).sumOf { it.created }
		}
		log.info("Test mail sync: {} accounts, {} new messages", synced.size, created)
		return MailSyncResponse(synced.size, created)
	}
}

data class MailSyncResponse(
	val accounts: Int,
	val created: Int,
)
