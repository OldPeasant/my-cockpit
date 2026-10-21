package ch.sonensei.cockpit.mail.search

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailBody
import ch.sonensei.cockpit.mail.model.MailBodyRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import java.util.UUID

/** The search document [MailSearchIndex] builds for a cached message (title, body text, recency). */
class MailSearchIndexTests {
	private val indexer = mockk<SearchIndexer>(relaxed = true)
	private val bodies = mockk<MailBodyRepository>()
	private val index =
		MailSearchIndex(indexer, bodies, mockk<MailMessageRepository>(), mockk<NamedParameterJdbcTemplate>())

	private val accountId = UUID.fromString("01900000-0000-7000-8000-000000000001")
	private val folderId = UUID.fromString("01900000-0000-7000-8000-000000000002")
	private val received = Instant.parse("2025-03-01T08:30:00Z")

	private fun message(
		subject: String? = "Quarterly report",
		snippet: String? = "Short preview",
	) = MailMessage(accountId, folderId, 7).apply {
		this.subject = subject
		this.snippet = snippet
		from = listOf(MailAddress("ann.example@example.test", "Ann Example"))
		to = listOf(MailAddress("jane.doe@work.example"))
		cc = listOf(MailAddress("carl.cc@example.test", "Carl Copy"))
		bcc = listOf(MailAddress("bea.bcc@example.test"))
		receivedAt = received
	}

	/** The (title, body) the indexer got for [message]; asserts the type, id and recency. */
	private fun indexed(message: MailMessage): Pair<String, String> {
		val title = slot<String>()
		val body = slot<String>()
		verify { indexer.upsert(ItemType.EMAIL, message.id, capture(title), capture(body), received) }
		return title.captured to body.captured
	}

	@Test
	fun `title is the subject, body the names and addresses (also split into words) then the plain text`() {
		val message = message()

		index.index(message, "The figures are attached.", "<p>ignored HTML</p>")

		val (title, body) = indexed(message)
		title shouldBe "Quarterly report"
		body shouldBe
			"Ann Example ann.example@example.test ann example example test " +
			"jane.doe@work.example jane doe work example " +
			"Carl Copy carl.cc@example.test carl cc example test " +
			"bea.bcc@example.test bea bcc example test\n" +
			"The figures are attached."
	}

	@Test
	fun `a blank plain part falls back to the text of the HTML part, without markup or scripts`() {
		val message = message()

		index.index(message, "  \n", "<p>Hello <b>Bern</b></p><script>track()</script>")

		indexed(message).second.substringAfter("\n") shouldBe "Hello Bern"
	}

	@Test
	fun `without a body the snippet is indexed, and a message without subject or snippet gets empty text`() {
		val withSnippet = message()
		val bare = message(subject = null, snippet = null)

		index.index(withSnippet, null, " ")
		index.index(bare, null, null)

		indexed(withSnippet).second.substringAfter("\n") shouldBe "Short preview"
		val (title, body) = indexed(bare)
		title shouldBe ""
		body.substringAfter("\n") shouldBe ""
	}

	@Test
	fun `indexing without a body uses the cached body when there is one`() {
		val cached = message()
		val uncached = message()
		every { bodies.findByMessageId(cached.id) } returns MailBody(cached.id, "Cached kangaroo text", null, received)
		every { bodies.findByMessageId(uncached.id) } returns null

		index.index(cached)
		index.index(uncached)

		indexed(cached).second.substringAfter("\n") shouldBe "Cached kangaroo text"
		indexed(uncached).second.substringAfter("\n") shouldBe "Short preview"
	}

	@Test
	fun `a message without recipients or sender only has its text`() {
		val message = MailMessage(accountId, folderId, 8).apply { receivedAt = received }

		index.index(message, "Lonely text", null)

		indexed(message).second shouldBe "\nLonely text"
	}

	@Test
	fun `a detached message is removed from the index instead of indexed`() {
		val message = message()
		message.detach()

		index.index(message, "Text", null)

		verify { indexer.delete(ItemType.EMAIL, message.id) }
		verify(exactly = 0) { indexer.upsert(any(), any(), any(), any(), any()) }
	}

	@Test
	fun `a message without arrival date is indexed with the indexer's own recency`() {
		val message = MailMessage(accountId, folderId, 9).apply { subject = "Undated" }

		index.index(message, null, null)

		verify { indexer.upsert(ItemType.EMAIL, message.id, "Undated", "\n", null) }
	}
}
