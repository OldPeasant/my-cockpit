package ch.sonensei.cockpit.bookmarks.snapshot

import ch.sonensei.cockpit.bookmarks.infra.FetchedPage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.net.URI

class ReadableExtractorTests {
	private fun extract(
		html: String,
		url: String = "https://example.org/blog/post",
		charset: String? = "utf-8",
	) = ReadableExtractor.extract(FetchedPage(URI(url), charset, html.toByteArray(Charsets.UTF_8)), "example.org")

	private val prose =
		"The tomato plants on the balcony need water every morning, and in the hot weeks of July also in the evening. " +
			"A deep pot keeps the roots cool, and mulch keeps the soil from drying out too fast."

	private val article =
		"""
		<html><head><title>Growing tomatoes | Garden blog</title><script>var tracking = 1;</script>
		<style>body { color: red }</style></head>
		<body>
		<header class="site-header"><a href="/">Garden blog</a></header>
		<nav><ul><li><a href="/a">Home</a></li><li><a href="/b">Archive</a></li></ul></nav>
		<div id="sidebar"><p>Subscribe to our newsletter for weekly tips about gardening, plants and more.</p></div>
		<div class="post-content">
		  <h2>Water</h2>
		  <p>$prose</p>
		  <p>Second paragraph, with more advice: pick the fruit when it is red, not before, and keep the leaves dry.</p>
		  <p>Read <a href="/guides/soil">the soil guide</a>, it explains which earth to buy, and why it matters.</p>
		  <img src="https://tracker.example.com/pixel.gif">
		  <p onclick="alert(1)">Last paragraph, it ends here, with a final thought about patience in the garden.</p>
		</div>
		<div class="comments"><p>Great post, thanks a lot, I tried it and it worked for my plants as well!</p></div>
		<footer><p>Copyright 2026 by the garden blog, all rights reserved, no reuse.</p></footer>
		</body></html>
		""".trimIndent()

	@Test
	fun `keeps the main content and drops navigation, sidebar, comments and footer`() {
		val page = extract(article)

		page.title shouldBe "Growing tomatoes | Garden blog"
		page.text shouldContain "tomato plants on the balcony"
		page.text shouldContain "Last paragraph"
		page.text shouldContain "Water"
		page.text shouldNotContain "newsletter"
		page.text shouldNotContain "Great post"
		page.text shouldNotContain "Copyright"
		page.text shouldNotContain "Archive"
	}

	@Test
	fun `the result is sanitized - no scripts, styles, event handlers or images, absolute links`() {
		val html = extract(article).html

		html shouldNotContain "<script"
		html shouldNotContain "tracking"
		html shouldNotContain "color: red"
		html shouldNotContain "onclick"
		html shouldNotContain "<img"
		html shouldNotContain "tracker.example.com"
		val link = Jsoup.parse(html).select("article a").first { it.text() == "the soil guide" }
		link.attr("href") shouldBe "https://example.org/guides/soil"
		link.attr("rel") shouldBe "noopener noreferrer nofollow"
	}

	@Test
	fun `the document has a title, a source link and a content security policy that blocks remote loads`() {
		val document = Jsoup.parse(extract(article, url = "https://example.org/blog/post?x=1&y=2").html)

		document.title() shouldBe "Growing tomatoes | Garden blog"
		document.selectFirst("article h1")!!.text() shouldBe "Growing tomatoes | Garden blog"
		document.selectFirst("article a")!!.attr("href") shouldBe "https://example.org/blog/post?x=1&y=2"
		document.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content") shouldContain "default-src 'none'"
		document.selectFirst("meta[charset]")!!.attr("charset") shouldBe "utf-8"
	}

