package ch.sonensei.cockpit.contacts.carddav

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * [MultiStatus] parsing of WebDAV answers (RFC 4918, RFC 6578): hrefs, per-response status, properties of successful
 * propstats only, the sync token, and refusal of anything that is no multistatus or carries a DTD (XXE).
 */
class MultiStatusTests {
	private fun parse(xml: String): MultiStatus = MultiStatus.parse(xml, "Listing the changed contacts")

	@Test
	fun `responses, their status, successful properties and the sync token are read`() {
		val result =
			parse(
				"""
				<?xml version="1.0" encoding="utf-8"?>
				<D:multistatus xmlns:D="DAV:" xmlns:CR="urn:ietf:params:xml:ns:carddav">
				  <D:response>
				    <D:href> /dav/book/ada.vcf </D:href>
				    <D:propstat>
				      <D:prop><D:getetag>"etag-ada"</D:getetag><CR:address-data>BEGIN:VCARD</CR:address-data></D:prop>
				      <D:status>HTTP/1.1 200 OK</D:status>
				    </D:propstat>
				    <D:propstat>
				      <D:prop><D:displayname/></D:prop>
				      <D:status>HTTP/1.1 404 Not Found</D:status>
				    </D:propstat>
				  </D:response>
				  <D:response>
				    <D:href>/dav/book/bob.vcf</D:href>
				    <D:status>HTTP/1.1 404 Not Found</D:status>
				  </D:response>
				  <D:sync-token> http://example.test/sync/42 </D:sync-token>
				</D:multistatus>
				""".trimIndent(),
			)

		result.responses.map { it.href } shouldContainExactly listOf("/dav/book/ada.vcf", "/dav/book/bob.vcf")
		val ada = result.responses[0]
		ada.status.shouldBeNull()
		ada.text(CardDavClient.DAV, "getetag") shouldBe "\"etag-ada\""
		ada.text(CardDavClient.CARDDAV, "address-data") shouldBe "BEGIN:VCARD"
		// The property of the 404 propstat is not found.
		ada.prop(CardDavClient.DAV, "displayname").shouldBeNull()
		result.responses[1].status shouldBe 404
		result.syncToken shouldBe "http://example.test/sync/42"
	}

	@Test
	fun `a default DAV namespace is read like a prefixed one`() {
		val result =
			parse(
				"""<multistatus xmlns="DAV:"><response><href>/a.vcf</href><propstat><prop><getetag>"1"</getetag></prop>""" +
					"""<status>HTTP/1.1 200 OK</status></propstat></response><sync-token>t1</sync-token></multistatus>""",
			)

		result.responses.single().text(CardDavClient.DAV, "getetag") shouldBe "\"1\""
		result.syncToken shouldBe "t1"
	}

	@Test
	fun `a propstat without status counts as found, a response without href is skipped`() {
		val result =
			parse(
				"""<D:multistatus xmlns:D="DAV:"><D:response><D:propstat><D:prop>""" +
					"""<D:getetag>"x"</D:getetag></D:prop></D:propstat></D:response>""" +
					"""<D:response><D:href>/b.vcf</D:href><D:propstat><D:prop>""" +
					"""<D:getetag>"2"</D:getetag></D:prop></D:propstat></D:response></D:multistatus>""",
			)

		result.responses.map { it.href } shouldContainExactly listOf("/b.vcf")
		result.responses.single().text(CardDavClient.DAV, "getetag") shouldBe "\"2\""
	}

	@Test
	fun `an empty sync token and blank property texts are null`() {
		val result =
			parse(
				"""<D:multistatus xmlns:D="DAV:"><D:response><D:href>/c.vcf</D:href>""" +
					"""<D:propstat><D:prop><D:getetag>  </D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>""" +
					"""</D:response><D:sync-token> </D:sync-token></D:multistatus>""",
			)

		result.syncToken.shouldBeNull()
		val response = result.responses.single()
		response.prop(CardDavClient.DAV, "getetag").shouldNotBeNull()
		response.text(CardDavClient.DAV, "getetag").shouldBeNull()
	}

	@Test
	fun `a multistatus without sync token has none`() {
		parse("""<D:multistatus xmlns:D="DAV:"/>""").apply {
			responses.size shouldBe 0
			syncToken.shouldBeNull()
		}
	}

	@Test
	fun `an external entity is refused and never resolved (XXE)`() {
		val e =
			shouldThrow<CardDavException> {
				parse(
					"""<?xml version="1.0"?><!DOCTYPE m [<!ENTITY x SYSTEM "file:///etc/passwd">]>""" +
						"""<D:multistatus xmlns:D="DAV:"><D:response><D:href>&x;</D:href></D:response></D:multistatus>""",
				)
			}

		e.message shouldBe INVALID
		e.message.shouldNotBeNull() shouldNotContain "root:"
	}

	@Test
	fun `an internal entity expansion (billion laughs) is refused as well`() {
		shouldThrow<CardDavException> {
			parse(
				"""<?xml version="1.0"?><!DOCTYPE m [<!ENTITY a "aaaaaaaaaa">""" +
					"""<!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>""" +
					"""<D:multistatus xmlns:D="DAV:"><D:sync-token>&b;</D:sync-token></D:multistatus>""",
			)
		}.message shouldBe INVALID
	}

	@Test
	fun `text that is no XML is refused`() {
		shouldThrow<CardDavException> { parse("<html><body>Gateway") }.message shouldBe INVALID
		shouldThrow<CardDavException> { parse("") }.message shouldBe INVALID
	}

	@Test
	fun `XML that is no DAV multistatus is refused`() {
		shouldThrow<CardDavException> { parse("<html><body/></html>") }.message shouldBe NO_MULTISTATUS
		shouldThrow<CardDavException> { parse("""<multistatus xmlns="urn:example:other"/>""") }.message shouldBe NO_MULTISTATUS
	}

	companion object {
		const val INVALID = "Listing the changed contacts: the CardDAV server answered with invalid XML"
		const val NO_MULTISTATUS = "Listing the changed contacts: the CardDAV server answered without a multistatus"
	}
}
