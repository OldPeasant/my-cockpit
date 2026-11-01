package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import ch.sonensei.cockpit.mail.sync.MailSyncRepository
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.util.Properties
import java.util.UUID

/**
 * Folder management (create, rename, delete) through the API against GreenMail, whose hierarchy delimiter is `.`:
 * the folders change on the server and in the cache, ids of folders and messages survive a rename, messages of
 * deleted folders stay as link targets, and the refused cases answer with Problem Details.
 */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@DirtiesContext
@RecordApplicationEvents
class MailFolderManageGreenMailTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folderService: MailFolderService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
	@Autowired private val syncMessages: MailSyncRepository,
	@Autowired private val headerSync: MailHeaderSyncService,
	@Autowired private val links: LinkService,
) : PostgresTestBase() {
	@Autowired
	private lateinit var events: ApplicationEvents

	private lateinit var account: MailAccount

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from link")
		jdbc.update("delete from mail_account")
		greenMail.setUser(EMAIL, USER, PASSWORD)
		account = account(greenMail.imap.port)
	}

	private fun account(imapPort: Int): MailAccount =
		accounts.create(
			NewMailAccount(
				type = MailAccountType.IMAP_SMTP,
				displayName = "GreenMail",
				emailAddress = EMAIL,
				imap = MailServer(HOST, imapPort, MailSecurity.NONE),
				smtp = MailServer(HOST, greenMail.smtp.port, MailSecurity.NONE),
				username = USER,
				password = PASSWORD,
				oauthConnectionId = null,
				signatureMd = null,
				notifyNewMail = null,
				syncWindowMonths = null,
			),
		)

	private fun <T> remote(block: (Store) -> T): T =
		Session.getInstance(Properties()).getStore("imap").use { store ->
			store.connect(HOST, greenMail.imap.port, USER, PASSWORD)
			block(store)
		}

	private fun remoteFolders(): List<String> = remote { store -> store.defaultFolder.list("*").map { it.fullName } }

	private fun remoteSubscribed(): List<String> = remote { store -> store.defaultFolder.listSubscribed("*").map { it.fullName } }

	private fun createRemote(vararg paths: String) =
		remote { store -> paths.forEach { store.getFolder(it).create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS) shouldBe true } }

	private fun append(
		path: String,
		vararg messageIds: String,
	) = remote { store ->
		store.getFolder(path).appendMessages(
			messageIds
				.map { id ->
					object : MimeMessage(Session.getInstance(Properties())) {
						override fun updateMessageID() = Unit
					}.apply {
						setHeader("Message-ID", id)
						setFrom(InternetAddress("ann.example@example.test"))
						setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
						subject = "Fictitious $id"
						setText("Hello from the fictitious test.", "UTF-8")
						saveChanges()
					}
				}.toTypedArray(),
		)
	}

	private fun cached(path: String): MailFolder? = folders.findByAccountIdAndPath(account.id, path)

	private fun base(accountId: UUID = account.id) = "${MailAccountController.BASE}/$accountId/folders"

	private fun body(result: ResultActionsDsl): JsonNode = json.readTree(result.andReturn().response.contentAsString)

	private fun createViaApi(
		name: String,
		parentId: UUID? = null,
		accountId: UUID = account.id,
	) = mockMvc.post(base(accountId)) {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = json.writeValueAsString(mapOf("name" to name, "parentId" to parentId))
	}

	private fun renameViaApi(
		folder: MailFolder,
		name: String,
		ifMatch: String? = "\"${folder.version}\"",
	) = mockMvc.patch("${base()}/${folder.id}") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = json.writeValueAsString(mapOf("name" to name))
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun deleteViaApi(
		folder: MailFolder,
		ifMatch: String? = "\"${folder.version}\"",
	) = mockMvc.delete("${base()}/${folder.id}") {
		with(csrf())
		ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
	}

	private fun folderEvents() =
		events
			.stream(ChangeEvent::class.java)
			.toList()
			.filter { it.type == MailFolderService.FOLDER_EVENT }
			.map { it.id to it.op }

	private fun problem(
		result: ResultActionsDsl,
		status: Int,
	): JsonNode {
		result.andExpect {
			status { isEqualTo(status) }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		return body(result)
	}

	@Test
	fun `create makes a subscribed top-level folder and caches it`() {
		folderService.sync(account.id)
		events.clear()

		val result = createViaApi("Projects")

		result.andExpect {
			status { isCreated() }
			header { string(HttpHeaders.ETAG, "\"0\"") }
		}
		val created = body(result)
		created["path"].asString() shouldBe "Projects"
		created["name"].asString() shouldBe "Projects"
		created["delimiter"].asString() shouldBe "."
		created["selectable"].asBoolean() shouldBe true
		remoteFolders() shouldContain "Projects"
		remoteSubscribed() shouldContain "Projects"
		val cachedFolder = cached("Projects").shouldNotBeNull()
		cachedFolder.id.toString() shouldBe created["id"].asString()
		folderEvents() shouldBe listOf(cachedFolder.id to ChangeOp.CREATED)
		// The next sync finds the same folder, not a second one.
		folderService.sync(account.id).count { it.path == "Projects" } shouldBe 1
		cached("Projects")?.id shouldBe cachedFolder.id
	}

	@Test
	fun `create nests a folder under its parent with the server's delimiter, also before any sync`() {
		val parent = body(createViaApi("Clients").andExpect { status { isCreated() } })

		val child = body(createViaApi("Acme Corp", UUID.fromString(parent["id"].asString())).andExpect { status { isCreated() } })

		child["path"].asString() shouldBe "Clients.Acme Corp"
		child["name"].asString() shouldBe "Acme Corp"
		remoteFolders() shouldContainAll listOf("Clients", "Clients.Acme Corp")
		remoteSubscribed() shouldContain "Clients.Acme Corp"
		cached("Clients.Acme Corp").shouldNotBeNull()
	}

	@Test
	fun `create refuses empty names, the delimiter, taken names and unknown accounts or parents`() {
		createRemote("Projects")
		folderService.sync(account.id)
		events.clear()

		problem(createViaApi("   "), 400)["errors"][0]["field"].asString() shouldBe "name"
		problem(createViaApi("a.b"), 400)["errors"][0]["field"].asString() shouldBe "name"
		problem(createViaApi("Projects"), 409)
		problem(createViaApi("inbox"), 409)
		problem(createViaApi("X", accountId = UUID.randomUUID()), 404)
		problem(createViaApi("X", parentId = UUID.randomUUID()), 404)

		remoteFolders() shouldNotContain "a.b"
		remoteFolders() shouldNotContain "a"
		folderEvents() shouldBe emptyList()
	}

	@Test
	fun `create of a folder that exists only on the server is a conflict`() {
		folderService.sync(account.id)
		createRemote("Elsewhere")

		problem(createViaApi("Elsewhere"), 409)
	}

	@Test
	fun `rename moves the folder and its subfolders on the server and in the cache and keeps all ids`() {
		createRemote("Projects", "Projects.Alpha", "Projects.Alpha.Notes", "Projectsx")
		append("Projects", "<p1@example.test>")
		append("Projects.Alpha", "<a1@example.test>")
		folderService.sync(account.id)
		headerSync.syncFolder(cached("Projects")!!.id)
		headerSync.syncFolder(cached("Projects.Alpha")!!.id)
		val projects = cached("Projects")!!
		val alpha = cached("Projects.Alpha")!!
		val notes = cached("Projects.Alpha.Notes")!!
		val other = cached("Projectsx")!!
		val p1 = syncMessages.findByFolderIdAndUidIsNotNull(projects.id).single()
		val a1 = syncMessages.findByFolderIdAndUidIsNotNull(alpha.id).single()
		links.create(ItemRef(ItemType.EMAIL, p1.id), ItemRef(ItemType.EMAIL, a1.id), null)
		events.clear()

		val result = renameViaApi(projects, "Archive 2026").andExpect { status { isOk() } }

		body(result).let {
			it["id"].asString() shouldBe projects.id.toString()
			it["path"].asString() shouldBe "Archive 2026"
			it["name"].asString() shouldBe "Archive 2026"
		}
		result.andExpect { header { string(HttpHeaders.ETAG, "\"${projects.version + 1}\"") } }
		remoteFolders() shouldContainAll listOf("Archive 2026", "Archive 2026.Alpha", "Archive 2026.Alpha.Notes", "Projectsx")
		remoteFolders() shouldNotContain "Projects"
		remoteSubscribed() shouldContain "Archive 2026"
		folders.findById(projects.id).orElseThrow().path shouldBe "Archive 2026"
		folders.findById(alpha.id).orElseThrow().let {
			it.path shouldBe "Archive 2026.Alpha"
			it.name shouldBe "Alpha"
		}
		folders.findById(notes.id).orElseThrow().path shouldBe "Archive 2026.Alpha.Notes"
		folders.findById(other.id).orElseThrow().path shouldBe "Projectsx"
		messages.findById(p1.id).orElseThrow().folderId shouldBe projects.id
		messages.findById(a1.id).orElseThrow().folderId shouldBe alpha.id
		links.linksOf(ItemRef(ItemType.EMAIL, p1.id)).size shouldBe 1
		folderEvents() shouldContainExactlyInAnyOrder
			listOf(projects.id to ChangeOp.UPDATED, alpha.id to ChangeOp.UPDATED, notes.id to ChangeOp.UPDATED)

		// The next folder sync keeps the same rows; the message sync still finds the messages under the new path.
		folderService.sync(account.id)
		cached("Archive 2026")?.id shouldBe projects.id
		cached("Archive 2026.Alpha")?.id shouldBe alpha.id
		headerSync.syncFolder(projects.id)
		headerSync.syncFolder(alpha.id)
		syncMessages.findByFolderIdAndUidIsNotNull(alpha.id).map { it.messageIdHeader } shouldBe listOf("<a1@example.test>")
		messages.findById(p1.id).orElseThrow().let {
			it.isLocated shouldBe true
			it.folderId shouldBe projects.id
		}
	}

	@Test
	fun `rename of a nested folder keeps its parent`() {
		createRemote("Clients", "Clients.Acme")
		folderService.sync(account.id)

		renameViaApi(cached("Clients.Acme")!!, "Acme Corp").andExpect { status { isOk() } }

		remoteFolders() shouldContain "Clients.Acme Corp"
		cached("Clients.Acme Corp").shouldNotBeNull().name shouldBe "Acme Corp"
	}

	@Test
	fun `rename keeps the role and a body with name and role changes both`() {
		createRemote("Old Archive")
		folderService.sync(account.id)
		val folder = cached("Old Archive")!!
		folderService.overrideRole(account.id, folder.id, MailFolderRole.ARCHIVE, automatic = false, ifMatch = "\"${folder.version}\"")
		val withRole = folders.findById(folder.id).orElseThrow()

		renameViaApi(withRole, "Archive").andExpect { status { isOk() } }
		folders.findById(folder.id).orElseThrow().role shouldBe MailFolderRole.ARCHIVE

		val renamed = folders.findById(folder.id).orElseThrow()
		mockMvc
			.patch("${base()}/${folder.id}") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"name": "Kept", "role": null}"""
				header(HttpHeaders.IF_MATCH, "\"${renamed.version}\"")
			}.andExpect { status { isOk() } }
		folders.findById(folder.id).orElseThrow().let {
			it.path shouldBe "Kept"
			it.role.shouldBeNull()
			it.roleOverridden shouldBe true
		}
	}

	@Test
	fun `rename refuses INBOX, taken names, the delimiter, empty names and bad If-Match`() {
		createRemote("Projects", "Done")
		folderService.sync(account.id)
		val inbox = cached("INBOX")!!
		val projects = cached("Projects")!!
		events.clear()

		problem(renameViaApi(inbox, "Mail"), 409)
		problem(renameViaApi(projects, "Done"), 409)
		problem(renameViaApi(projects, "a.b"), 400)
		problem(renameViaApi(projects, ""), 400)
		problem(renameViaApi(projects, "New", ifMatch = "\"99\""), 412)
		problem(renameViaApi(projects, "New", ifMatch = null), 428)
		problem(
			mockMvc.patch("${base()}/${UUID.randomUUID()}") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"name": "New"}"""
				header(HttpHeaders.IF_MATCH, "\"0\"")
			},
			404,
		)

		remoteFolders() shouldContainAll listOf("INBOX", "Projects", "Done")
		remoteFolders() shouldNotContain "New"
		folderEvents() shouldBe emptyList()
	}

	@Test
	fun `a rename the server rejects answers 502 SERVER_REJECTED and leaves the cache alone`() {
		createRemote("Gone")
		folderService.sync(account.id)
		val gone = cached("Gone")!!
		remote { store -> store.getFolder("Gone").delete(true) shouldBe true }

		problem(renameViaApi(gone, "Back"), 502)["errorCode"].asString() shouldBe "SERVER_REJECTED"

		folders.findById(gone.id).orElseThrow().path shouldBe "Gone"
	}

	@Test
	fun `delete removes the folder and its subfolders and keeps their messages as link targets`() {
		createRemote("Old", "Old.Sub", "Oldx")
		append("Old", "<o1@example.test>", "<o2@example.test>")
		append("Old.Sub", "<s1@example.test>")
		append("INBOX", "<i1@example.test>")
		folderService.sync(account.id)
		listOf("Old", "Old.Sub", "INBOX").forEach { headerSync.syncFolder(cached(it)!!.id) }
		val old = cached("Old")!!
		val sub = cached("Old.Sub")!!
		val inbox = cached("INBOX")!!
		val (o1, o2) = syncMessages.findByFolderIdAndUidIsNotNull(old.id).sortedBy { it.uid }
		val s1 = syncMessages.findByFolderIdAndUidIsNotNull(sub.id).single()
		val i1 = syncMessages.findByFolderIdAndUidIsNotNull(inbox.id).single()
		links.create(ItemRef(ItemType.EMAIL, i1.id), ItemRef(ItemType.EMAIL, o1.id), null)
		events.clear()

		deleteViaApi(old).andExpect { status { isNoContent() } }

		remoteFolders() shouldNotContain "Old"
		remoteFolders() shouldNotContain "Old.Sub"
		remoteFolders() shouldContain "Oldx"
		cached("Old").shouldBeNull()
		cached("Old.Sub").shouldBeNull()
		cached("Oldx").shouldNotBeNull()
		listOf(o1, o2, s1).forEach { message ->
			messages.findById(message.id).orElseThrow().let {
				it.folderId.shouldBeNull()
				it.isLocated shouldBe false
			}
		}
		links.linksOf(ItemRef(ItemType.EMAIL, i1.id)).single().target.let {
			// Kept as a link target; it resolves again once the message turns up in another folder (by Message-ID).
			it.ref.id shouldBe o1.id
		}
		folderEvents() shouldContainExactlyInAnyOrder listOf(old.id to ChangeOp.DELETED, sub.id to ChangeOp.DELETED)
	}

	@Test
	fun `delete of a folder already gone on the server only uncaches it`() {
		createRemote("Gone")
		folderService.sync(account.id)
		val gone = cached("Gone")!!
		remote { store -> store.getFolder("Gone").delete(true) shouldBe true }

		deleteViaApi(gone).andExpect { status { isNoContent() } }

		cached("Gone").shouldBeNull()
	}

	@Test
	fun `delete refuses INBOX, role folders, folders holding one, and bad If-Match`() {
		createRemote("Trash", "Parent", "Parent.Sent", "Projects")
		folderService.sync(account.id)
		val trash = cached("Trash")!!
		trash.role shouldBe MailFolderRole.TRASH
		val parent = cached("Parent")!!
		val projects = cached("Projects")!!
		folderService.overrideRole(account.id, cached("Parent.Sent")!!.id, MailFolderRole.SENT, false, "\"0\"")
		events.clear()

		problem(deleteViaApi(cached("INBOX")!!), 409)
		problem(deleteViaApi(trash), 409)
		problem(deleteViaApi(parent), 409)
		problem(deleteViaApi(projects, ifMatch = "\"7\""), 412)
		problem(deleteViaApi(projects, ifMatch = null), 428)
		problem(
			mockMvc.delete("${base()}/${UUID.randomUUID()}") {
				with(csrf())
				header(HttpHeaders.IF_MATCH, "\"0\"")
			},
			404,
		)

		remoteFolders() shouldContainAll listOf("INBOX", "Trash", "Parent", "Parent.Sent", "Projects")
		folderEvents() shouldBe emptyList()
	}

	@Test
	fun `an unreachable server answers 502 with errorCode`() {
		val closedPort = ServerSocket(0).use { it.localPort }
		val offline = account(closedPort)

		problem(createViaApi("Projects", accountId = offline.id), 502)["errorCode"].asString() shouldBe "CONNECTION_FAILED"
	}

	companion object {
		private const val HOST = "localhost"
		private const val EMAIL = "jane.doe@example.test"
		private const val USER = "jane"
		private const val PASSWORD = "fictitious-password"

		@JvmField
		@RegisterExtension
		val greenMail: GreenMailExtension =
			GreenMailExtension(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP).map { it.dynamicPort() }.toTypedArray())
	}
}
