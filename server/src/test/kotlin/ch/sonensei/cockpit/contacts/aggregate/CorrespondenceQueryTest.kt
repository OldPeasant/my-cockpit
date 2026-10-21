package ch.sonensei.cockpit.contacts.aggregate

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** [CorrespondenceQuery.addressPattern] without a database; Java regex matches the pattern like Postgres `~*` here. */
class CorrespondenceQueryTest {
	private fun matches(
		addresses: List<String>,
		header: String,
	) = Regex(CorrespondenceQuery.addressPattern(addresses), RegexOption.IGNORE_CASE).containsMatchIn(header)

	@Test
	fun `escapes regex metacharacters and bounds the address`() {
		CorrespondenceQuery.addressPattern(listOf("a.b+c@x-y.test")) shouldBe """(^|[\s<,])(a\.b\+c@x\-y\.test)($|[\s>,])"""
	}

	@Test
	fun `joins several addresses as alternatives inside the bounds`() {
		CorrespondenceQuery.addressPattern(listOf("a@x.test", "b@y.test")) shouldBe """(^|[\s<,])(a@x\.test|b@y\.test)($|[\s>,])"""
	}

	@Test
	fun `finds the address as a whole mailbox in a stored header`() {
		val ann = listOf("ann.doe@example.test")
		matches(ann, "ann.doe@example.test") shouldBe true
		matches(ann, "\"Doe, Ann\" <Ann.Doe@Example.test>") shouldBe true
		matches(ann, "bob@example.test, ann.doe@example.test") shouldBe true
		matches(ann, "ann.doe@example.test,bob@example.test") shouldBe true
		matches(ann, "joann.doe@example.test") shouldBe false
		matches(ann, "ann.doe@example.test.org") shouldBe false
		matches(ann, "annxdoe@example.test") shouldBe false
	}

	@Test
	fun `treats metacharacters in addresses literally`() {
		matches(listOf("a+b@example.test"), "a+b@example.test") shouldBe true
		matches(listOf("a+b@example.test"), "aab@example.test") shouldBe false
		matches(listOf("x^y\$z|w@example.test"), "<x^y\$z|w@example.test>") shouldBe true
		matches(listOf("x^y\$z|w@example.test"), "w@example.test") shouldBe false
		matches(listOf("(a)[b]{2}?*@example.test"), "(a)[b]{2}?*@example.test") shouldBe true
	}
}
