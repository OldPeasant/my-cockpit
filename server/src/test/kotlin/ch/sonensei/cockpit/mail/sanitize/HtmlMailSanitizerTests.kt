package ch.sonensei.cockpit.mail.sanitize

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class HtmlMailSanitizerTests {
	private val sanitizer = HtmlMailSanitizer()
	private val proxy = ImageProxyUrlBuilder { "/api/v1/mail/image-proxy?url=" + java.net.URLEncoder.encode(it, Charsets.UTF_8) }
	private val proxied = SanitizeOptions(RemoteImagePolicy.PROXY, proxy)

	private fun body(html: String) = Jsoup.parseBodyFragment(html).body()

	@Test
	fun `remote images are blocked by default and counted`() {
		val result = sanitizer.sanitize("""<p><img src="https://tracker.example/pixel.gif" alt="x"><img src="http://cdn.example/a.png"></p>""")

		result.remoteImageCount shouldBe 2
		result.remoteImagesBlocked shouldBe true
		val images = body(result.html).select("img")
		images shouldHaveSize 2
		images.forEach {
			it.hasAttr("src") shouldBe false
			it.attr("data-cockpit-blocked-image") shouldBe "true"
		}
		result.html shouldNotContain "tracker.example"
	}

	@Test
	fun `remote images are rewritten to the proxy under the proxy policy`() {
		val result = sanitizer.sanitize("""<img src=" https://cdn.example/a.png?x=1&amp;y=2 ">""", proxied)

		result.remoteImageCount shouldBe 1
		result.remoteImagesBlocked shouldBe false
		body(result.html).selectFirst("img")!!.attr("src") shouldBe
			"/api/v1/mail/image-proxy?url=https%3A%2F%2Fcdn.example%2Fa.png%3Fx%3D1%26y%3D2"
	}

	@Test
	fun `inline raster data images and cid images are kept, svg data images removed`() {
		val png = "data:image/png;base64,iVBORw0KGgo="
		val result =
			sanitizer.sanitize(
				"""<img src="$png"><img src="cid:logo@example"><img src="data:image/svg+xml;base64,PHN2Zz4=">""",
			)

		val sources = body(result.html).select("img").map { it.attr("src") }
		sources shouldBe listOf(png, "cid:logo@example")
		result.remoteImageCount shouldBe 0
		result.remoteImagesBlocked shouldBe false
	}

	@Test
	fun `layout tables and their presentational attributes survive`() {
		val html =
			"""<table width="600" cellpadding="0" cellspacing="0" border="0" align="center" bgcolor="#ffffff">
			<tr><td valign="top" colspan="2" style="padding: 10px; color: #333">Hello</td></tr></table>"""
		val table = body(sanitizer.sanitize(html).html).selectFirst("table")!!

		table.attr("width") shouldBe "600"
		table.attr("cellpadding") shouldBe "0"
		table.attr("bgcolor") shouldBe "#ffffff"
		val td = table.selectFirst("td")!!
		td.attr("valign") shouldBe "top"
		td.attr("colspan") shouldBe "2"
		td.attr("style") shouldBe "padding: 10px; color: #333"
	}

	@Test
	fun `inline styles keep safe declarations and drop position and url`() {
		val html = """<div style="color: red; position: fixed; background: url(https://t.example/a.png); font-size: 14px">x</div>"""
		body(sanitizer.sanitize(html).html).selectFirst("div")!!.attr("style") shouldBe "color: red; font-size: 14px"
	}

	@Test
	fun `style attribute is removed when nothing safe is left`() {
		body(sanitizer.sanitize("""<p style="position:absolute">x</p>""").html).selectFirst("p")!!.hasAttr("style") shouldBe false
	}

	@Test
	fun `links open in a new context without referrer`() {
		val a = body(sanitizer.sanitize("""<a href="https://shop.example/" target="_top">Shop</a>""").html).selectFirst("a")!!
		a.attr("href") shouldBe "https://shop.example/"
		a.attr("target") shouldBe "_blank"
		a.attr("rel") shouldBe "noopener noreferrer nofollow"
	}

	@Test
	fun `text content of removed elements does not leak`() {
		val html = sanitizer.sanitize("<style>.a{color:red}</style><script>var secret=1</script><title>T</title><p>Body</p>").html
		html shouldBe "<p>Body</p>"
	}

	@Test
	fun `proxy policy without a proxy url builder is rejected`() {
		shouldThrow<IllegalArgumentException> { SanitizeOptions(RemoteImagePolicy.PROXY) }
	}

	@Test
	fun `fictitious newsletter keeps its content and layout`() {
		val result = sanitizer.sanitize(NEWSLETTER)
		val doc = body(result.html)

		XssCorpusTests.violations(result.html).shouldBeEmpty()
		doc.select("table") shouldHaveSize 3
		doc.text() shouldContain "Autumn sale at Example Outdoor"
		doc.text() shouldContain "Waterproof jacket"
		doc.text() shouldContain "Unsubscribe"
		doc.selectFirst("h1")!!.attr("style") shouldBe "font-family: Arial, sans-serif; font-size: 24px; color: #1a1a1a"
		doc.selectFirst("a.button")!!.attr("style") shouldBe
			"display: inline-block; background-color: #0a7d4f; color: #ffffff; padding: 12px 24px; border-radius: 4px"
		result.remoteImageCount shouldBe 3
		result.remoteImagesBlocked shouldBe true
		result.html shouldNotContain "track.example"
		result.html shouldNotContain "@media"
	}

	@Test
	fun `fictitious newsletter under proxy policy proxies every remote image`() {
		val result = sanitizer.sanitize(NEWSLETTER, proxied)

		XssCorpusTests.violations(result.html, allowProxied = true).shouldBeEmpty()
		val sources = body(result.html).select("img").map { it.attr("src") }
		sources shouldHaveSize 3
		sources.forEach { it shouldContain "/api/v1/mail/image-proxy?url=https%3A%2F%2F" }
	}

	@Test
	fun `fictitious plain notification mail is preserved`() {
		val html =
			"""<div dir="ltr">Hi Alex,<br><br>your parcel <b>EX-1234</b> ships today.<br>
			<blockquote style="margin: 0 0 0 .8ex; border-left: 1px #ccc solid; padding-left: 1ex">Old quote</blockquote>
			<font face="Verdana" color="#555555" size="2">Example Logistics</font></div>"""
		val doc = body(sanitizer.sanitize(html).html)

		doc.selectFirst("div")!!.attr("dir") shouldBe "ltr"
		doc.selectFirst("b")!!.text() shouldBe "EX-1234"
		doc.selectFirst("blockquote")!!.attr("style") shouldBe "margin: 0 0 0 .8ex; border-left: 1px #ccc solid; padding-left: 1ex"
		doc.selectFirst("font")!!.attr("face") shouldBe "Verdana"
	}

	private companion object {
		val NEWSLETTER =
			"""
			<!DOCTYPE html>
			<html><head>
			<meta charset="utf-8"><title>Example Outdoor</title>
			<link rel="stylesheet" href="https://cdn.example/news.css">
			<style>@media (max-width: 600px) { .col { width: 100% !important; } }</style>
			</head>
			<body style="margin:0;padding:0;background-color:#f4f4f4" onload="track()">
			<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#f4f4f4">
			  <tr><td align="center">
			    <table width="600" cellpadding="0" cellspacing="0" border="0" style="background:#ffffff; max-width: 600px">
			      <tr><td style="padding: 20px"><img src="https://cdn.example/logo.png" width="120" alt="Example Outdoor"></td></tr>
			      <tr><td class="col" style="padding: 0 20px">
			        <h1 style="font-family: Arial, sans-serif; font-size: 24px; color: #1a1a1a">Autumn sale at Example Outdoor</h1>
			        <table width="100%"><tr>
			          <td width="50%"><img src="https://cdn.example/jacket.jpg" alt="Jacket" style="display:block;width:100%"></td>
			          <td width="50%" style="background-image: url('https://cdn.example/bg.png'); padding: 10px">
			            <p>Waterproof jacket, now 30% off.</p>
			            <a class="button" href="https://shop.example/jacket?utm_source=news" style="display: inline-block; background-color: #0a7d4f; color: #ffffff; padding: 12px 24px; border-radius: 4px">Shop now</a>
			          </td>
			        </tr></table>
			      </td></tr>
			      <tr><td style="font-size: 11px; color: #888888; padding: 20px">
			        <a href="https://shop.example/unsubscribe?id=fictitious">Unsubscribe</a>
			        <img src="https://track.example/open.gif" width="1" height="1" alt="">
			      </td></tr>
			    </table>
			  </td></tr>
			</table>
			<script>function track() { new Image().src = 'https://track.example/js'; }</script>
			</body></html>
			""".trimIndent()
	}
}
