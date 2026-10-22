package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailMessage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Recipients, subject, threading headers and quoting of replies and forwards ([ReplyComposer]), without I/O. */
class ReplyComposerTests {
	private val own = setOf(ME, ALIAS)

	private fun message(
		from: List<MailAddress> = listOf(BOB),
		to: List<MailAddress> = listOf(MailAddress(ME, "Jane Doe")),
		cc: List<MailAddress> = emptyList(),
		replyTo: List<MailAddress> = emptyList(),
		subject: String? = "Project status",
		messageId: String? = "<status-1@example.test>",
		references: String? = null,
		sentAt: Instant? = Instant.parse("2026-03-05T14:07:00Z"),
	) =
		MailMessage(UUID.fromString("01900000-0000-7000-8000-0000000000a1"), UUID.fromString("01900000-0000-7000-8000-0000000000f1"), 1).apply {
			this.from = from
			this.to = to
			this.cc = cc
			this.replyTo = replyTo
			this.subject = subject
			this.messageIdHeader = messageId
			this.referencesHeader = references
			this.sentAt = sentAt
		}

	private fun compose(
		mode: ResponseMode,
		original: MailMessage,
		quotedHtml: String = "<p>All on track.</p>",
		quotedText: String = "All on track.\nNext review on Friday.\n",
	) = ReplyComposer.compose(mode, original, quotedHtml, quotedText, own)

	private fun List<MailAddress>.addresses() = map { it.address }

	@Nested
	inner class Recipients {
		@Test
		fun `a reply goes to the sender only`() {
			val draft = compose(ResponseMode.REPLY, message(to = listOf(MailAddress(ME), CAROL), cc = listOf(DAVE)))

			draft.to.addresses() shouldContainExactly listOf(BOB.address)
			draft.cc.shouldBeEmpty()
		}

		@Test
		fun `a reply goes to Reply-To instead of From`() {
			val list = MailAddress("list@example.test", "Project list")
			val draft = compose(ResponseMode.REPLY, message(replyTo = listOf(list)))

			draft.to shouldContainExactly listOf(list)
		}

		@Test
		fun `a reply to one's own sent mail goes to its recipients`() {
			val draft = compose(ResponseMode.REPLY, message(from = listOf(MailAddress(ME)), to = listOf(BOB, CAROL)))

			draft.to.addresses() shouldContainExactly listOf(BOB.address, CAROL.address)
		}

		@Test
		fun `own addresses are recognized regardless of case`() {
			val draft = compose(ResponseMode.REPLY, message(from = listOf(MailAddress("Jane.Doe@Example.TEST")), to = listOf(BOB)))

			draft.to.addresses() shouldContainExactly listOf(BOB.address)
		}

		@Test
		fun `a reply to mail sent from an identity goes to its recipients`() {
			val draft = compose(ResponseMode.REPLY, message(from = listOf(MailAddress(ALIAS)), to = listOf(CAROL)))

			draft.to.addresses() shouldContainExactly listOf(CAROL.address)
		}

		@Test
		fun `a Reply-To that is one's own address counts as sent by oneself`() {
			val draft = compose(ResponseMode.REPLY, message(from = listOf(BOB), replyTo = listOf(MailAddress(ME)), to = listOf(CAROL)))

			draft.to.addresses() shouldContainExactly listOf(CAROL.address)
		}

		@Test
		fun `reply all adds the other To recipients and keeps Cc, without own addresses`() {
			val draft =
				compose(
					ResponseMode.REPLY_ALL,
					message(to = listOf(MailAddress(ME), CAROL), cc = listOf(DAVE, MailAddress(ALIAS))),
				)

			draft.to.addresses() shouldContainExactly listOf(BOB.address, CAROL.address)
			draft.cc.addresses() shouldContainExactly listOf(DAVE.address)
		}

		@Test
		fun `reply all drops duplicates case-insensitively, keeping the first spelling`() {
			val draft =
				compose(
					ResponseMode.REPLY_ALL,
					message(
						to = listOf(MailAddress("Carol@Example.test", "Carol"), CAROL, MailAddress("BOB@example.test")),
						cc = listOf(MailAddress("carol@EXAMPLE.test"), DAVE, MailAddress("dave@example.TEST"), MailAddress("bob@example.test")),
					),
				)

			draft.to.addresses() shouldContainExactly listOf(BOB.address, "Carol@Example.test")
			draft.cc.addresses() shouldContainExactly listOf(DAVE.address)
		}

		@Test
		fun `reply all to one's own sent mail goes to all its recipients`() {
			val draft =
				compose(ResponseMode.REPLY_ALL, message(from = listOf(MailAddress(ME)), to = listOf(BOB, MailAddress(ME)), cc = listOf(CAROL)))

			draft.to.addresses() shouldContainExactly listOf(BOB.address)
			draft.cc.addresses() shouldContainExactly listOf(CAROL.address)
		}

		@Test
		fun `a mail to oneself is answered to oneself`() {
			val draft = compose(ResponseMode.REPLY_ALL, message(from = listOf(MailAddress(ME)), to = listOf(MailAddress(ME))))

			draft.to.addresses() shouldContainExactly listOf(ME)
			draft.cc.shouldBeEmpty()
		}

		@Test
		fun `a message without sender gets a reply without recipients`() {
			val draft = compose(ResponseMode.REPLY, message(from = emptyList(), to = listOf(CAROL)))

			draft.to.shouldBeEmpty()
		}

		@Test
		fun `a forward has no recipients`() {
			val draft = compose(ResponseMode.FORWARD, message(to = listOf(CAROL), cc = listOf(DAVE)))

			draft.to.shouldBeEmpty()
			draft.cc.shouldBeEmpty()
		}
	}

