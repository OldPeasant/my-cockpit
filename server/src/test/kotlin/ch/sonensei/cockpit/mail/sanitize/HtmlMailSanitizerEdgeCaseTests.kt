package ch.sonensei.cockpit.mail.sanitize

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HtmlMailSanitizerEdgeCaseTests {
	private val sanitizer = HtmlMailSanitizer()
	private val requested = mutableListOf<String>()
	private val proxied =
		SanitizeOptions(RemoteImagePolicy.PROXY) {
			requested += it
			"/api/v1/mail/image-proxy?url=" + java.net.URLEncoder.encode(it, Charsets.UTF_8)
		}

	private fun body(html: String) = Jsoup.parseBodyFragment(html).body()

	@ParameterizedTest
	@ValueSource(strings = ["", "   ", "\n\t"])
	fun `empty or blank input gives an empty body`(html: String) {
		val result = sanitizer.sanitize(html)

		result.html.trim() shouldBe ""
		result.remoteImageCount shouldBe 0
		result.remoteImagesBlocked shouldBe false
	}

	@Test
	fun `plain text is kept and markup characters stay escaped`() {
		sanitizer.sanitize("Price < 10 & size > 2").html shouldBe "Price &lt; 10 &amp; size &gt; 2"
	}

	@Test
	fun `non-ascii text is output as utf-8, not as entities`() {
		sanitizer.sanitize("<p>Grüezi, café ✓</p>").html shouldBe "<p>Grüezi, café ✓</p>"
	}

	@Test
	fun `default options block remote images`() {
		SanitizeOptions().remoteImages shouldBe RemoteImagePolicy.BLOCK
	}

	@Test
	fun `upper-case remote image schemes are still treated as remote`() {
		val result = sanitizer.sanitize("""<img src="HTTPS://cdn.example/a.png"><img src="Http://cdn.example/b.png">""")

		result.remoteImageCount shouldBe 2
		result.remoteImagesBlocked shouldBe true
		result.html shouldNotContain "cdn.example"
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"""<img src="/relative.png">""",
			"""<img src="relative.png">""",
			"""<img src="//cdn.example/protocol-relative.png">""",
			"""<img src="">""",
			"""<img alt="no source">""",
			"""<img src="ftp://files.example/a.png">""",
			"""<img src="file:///etc/passwd">""",
			"""<img src="data:image/png;base64,">""",
			"""<img src="data:image/png,rawbytes">""",
			"""<img src="data:image/png;base64,iVBORw0KGgo=&quot;onerror=&quot;alert(1)">""",
		],
	)
	fun `images without an allowed source are removed and not counted`(html: String) {
		val result = sanitizer.sanitize("<p>before$html after</p>")

		body(result.html).select("img").shouldBeEmpty()
		result.remoteImageCount shouldBe 0
		result.remoteImagesBlocked shouldBe false
		body(result.html).text() shouldBe "before after"
	}

	@Test
	fun `upper-case and line-wrapped inline raster images are kept`() {
		val jpeg = "DATA:IMAGE/JPEG;BASE64,/9j/4AAQ\nSkZJRg=="
		val result = sanitizer.sanitize("""<img src="$jpeg" alt="Logo">""")

		body(result.html).select("img") shouldHaveSize 1
		result.remoteImageCount shouldBe 0
	}

	@Test
	fun `proxy policy without remote images reports nothing blocked`() {
		val result = sanitizer.sanitize("""<p>Hello <img src="cid:logo@example"></p>""", proxied)

		result.remoteImageCount shouldBe 0
		result.remoteImagesBlocked shouldBe false
		requested.shouldBeEmpty()
	}

	@Test
	fun `proxy receives each remote url once, trimmed, and cid and data images are untouched`() {
		val png = "data:image/png;base64,iVBORw0KGgo="
		val result =
			sanitizer.sanitize(
				"""<img src="  https://cdn.example/a.png  "><img src="cid:part1@example"><img src="$png"><img src="http://cdn.example/b.gif">""",
				proxied,
			)

		requested shouldBe listOf("https://cdn.example/a.png", "http://cdn.example/b.gif")
		result.remoteImageCount shouldBe 2
		result.remoteImagesBlocked shouldBe false
		body(result.html).select("img").map { it.attr("src") } shouldBe
			listOf(
				"/api/v1/mail/image-proxy?url=https%3A%2F%2Fcdn.example%2Fa.png",
				"cid:part1@example",
				png,
				"/api/v1/mail/image-proxy?url=http%3A%2F%2Fcdn.example%2Fb.gif",
			)
	}

	@Test
	fun `blocked images keep their size and alt so the layout stays stable`() {
		val img =
			body(sanitizer.sanitize("""<img src="https://cdn.example/a.png" width="120" height="40" alt="Logo" border="0">""").html)
				.selectFirst("img")!!

		img.attr("width") shouldBe "120"
		img.attr("height") shouldBe "40"
		img.attr("alt") shouldBe "Logo"
		img.attr("data-cockpit-blocked-image") shouldBe "true"
	}

	@Test
	fun `a blocked marker in the input is not trusted for non-remote images`() {
		val html = sanitizer.sanitize("""<img src="cid:a@example" data-cockpit-blocked-image="true">""").html

		html shouldNotContain "data-cockpit-blocked-image"
	}

	@Test
	fun `mailto and tel links are kept, relative and anchor links lose their href`() {
		val doc =
			body(
				sanitizer
					.sanitize(
						"""<a href="mailto:info@shop.example">m</a><a href="tel:+41000000000">t</a><a href="/account">r</a><a href="#top">h</a>""",
					).html,
			)
		val links = doc.select("a")

		links.map { it.attr("href") } shouldBe listOf("mailto:info@shop.example", "tel:+41000000000", "", "")
		links.filter { it.hasAttr("href") } shouldHaveSize 2
		links.filter { it.hasAttr("target") } shouldHaveSize 2
	}

	@Test
	fun `link attributes that leak or navigate are removed`() {
		val a =
			body(
				sanitizer
					.sanitize(
						"""<a href="https://shop.example/" ping="https://track.example/p" download="x.exe" rel="opener" name="n" id="i">x</a>""",
					).html,
			).selectFirst("a")!!

		a.attributes().map { it.key }.sorted() shouldBe listOf("href", "rel", "target")
		a.attr("rel") shouldBe "noopener noreferrer nofollow"
	}

	@Test
	fun `entity-encoded url in a style attribute is still dropped`() {
		val div =
			body(sanitizer.sanitize("""<div style="color: red; background: url&#40;https://track.example/a.png&#41;">x</div>""").html)
				.selectFirst("div")!!

		div.attr("style") shouldBe "color: red"
	}

	@Test
	fun `remote background attributes on table cells are removed`() {
		val html =
			sanitizer
				.sanitize(
					"""<table background="https://track.example/t.png"><tr><td background="https://track.example/c.png" bgcolor="#fff">x</td></tr></table>""",
				).html

		html shouldNotContain "track.example"
		body(html).selectFirst("td")!!.attr("bgcolor") shouldBe "#fff"
	}

	@Test
	fun `unknown wrapper elements are unwrapped and their text kept`() {
		val html = sanitizer.sanitize("<custom-box><o:p>Office</o:p> <center>Centered</center></custom-box>").html
		val doc = body(html)

		doc.text() shouldBe "Office Centered"
		doc.select("custom-box").shouldBeEmpty()
		doc.select("center") shouldHaveSize 1
	}

	@Test
	fun `noscript mutation vector does not produce an executable image`() {
		val html = sanitizer.sanitize("""<noscript><p title="</noscript><img src=x onerror=alert(1)>"></noscript>""").html

		XssCorpusTests.violations(html).shouldBeEmpty()
		body(html).select("img").shouldBeEmpty()
		body(html).select("noscript").shouldBeEmpty()
	}

	@Test
	fun `newline in a quoted style value cannot smuggle position through the html sanitizer`() {
		val div =
			body(sanitizer.sanitize("""<div style='font-family: "a&#10;; position: fixed; top: 0; x: "'>overlay</div>""").html)
				.selectFirst("div")!!

		div.attr("style") shouldNotContain "position"
	}

	@Test
	fun `sanitizing is idempotent for the newsletter-like layout`() {
		val once =
			sanitizer
				.sanitize(
					"""<table width="600"><tr><td style="padding: 10px; color: #333"><a href="https://shop.example/">Shop</a><img src="cid:x@example"></td></tr></table>""",
				).html

		sanitizer.sanitize(once).html shouldBe once
	}
}