	@Test
	fun `dangerous links and markup in the content are removed`() {
		val html =
			extract(
				"""
				<body><article>
				<p>$prose <a href="javascript:alert(1)">click</a> <a href="data:text/html,x">data</a></p>
				<p>$prose<iframe src="https://evil.example.com"></iframe><svg onload="alert(1)"></svg></p>
				<p>$prose<form action="/x"><input name="q"></form><object data="x.swf"></object></p>
				</article></body>
				""".trimIndent(),
			).html

		html shouldNotContain "javascript:"
		html shouldNotContain "data:text"
		html shouldNotContain "<iframe"
		html shouldNotContain "<svg"
		html shouldNotContain "onload"
		html shouldNotContain "<form"
		html shouldNotContain "<input"
		html shouldNotContain "<object"
	}

	@Test
	fun `markup in the title is escaped`() {
		val page = extract("<head><title>A &lt;script&gt;alert(1)&lt;/script&gt; title</title></head><body><p>$prose</p></body>")

		page.html shouldNotContain "<script"
		Jsoup.parse(page.html).selectFirst("article h1")!!.text() shouldBe "A <script>alert(1)</script> title"
	}

	@Test
	fun `og title wins, the fallback title is used without any`() {
		extract("<head><meta property=\"og:title\" content=\"OG\"><title>T</title></head><body><p>$prose</p></body>").title shouldBe "OG"
		extract("<body><p>$prose</p></body>").title shouldBe "example.org"
	}

	@Test
	fun `hidden elements are dropped`() {
		val page =
			extract(
				"""
				<body><div class="content"><p>$prose</p>
				<p hidden>Hidden paragraph, it should never show up in the copy at all.</p>
				<p style="display: none">Invisible paragraph, it should never show up in the copy either.</p>
				<p aria-hidden="true">Decorative paragraph, it should never show up in the copy either.</p>
				</div></body>
				""".trimIndent(),
			)

		page.text shouldContain "tomato plants"
		page.text shouldNotContain "Hidden paragraph"
		page.text shouldNotContain "Invisible paragraph"
		page.text shouldNotContain "Decorative paragraph"
	}

	@Test
	fun `div with only text counts as a paragraph`() {
		val page =
			extract(
				"""
				<body><div class="menu"><a href="/1">One</a> <a href="/2">Two</a></div>
				<div class="story"><div>$prose</div><div>Another block of text, with a comma, and another one, here.</div></div></body>
				""".trimIndent(),
			)

		page.text shouldContain "tomato plants"
		page.text shouldContain "Another block of text"
		page.text shouldNotContain "One"
	}

	@Test
	fun `a page without paragraphs keeps the cleaned body`() {
		val page = extract("<body><h1>Only a heading</h1><ul><li>an item</li></ul><script>x()</script></body>")

		page.text shouldContain "Only a heading"
		page.text shouldContain "an item"
		page.html shouldNotContain "x()"
	}

	@Test
	fun `a page with no text has empty text`() {
		extract("<body><script>x()</script><img src=\"a.png\"></body>").text shouldBe ""
	}

	@Test
	fun `the charset of the response is used`() {
		val bytes = "<body><p>Grüezi mitenand, $prose</p></body>".toByteArray(Charsets.ISO_8859_1)
		val page = ReadableExtractor.extract(FetchedPage(URI("https://example.org/"), "iso-8859-1", bytes), "example.org")

		page.text shouldContain "Grüezi"
	}

	@Test
	fun `tables, lists, quotes and code survive`() {
		val html =
			extract(
				"""
				<body><article><p>$prose</p>
				<table><tr><th colspan="2">Month</th></tr><tr><td>July</td><td>Water twice</td></tr></table>
				<ol start="3"><li>Third step</li></ol>
				<blockquote cite="https://example.org/q">Patience is a virtue</blockquote>
				<pre><code>water(plants)</code></pre>
				</article></body>
				""".trimIndent(),
			).html
		val document = Jsoup.parse(html)

		document.selectFirst("th")!!.attr("colspan") shouldBe "2"
		document.selectFirst("ol")!!.attr("start") shouldBe "3"
		document.selectFirst("blockquote")!!.text() shouldBe "Patience is a virtue"
		document.selectFirst("pre code")!!.text() shouldBe "water(plants)"
	}
}