	@Nested
	inner class Subject {
		private fun reply(subject: String?) = compose(ResponseMode.REPLY, message(subject = subject)).subject

		private fun forward(subject: String?) = compose(ResponseMode.FORWARD, message(subject = subject)).subject

		@Test
		fun `a reply gets Re, unless it already has a reply prefix`() {
			reply("Project status") shouldBe "Re: Project status"
			reply("Re: Project status") shouldBe "Re: Project status"
			reply("RE:Project status") shouldBe "RE:Project status"
			reply("AW: Projektstand") shouldBe "AW: Projektstand"
			reply("SV: Projektstatus") shouldBe "SV: Projektstatus"
		}

		@Test
		fun `a reply to a forward gets Re in front of Fwd`() {
			reply("Fwd: Project status") shouldBe "Re: Fwd: Project status"
		}

		@Test
		fun `the subject is trimmed, an empty one becomes a bare prefix`() {
			reply("  Project status  ") shouldBe "Re: Project status"
			reply(null) shouldBe "Re:"
			forward("") shouldBe "Fwd:"
		}

		@Test
		fun `a forward gets Fwd, unless it already has a forward prefix`() {
			forward("Project status") shouldBe "Fwd: Project status"
			forward("Fwd: Project status") shouldBe "Fwd: Project status"
			forward("FW: Project status") shouldBe "FW: Project status"
			forward("WG: Projektstand") shouldBe "WG: Projektstand"
			forward("Re: Project status") shouldBe "Fwd: Re: Project status"
		}

		@Test
		fun `a word that only starts like a prefix is no prefix`() {
			reply("Report: Q3") shouldBe "Re: Report: Q3"
			forward("Fwdx things") shouldBe "Fwd: Fwdx things"
		}
	}

	@Nested
	inner class Threading {
		@Test
		fun `a reply answers the original's Message-ID and appends it to References`() {
			val draft = compose(ResponseMode.REPLY, message(references = "<root@example.test> <second@example.test>"))

			draft.inReplyTo shouldBe "<status-1@example.test>"
			draft.references shouldBe "<root@example.test> <second@example.test> <status-1@example.test>"
		}

		@Test
		fun `without References the chain starts with the original`() {
			compose(ResponseMode.REPLY_ALL, message()).references shouldBe "<status-1@example.test>"
		}

		@Test
		fun `folded or messy References are normalized to single-spaced ids`() {
			val draft = compose(ResponseMode.REPLY, message(references = "<root@example.test>\r\n\t<second@example.test>  junk"))

			draft.references shouldBe "<root@example.test> <second@example.test> <status-1@example.test>"
		}

		@Test
		fun `the original's id is not repeated when References already ends with it`() {
			val draft = compose(ResponseMode.REPLY, message(references = "<root@example.test> <status-1@example.test>"))

			draft.references shouldBe "<root@example.test> <status-1@example.test>"
		}

		@Test
		fun `a long chain keeps the root and the most recent ids`() {
			val chain = (1..30).joinToString(" ") { "<m$it@example.test>" }
			val draft = compose(ResponseMode.REPLY, message(references = chain))

			val ids = draft.references.shouldNotBeNull().split(" ")
			ids shouldHaveSize ReplyComposer.MAX_REFERENCES
			ids.first() shouldBe "<m1@example.test>"
			ids.last() shouldBe "<status-1@example.test>"
			ids[1] shouldBe "<m13@example.test>"
		}

		@Test
		fun `a chain exactly at the cap is kept whole`() {
			val chain = (1 until ReplyComposer.MAX_REFERENCES).joinToString(" ") { "<m$it@example.test>" }
			val ids = compose(ResponseMode.REPLY, message(references = chain)).references.shouldNotBeNull().split(" ")

			ids shouldHaveSize ReplyComposer.MAX_REFERENCES
			ids[1] shouldBe "<m2@example.test>"
		}

		@Test
		fun `an original without Message-ID gives no In-Reply-To and keeps its References`() {
			val draft = compose(ResponseMode.REPLY, message(messageId = null, references = "<root@example.test>"))

			draft.inReplyTo.shouldBeNull()
			draft.references shouldBe "<root@example.test>"
		}

		@Test
		fun `a forward has no threading headers`() {
			val draft = compose(ResponseMode.FORWARD, message(references = "<root@example.test>"))

			draft.inReplyTo.shouldBeNull()
			draft.references.shouldBeNull()
		}
	}

