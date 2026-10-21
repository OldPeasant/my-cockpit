package ch.sonensei.cockpit.mail.search

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** [MailSearchService.words]: what the full-text query and IMAP SEARCH get from the text the user typed. */
class MailSearchWordsTests {
	@Test
	fun `splits on everything but letters and digits, lowercased, each word once`() {
		MailSearchService.words("Budget-Draft 2026, budget; ZÜRICH") shouldContainExactly listOf("budget", "draft", "2026", "zürich")
	}

	@Test
	fun `query syntax and quotes never reach the words`() {
		MailSearchService.words("o'brien & !x:* | \"quoted\" \\ (group)") shouldContainExactly
			listOf("o", "brien", "x", "quoted", "group")
	}

	@Test
	fun `text without letters or digits has no words`() {
		MailSearchService.words("+++ ... ' \" \\").shouldBeEmpty()
	}

	@Test
	fun `at most 20 distinct words are used`() {
		val words = MailSearchService.words((1..25).joinToString(" ") { "w$it" } + " w1")

		words.size shouldBe 20
		words.first() shouldBe "w1"
		words.last() shouldBe "w20"
	}
}
