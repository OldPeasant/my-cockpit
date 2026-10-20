package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.mail.model.MailFolderRole
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FolderRoleDetectorTests {
	private fun folder(
		path: String,
		vararg attributes: String,
		delimiter: Char? = '/',
	) = RemoteFolder(path, delimiter?.let { path.substringAfterLast(it) } ?: path, delimiter, attributes.toSet())

	@Test
	fun `SPECIAL-USE attributes decide the roles`() {
		val folders =
			listOf(
				folder("INBOX"),
				folder("Outbox", "\\Sent"),
				folder("Concepts", "\\Drafts"),
				folder("Bin", "\\Trash"),
				folder("Unwanted", "\\Junk"),
				folder("Old", "\\Archive"),
			)

		FolderRoleDetector.assign(folders) shouldContainExactly
			mapOf(
				"INBOX" to MailFolderRole.INBOX,
				"Outbox" to MailFolderRole.SENT,
				"Concepts" to MailFolderRole.DRAFTS,
				"Bin" to MailFolderRole.TRASH,
				"Unwanted" to MailFolderRole.SPAM,
				"Old" to MailFolderRole.ARCHIVE,
			)
	}

	@Test
	fun `Gmail layout - special-use beats names, All Mail archives`() {
		val folders =
			listOf(
				folder("INBOX", "\\HasNoChildren"),
				folder("[Gmail]", "\\Noselect", "\\HasChildren"),
				folder("[Gmail]/All Mail", "\\All", "\\HasNoChildren"),
				folder("[Gmail]/Sent Mail", "\\Sent"),
				folder("[Gmail]/Drafts", "\\Drafts"),
				folder("[Gmail]/Bin", "\\Trash"),
				folder("[Gmail]/Spam", "\\Junk"),
				folder("[Gmail]/Starred", "\\Flagged"),
				// A user label called "Sent" must not win over the \Sent folder.
				folder("Sent"),
			)

		FolderRoleDetector.assign(folders) shouldContainExactly
			mapOf(
				"INBOX" to MailFolderRole.INBOX,
				"[Gmail]/All Mail" to MailFolderRole.ARCHIVE,
				"[Gmail]/Sent Mail" to MailFolderRole.SENT,
				"[Gmail]/Drafts" to MailFolderRole.DRAFTS,
				"[Gmail]/Bin" to MailFolderRole.TRASH,
				"[Gmail]/Spam" to MailFolderRole.SPAM,
			)
	}

	@Test
	fun `an Archive attribute beats All`() {
		val folders = listOf(folder("All", "\\All"), folder("Archive", "\\Archive"))

		FolderRoleDetector.assign(folders)["Archive"] shouldBe MailFolderRole.ARCHIVE
		FolderRoleDetector.assign(folders)["All"] shouldBe null
	}

	@Test
	fun `without SPECIAL-USE well-known names are used, case-insensitively, English and German`() {
		val folders =
			listOf(
				folder("INBOX"),
				folder("Gesendete Elemente"),
				folder("drafts"),
				folder("Papierkorb"),
				folder("Junk E-Mail"),
				folder("Archiv"),
			)

		FolderRoleDetector.assign(folders) shouldContainExactly
			mapOf(
				"INBOX" to MailFolderRole.INBOX,
				"Gesendete Elemente" to MailFolderRole.SENT,
				"drafts" to MailFolderRole.DRAFTS,
				"Papierkorb" to MailFolderRole.TRASH,
				"Junk E-Mail" to MailFolderRole.SPAM,
				"Archiv" to MailFolderRole.ARCHIVE,
			)
	}

	@Test
	fun `Outlook names are recognised`() {
		val folders = listOf(folder("Inbox"), folder("Sent Items"), folder("Deleted Items"), folder("Junk Email"))

		FolderRoleDetector.assign(folders) shouldContainExactly
			mapOf(
				"Inbox" to MailFolderRole.INBOX,
				"Sent Items" to MailFolderRole.SENT,
				"Deleted Items" to MailFolderRole.TRASH,
				"Junk Email" to MailFolderRole.SPAM,
			)
	}

	@Test
	fun `dot-delimited servers - INBOX children match by their last segment`() {
		val folders =
			listOf(
				folder("INBOX", delimiter = '.'),
				folder("INBOX.Sent", delimiter = '.'),
				folder("INBOX.Trash", delimiter = '.'),
			)

		FolderRoleDetector.assign(folders) shouldContainExactly
			mapOf("INBOX" to MailFolderRole.INBOX, "INBOX.Sent" to MailFolderRole.SENT, "INBOX.Trash" to MailFolderRole.TRASH)
	}

	@Test
	fun `each role goes to one folder - top-level first, then by path`() {
		val folders = listOf(folder("Projects/Sent"), folder("Sent Messages"), folder("Sent"))

		FolderRoleDetector.assign(folders) shouldContainExactly mapOf("Sent" to MailFolderRole.SENT)
	}

	@Test
	fun `two folders with the same attribute - the first by depth and path wins`() {
		val folders = listOf(folder("b", "\\Trash"), folder("a", "\\Trash"))

		FolderRoleDetector.assign(folders) shouldContainExactly mapOf("a" to MailFolderRole.TRASH)
	}

	@Test
	fun `a folder gets at most one role`() {
		val folders = listOf(folder("Trash", "\\Junk"))

		FolderRoleDetector.assign(folders) shouldContainExactly mapOf("Trash" to MailFolderRole.SPAM)
	}

	@Test
	fun `non-selectable folders get no role`() {
		val folders = listOf(folder("Sent", "\\Noselect"), folder("Trash", "\\NonExistent", "\\Trash"))

		FolderRoleDetector.assign(folders) shouldContainExactly emptyMap()
	}

	@Test
	fun `an override keeps its role and takes it away from detection`() {
		val folders = listOf(folder("INBOX"), folder("Sent", "\\Sent"), folder("Outgoing"))

		FolderRoleDetector.assign(folders, mapOf("Outgoing" to MailFolderRole.SENT)) shouldContainExactly
			mapOf("INBOX" to MailFolderRole.INBOX, "Outgoing" to MailFolderRole.SENT)
	}

	@Test
	fun `an override without role keeps the folder role-less`() {
		val folders = listOf(folder("INBOX"), folder("Sent", "\\Sent"), folder("Sent Messages"))

		// The owner said "Sent" has no role; detection may still pick the next candidate by name.
		FolderRoleDetector.assign(folders, mapOf("Sent" to null)) shouldContainExactly
			mapOf("INBOX" to MailFolderRole.INBOX, "Sent Messages" to MailFolderRole.SENT)
	}

	@Test
	fun `overrides of vanished folders are ignored`() {
		val folders = listOf(folder("INBOX"), folder("Sent"))

		FolderRoleDetector.assign(folders, mapOf("Old Sent" to MailFolderRole.SENT)) shouldContainExactly
			mapOf("INBOX" to MailFolderRole.INBOX, "Sent" to MailFolderRole.SENT)
	}

	@Test
	fun `two overrides with the same role - the first by path keeps it`() {
		val folders = listOf(folder("A"), folder("B"))

		FolderRoleDetector.assign(folders, mapOf("B" to MailFolderRole.ARCHIVE, "A" to MailFolderRole.ARCHIVE)) shouldContainExactly
			mapOf("A" to MailFolderRole.ARCHIVE)
	}

	@Test
	fun `flat namespaces have depth 0`() {
		folder("Sent", delimiter = null).depth shouldBe 0
		folder("a/b/c").depth shouldBe 2
	}
}