	@Nested
	inner class Body {
		@Test
		fun `a reply starts with an empty paragraph, then the attribution and the quoted original`() {
			val draft = compose(ResponseMode.REPLY, message())

			draft.html shouldBe
				"<p></p><p>On Thu, 5 Mar 2026 at 14:07 UTC, Bob Example &lt;bob@example.test&gt; wrote:</p>" +
				"<blockquote type=\"cite\"><p>All on track.</p></blockquote>"
			draft.text shouldBe
				"\n\nOn Thu, 5 Mar 2026 at 14:07 UTC, Bob Example <bob@example.test> wrote:\n> All on track.\n> Next review on Friday.\n"
		}

		@Test
		fun `the attribution escapes the sender's name`() {
			val draft = compose(ResponseMode.REPLY, message(from = listOf(MailAddress("eve@example.test", "<img src=x onerror=alert(1)>"))))

			draft.html shouldNotContain "<img"
			draft.html shouldContain "&lt;img"
		}

		@Test
		fun `the attribution falls back to the arrival date, an address without name, or unknown`() {
			val received =
				message(from = listOf(MailAddress("bob@example.test")), sentAt = null).apply {
					receivedAt =
						Instant.parse("2026-01-02T03:04:00Z")
				}
			compose(ResponseMode.REPLY, received).text shouldStartWith "\n\nOn Fri, 2 Jan 2026 at 03:04 UTC, bob@example.test wrote:\n"

			val unknown = message(from = emptyList(), sentAt = null)
			compose(ResponseMode.REPLY, unknown).text shouldStartWith "\n\nOn an unknown date, unknown sender wrote:\n"
		}

		@Test
		fun `a forward has a header block and the original unquoted`() {
			val original = message(to = listOf(MailAddress(ME, "Jane Doe"), CAROL), cc = listOf(DAVE))
			val draft = compose(ResponseMode.FORWARD, original)

			draft.text shouldBe
				"\n\n---------- Forwarded message ----------\n" +
				"From: Bob Example <bob@example.test>\n" +
				"Date: Thu, 5 Mar 2026 at 14:07 UTC\n" +
				"Subject: Project status\n" +
				"To: Jane Doe <jane.doe@example.test>, carol@example.test\n" +
				"Cc: Dave Example <dave@example.test>\n" +
				"\n" +
				"All on track.\nNext review on Friday.\n"
			draft.html shouldStartWith "<p></p><p>---------- Forwarded message ----------<br>From: Bob Example &lt;bob@example.test&gt;<br>"
			draft.html shouldEndWith "</p><p>All on track.</p>"
			draft.html shouldNotContain "blockquote"
		}

		@Test
		fun `a forward leaves out empty To and Cc lines`() {
			val draft = compose(ResponseMode.FORWARD, message(to = emptyList(), cc = emptyList()))

			draft.text shouldNotContain "To:"
			draft.text shouldNotContain "Cc:"
		}
	}

	private companion object {
		const val ME = "jane.doe@example.test"
		const val ALIAS = "support@example.test"
		val BOB = MailAddress("bob@example.test", "Bob Example")
		val CAROL = MailAddress("carol@example.test")
		val DAVE = MailAddress("dave@example.test", "Dave Example")
	}
}
