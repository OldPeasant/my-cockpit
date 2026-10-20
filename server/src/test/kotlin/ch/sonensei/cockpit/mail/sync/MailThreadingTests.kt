package ch.sonensei.cockpit.mail.sync

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MailThreadingTests {
	/** An account's cached messages, threaded like [MailHeaderStore] does: assign, then rewrite merged keys. */
	private class Mailbox : ThreadIndex {
		private val keys = linkedMapOf<String, String>()

		fun receive(
			messageId: String?,
			subject: String? = "Budget",
			inReplyTo: String? = null,
			references: String? = null,
		): String {
			val assignment = MailThreading.assign(messageId, inReplyTo, references, subject, "row-${keys.size}", this)
			keys.replaceAll { _, key -> if (key in assignment.merged) assignment.key else key }
			keys[messageId ?: "row-${keys.size}"] = assignment.key
			return assignment.key
		}

		fun keyOf(messageId: String) = keys.getValue(messageId)

		override fun keysByMessageId(messageIds: Collection<String>) = keys.filterKeys { it in messageIds }

		override fun existingKeys(keys: Collection<String>) =
			this.keys.values
				.filter { it in keys }
				.toSet()
	}

	@Test
	fun `a reply joins its parent's conversation by In-Reply-To`() {
		val mail = Mailbox()
		mail.receive("<a@example.test>")
		mail.receive("<b@example.test>", "Re: Budget", inReplyTo = "<a@example.test>")

		mail.keyOf("<b@example.test>") shouldBe mail.keyOf("<a@example.test>")
	}

	@Test
	fun `a reply joins by References even when In-Reply-To is missing`() {
		val mail = Mailbox()
		mail.receive("<a@example.test>", "Plan")
		mail.receive("<b@example.test>", "Something else entirely", references = "<a@example.test>")

		mail.keyOf("<b@example.test>") shouldBe mail.keyOf("<a@example.test>")
	}

	@Test
	fun `a deep reply joins through its root in References`() {
		val mail = Mailbox()
		mail.receive("<a@example.test>")
		mail.receive("<b@example.test>", inReplyTo = "<a@example.test>", references = "<a@example.test>")
		mail.receive("<c@example.test>", inReplyTo = "<b@example.test>", references = "<a@example.test> <b@example.test>")

		setOf(mail.keyOf("<a@example.test>"), mail.keyOf("<b@example.test>"), mail.keyOf("<c@example.test>")).size shouldBe 1
	}

	@Test
	fun `replies that arrive before their root end up in the root's conversation`() {
		val mail = Mailbox()
		mail.receive("<c@example.test>", inReplyTo = "<b@example.test>", references = "<a@example.test> <b@example.test>")
		mail.receive("<b@example.test>", inReplyTo = "<a@example.test>", references = "<a@example.test>")
		mail.receive("<a@example.test>")

		// The root has no references, so the merged conversation takes its subject key.
		mail.keyOf("<a@example.test>") shouldBe "subject:budget"
		mail.keyOf("<b@example.test>") shouldBe "subject:budget"
		mail.keyOf("<c@example.test>") shouldBe "subject:budget"
	}

	@Test
	fun `a message that connects two conversations merges them`() {
		val mail = Mailbox()
		// c only knows its parent b (no References); b arrives later and refers to the root a.
		mail.receive("<c@example.test>", inReplyTo = "<b@example.test>")
		mail.receive("<a@example.test>", "Unrelated subject")
		mail.keyOf("<c@example.test>") shouldNotBe mail.keyOf("<a@example.test>")

		mail.receive("<b@example.test>", inReplyTo = "<a@example.test>", references = "<a@example.test>")

		mail.keyOf("<c@example.test>") shouldBe mail.keyOf("<a@example.test>")
		mail.keyOf("<b@example.test>") shouldBe mail.keyOf("<a@example.test>")
	}

	@Test
	fun `a reply before its parent that refers to the root keeps the conversation together when the root arrives last`() {
		val mail = Mailbox()
		// c only knows its parent b; b refers to the root a; a arrives last with a subject of its own.
		mail.receive("<c@example.test>", inReplyTo = "<b@example.test>")
		mail.receive("<b@example.test>", inReplyTo = "<a@example.test>", references = "<a@example.test>")
		mail.keyOf("<c@example.test>") shouldBe "<a@example.test>"
		mail.keyOf("<b@example.test>") shouldBe "<a@example.test>"

		mail.receive("<a@example.test>", "Unrelated subject")

		mail.keyOf("<a@example.test>") shouldBe "subject:unrelated subject"
		mail.keyOf("<b@example.test>") shouldBe "subject:unrelated subject"
		mail.keyOf("<c@example.test>") shouldBe "subject:unrelated subject"
	}

	@Test
	fun `a root without subject arriving after its replies joins them`() {
		val mail = Mailbox()
		mail.receive("<c@example.test>", subject = null, inReplyTo = "<b@example.test>")
		mail.receive("<b@example.test>", subject = null, inReplyTo = "<a@example.test>")

		mail.receive("<a@example.test>", subject = null)

		setOf(mail.keyOf("<a@example.test>"), mail.keyOf("<b@example.test>"), mail.keyOf("<c@example.test>")) shouldBe
			setOf("<a@example.test>")
	}

	@Test
	fun `a reply before its parent joins the root's subject conversation once the parent arrives`() {
		val mail = Mailbox()
		mail.receive("<a@example.test>", "Budget")
		mail.receive("<c@example.test>", inReplyTo = "<b@example.test>")

		mail.receive("<b@example.test>", inReplyTo = "<a@example.test>", references = "<a@example.test>")

		mail.keyOf("<b@example.test>") shouldBe "subject:budget"
		mail.keyOf("<c@example.test>") shouldBe "subject:budget"
	}

	@Test
	fun `messages without references fall back to the normalized subject`() {
		val mail = Mailbox()
		mail.receive("<a@example.test>", "Budget 2026")
		mail.receive("<b@example.test>", "RE: AW:  budget   2026")
		mail.receive("<c@example.test>", "Fwd: [team] Budget 2026")
		mail.receive("<d@example.test>", "Holidays")

		mail.keyOf("<a@example.test>") shouldBe "subject:budget 2026"
		mail.keyOf("<b@example.test>") shouldBe "subject:budget 2026"
		mail.keyOf("<c@example.test>") shouldBe "subject:budget 2026"
		mail.keyOf("<d@example.test>") shouldBe "subject:holidays"
	}

	/**
	 * a and d have neither References nor In-Reply-To and the same subject, so the subject fallback puts them into one
	 * conversation; b replies to a. The result must not depend on the order the three arrive in (UID order, folder order).
	 */
	@ParameterizedTest
	@CsvSource(
		value = [
			"a,b,d",
			"a,d,b",
			"b,a,d",
			"b,d,a",
			"d,a,b",
			"d,b,a",
		],
	)
	fun `a root, its reply and another message with the root's subject share one conversation in any arrival order`(
		first: String,
		second: String,
		third: String,
	) {
		val mail = Mailbox()
		for (name in listOf(first, second, third)) {
			when (name) {
				"a" -> mail.receive("<a@example.test>", "Budget")
				"b" -> mail.receive("<b@example.test>", "Re: Budget", inReplyTo = "<a@example.test>")
				"d" -> mail.receive("<d@example.test>", "Budget")
			}
		}

		setOf(mail.keyOf("<a@example.test>"), mail.keyOf("<b@example.test>"), mail.keyOf("<d@example.test>")).size shouldBe 1
	}

	/**
	 * Every arrival order of a root a, its reply b, a deep reply c (References a b), a reply t that only names its parent
	 * b, a message d with the root's subject, and an unrelated root u with a reply v whose subject looks like the budget
	 * thread: always the same two conversations, with the same keys.
	 */
	@Test
	fun `threading by references and subject gives the same conversations in every arrival order`() {
		val messages =
			mapOf<String, (Mailbox) -> Unit>(
				"a" to { it.receive("<a@example.test>", "Budget") },
				"b" to { it.receive("<b@example.test>", "Re: Budget", inReplyTo = "<a@example.test>", references = "<a@example.test>") },
				"c" to {
					it.receive("<c@example.test>", "Re: Budget", inReplyTo = "<b@example.test>", references = "<a@example.test> <b@example.test>")
				},
				"t" to { it.receive("<t@example.test>", "Re: Budget", inReplyTo = "<b@example.test>") },
				"d" to { it.receive("<d@example.test>", "Budget") },
				"u" to { it.receive("<u@example.test>", "Unrelated") },
				"v" to { it.receive("<v@example.test>", "Re: Budget", inReplyTo = "<u@example.test>") },
			)
		val expected =
			listOf("a", "b", "c", "t", "d").associate { "<$it@example.test>" to "subject:budget" } +
				listOf("u", "v").associate { "<$it@example.test>" to "subject:unrelated" }

		for (order in permutations(messages.keys.toList())) {
			val mail = Mailbox()
			order.forEach { messages.getValue(it)(mail) }

			withClue("arrival order $order") { expected.mapValues { (id, _) -> mail.keyOf(id) } shouldBe expected }
		}
	}

	private fun permutations(items: List<String>): List<List<String>> =
		if (items.size <= 1) {
			listOf(items)
		} else {
			items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }
		}

	@Test
	fun `a reply to a subject-threaded message joins that conversation`() {
		val mail = Mailbox()
		mail.receive("<a@example.test>", "Budget")
		mail.receive("<b@example.test>", "Re: Budget", inReplyTo = "<a@example.test>")

		mail.keyOf("<b@example.test>") shouldBe "subject:budget"
	}

	@Test
	fun `a reply whose parent is not cached is keyed by its root until the root arrives`() {
		val mail = Mailbox()

		mail.receive("<b@example.test>", inReplyTo = "<x@example.test>", references = "<root@example.test> <x@example.test>") shouldBe
			"<root@example.test>"
	}

	@Test
	fun `without references, subject and Message-ID the fallback id keeps the message alone`() {
		val mail = Mailbox()

		mail.receive(null, subject = "Re: ") shouldBe "id:row-0"
		mail.receive(null, subject = null) shouldBe "id:row-1"
	}

	@Test
	fun `an empty subject without references uses the Message-ID`() {
		Mailbox().receive("<a@example.test>", subject = "   ") shouldBe "<a@example.test>"
	}

	@Test
	fun `a message referring to itself is not its own parent`() {
		val mail = Mailbox()

		mail.receive("<a@example.test>", "Budget", references = "<a@example.test>") shouldBe "subject:budget"
	}

	@ParameterizedTest
	@CsvSource(
		delimiter = '|',
		value = [
			"Re: Hello|hello",
			"RE:RE: Hello|hello",
			"Fwd: Re: Hello|hello",
			"FW: Hello|hello",
			"AW: WG: Hallo|hallo",
			"SV: Hej|hej",
			"Re[2]: Hello|hello",
			"[list] Re: Hello|hello",
			"Re: [list] Hello|hello",
			"  Hello   World  |hello world",
			"Réponse: café|réponse: café",
			"Return of the Re: thing|return of the re: thing",
		],
	)
	fun `normalizeSubject strips reply prefixes and list tags`(
		subject: String,
		expected: String,
	) {
		MailThreading.normalizeSubject(subject) shouldBe expected
	}

	@Test
	fun `normalizeSubject of a missing subject is empty`() {
		MailThreading.normalizeSubject(null) shouldBe ""
		MailThreading.normalizeSubject("Re:") shouldBe ""
	}

	@Test
	fun `messageIds reads every id in order without duplicates and ignores junk`() {
		MailThreading.messageIds("<a@x>  <b@x>\r\n\t<a@x> garbage <c@x>") shouldContainExactly listOf("<a@x>", "<b@x>", "<c@x>")
		MailThreading.messageIds("no ids here").shouldBeEmpty()
		MailThreading.messageIds(null).shouldBeEmpty()
	}
}
