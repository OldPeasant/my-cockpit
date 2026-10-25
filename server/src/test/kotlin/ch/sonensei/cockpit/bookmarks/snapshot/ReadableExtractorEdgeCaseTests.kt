package ch.sonensei.cockpit.bookmarks.snapshot

import ch.sonensei.cockpit.bookmarks.infra.FetchedPage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.net.URI

/** Edge cases of [ReadableExtractor]: sanitization against XSS vectors, charsets, titles and the content heuristics. */
class ReadableExtractorEdgeCaseTests {
	private fun extract(
		html: String,
		url: String = "https://example.org/blog/post",
		charset: String? = "utf-8",
		bytes: ByteArray = html.toByteArray(Charsets.UTF_8),
	) = ReadableExtractor.extract(FetchedPage(URI(url), charset, bytes), "example.org")

	private val prose =
		"The tomato plants on the balcony need water every morning, and in the hot weeks of July also in the evening. " +
			"A deep pot keeps the roots cool, and mulch keeps the soil from drying out too fast."

	/** Vectors that try to get script, remote loads or unsafe attributes past the sanitizer (fictitious hosts). */
	private val vectors =
		"""
		<p>Vector one, <a href="jav&#x09;ascript:alert(1)">tab</a> <a href="JaVaScRiPt:alert(1)">case</a>
		<a href=" javascript:alert(1)">space</a> <a href="vbscript:msgbox(1)">vb</a>
		<a href="data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==">data</a></p>
		<p style="background:url(https://tracker.example.com/bg.png)" class="x" id="clobber" data-x="y">Vector two styled</p>
		<p>Vector three <img src="x" onerror="alert(1)"> <video src="https://tracker.example.com/v.mp4" poster="p.png"></video>
		<audio src="a.mp3"></audio><picture><source srcset="https://tracker.example.com/s.png"></picture></p>
		<math><mtext><table><mglyph><style><img src="x" onerror="alert(1)"></style></mglyph></table></mtext></math>
		<noscript><p title="</noscript><img src=x onerror=alert(1)>">Vector four</p></noscript>
		<p><a href="https://ok.example.org/page" target="_blank" rel="opener" onmouseover="alert(1)">Vector five link</a></p>
		<meta http-equiv="refresh" content="0;url=https://evil.example.com/">
		<link rel="stylesheet" href="https://evil.example.com/s.css">
		<base href="https://evil.example.com/">
		<details open ontoggle="alert(1)"><summary>Vector six</summary></details>
		<blockquote cite="javascript:alert(1)">Vector seven quote</blockquote>
		<q cite="data:text/html,x">Vector eight</q>
		<time datetime="2026-01-01" onclick="alert(1)">Vector nine</time>
		<table background="https://tracker.example.com/t.png"><tr><td style="color:red">Vector ten cell</td></tr></table>
		<a href="ftp://files.example.org/x">Vector eleven ftp</a>
		<object data="x.swf"></object><embed src="x.swf"><applet code="x"></applet>
		<marquee onstart="alert(1)">Vector twelve</marquee>
		""".trimIndent()

	private val allowedTags =
		setOf(
			"#root",
			"html",
			"head",
			"body",
			"meta",
			"title",
			"article",
			"p",
			"br",
			"hr",
			"h1",
			"h2",
			"h3",
			"h4",
			"h5",
			"h6",
			"ul",
			"ol",
			"li",
			"dl",
			"dt",
			"dd",
			"blockquote",
			"pre",
			"code",
			"kbd",
			"samp",
			"var",
			"em",
			"strong",
			"b",
			"i",
			"u",
			"s",
			"del",
			"ins",
			"sub",
			"sup",
			"small",
			"mark",
			"q",
			"cite",
			"abbr",
			"dfn",
			"time",
			"figure",
			"figcaption",
			"table",
			"caption",
			"thead",
			"tbody",
			"tfoot",
			"tr",
			"th",
			"td",
			"div",
			"span",
			"section",
			"a",
		)

	private val allowedAttributes =
		mapOf(
			"a" to setOf("href", "title", "rel"),
			"abbr" to setOf("title"),
			"time" to setOf("datetime"),
			"ol" to setOf("start"),
			"th" to setOf("colspan", "rowspan", "scope"),
			"td" to setOf("colspan", "rowspan"),
			"blockquote" to setOf("cite"),
			"q" to setOf("cite"),
		)

	/** Every element and attribute of the body of [html] is on the safelist; links are http(s) or mailto. */
	private fun assertOnlySafeMarkup(html: String) {
		val document = Jsoup.parse(html)
		document.allElements
			.map { it.tagName() }
			.filter { it !in allowedTags }
			.shouldBeEmpty()
		document.head().select("meta").forEach { meta ->
			meta
				.attributes()
				.map { it.key }
				.filter { it !in setOf("charset", "http-equiv", "content", "name") }
				.shouldBeEmpty()
		}
		document.body().select("*").drop(1).forEach { element ->
			val allowed = allowedAttributes[element.tagName()] ?: emptySet()
			element
				.attributes()
				.map { it.key }
				.filter { it !in allowed }
				.shouldBeEmpty()
		}
		document.body().select("a[href]").forEach { link ->
			listOf("http://", "https://", "mailto:").any { link.attr("href").startsWith(it) } shouldBe true
			link.attr("rel") shouldBe "noopener noreferrer nofollow"
		}
		document.body().select("[cite]").forEach { it.attr("cite") shouldStartWith "http" }
		html.lowercase() shouldNotContain "javascript:"
		html.lowercase() shouldNotContain "vbscript:"
		html.lowercase() shouldNotContain "data:text"
		html shouldNotContain "tracker.example.com"
		html shouldNotContain "evil.example.com"
		html shouldNotContain "onerror"
	}

	// --- sanitization

	@Test
	fun `XSS and remote-load vectors in the main content leave only safelisted markup`() {
		val page = extract("<html><head><title>T</title></head><body><article><p>$prose</p><p>$prose</p>$vectors</article></body></html>")

		assertOnlySafeMarkup(page.html)
		page.text shouldContain "tomato plants"
		page.text shouldContain "Vector five link"
		Jsoup
			.parse(page.html)
			.body()
			.select("a[href=https://ok.example.org/page]")
			.size shouldBe 1
	}

	@Test
	fun `XSS vectors are also sanitized when no paragraph qualifies and the whole body is kept`() {
		val page = extract("<html><body>$vectors</body></html>")

		assertOnlySafeMarkup(page.html)
		page.text shouldContain "Vector two styled"
	}

	@Test
	fun `a base tag in the page is dropped and links stay http(s)`() {
		val page =
			extract(
				"<html><head><base href=\"https://evil.example.com/\"></head>" +
					"<body><article><p>$prose <a href=\"soil\">soil</a></p></article></body></html>",
			)
		val document = Jsoup.parse(page.html)

		document.select("base").isEmpty() shouldBe true
		document.select("article a").first { it.text() == "soil" }.attr("href") shouldStartWith "https://"
	}

	@Test
	fun `relative, protocol-relative and mailto links become safe absolute links, other schemes lose their href`() {
		val document =
			Jsoup.parse(
				extract(
					"""
					<body><article><p>$prose
					<a href="../guides/soil">up</a> <a href="//cdn.example.org/x">proto</a>
					<a href="mailto:gardener@example.org">mail</a> <a href="ftp://example.org/f">ftp</a></p></article></body>
					""".trimIndent(),
					url = "https://example.org/blog/post",
				).html,
			)

		document.select("a").first { it.text() == "up" }.attr("href") shouldBe "https://example.org/guides/soil"
		document.select("a").first { it.text() == "proto" }.attr("href") shouldBe "https://cdn.example.org/x"
		document.select("a").first { it.text() == "mail" }.attr("href") shouldBe "mailto:gardener@example.org"
		document.select("a").first { it.text() == "ftp" }.hasAttr("href") shouldBe false
	}

	@Test
	fun `the output is a complete document with doctype, no-referrer policy and no empty leftovers`() {
		val html = extract("<body><article><p>$prose</p><p><img src=\"a.png\"></p><div><span></span></div></article></body>").html

		html shouldStartWith "<!DOCTYPE html>"
		val document = Jsoup.parse(html)
		document.selectFirst("meta[name=referrer]")!!.attr("content") shouldBe "no-referrer"
		val csp = document.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content")
		csp shouldContain "default-src 'none'"
		csp shouldContain "base-uri 'none'"
		csp shouldContain "form-action 'none'"
		document.select("article p, article div, article span").filter { it.text().isBlank() }.shouldBeEmpty()
	}

	// --- charsets

	@Test
	fun `an unknown or illegal charset name falls back to detection instead of failing`() {
		extract("<body><p>$prose</p></body>", charset = "x-unknown-charset").text shouldContain "tomato plants"
		extract("<body><p>$prose</p></body>", charset = "not a charset!").text shouldContain "tomato plants"
	}

	@Test
	fun `without a charset header the meta charset of the page is used`() {
		val html = "<html><head><meta charset=\"iso-8859-1\"></head><body><p>Grüezi mitenand, $prose</p></body></html>"
		val page = extract("", charset = null, bytes = html.toByteArray(Charsets.ISO_8859_1))

		page.text shouldContain "Grüezi"
	}

	// --- titles

	@Test
	fun `titles collapse whitespace, blank ones fall back to the h1, then to the fallback title`() {
		extract("<head><title>  Growing\n\t  tomatoes  </title></head><body><p>$prose</p></body>").title shouldBe "Growing tomatoes"
		extract(
			"<head><meta property=\"og:title\" content=\"  \"><title> </title></head><body><h1>Heading title</h1><p>$prose</p></body>",
		).title shouldBe "Heading title"
		val fallback = extract("<head><title> </title></head><body><p>$prose</p></body>")
		fallback.title shouldBe "example.org"
		Jsoup.parse(fallback.html).title() shouldBe "example.org"
	}

	// --- heuristics

	@Test
	fun `elements with navigation or complementary roles are dropped`() {
		val page =
			extract(
				"""
				<body><div role="navigation"><p>Navigation block with many words, links, and more links, here.</p></div>
				<div role="complementary"><p>Complementary block with many words, teasers, and more teasers, here.</p></div>
				<div><p>$prose</p><p>$prose</p></div></body>
				""".trimIndent(),
			)

		page.text shouldContain "tomato plants"
		page.text shouldNotContain "Navigation block"
		page.text shouldNotContain "Complementary block"
	}

	@Test
	fun `a block made of links loses against prose`() {
		val links =
			(1..4).joinToString("") { "<p><a href=\"/l$it\">A long teaser link number $it, with commas, to another story</a></p>" }
		val page = extract("<body><div>$links</div><div><p>$prose</p><p>$prose</p></div></body>")

		page.text shouldContain "tomato plants"
		page.text shouldNotContain "teaser link"
	}

	@Test
	fun `a prose paragraph next to the best container is kept, an unrelated link list is not`() {
		val page =
			extract(
				"""
				<body><div>
				<div class="content"><p>$prose</p><p>$prose</p></div>
				<p>A closing sentence that sits next to the article. It belongs to it.</p>
				<ul><li><a href="/x">Unrelated link</a></li></ul>
				</div></body>
				""".trimIndent(),
			)

		page.text shouldContain "tomato plants"
		page.text shouldContain "A closing sentence"
		page.text shouldNotContain "Unrelated link"
	}

	@Test
	fun `paragraphs in lists and headings lose against prose, paragraphs in a quote count`() {
		val page =
			extract(
				"""
				<body>
				<section><ul><li><p>A teaser paragraph in a list, long enough to count, really.</p></li>
				<li><p>Another teaser paragraph in a list, long enough to count, too.</p></li></ul></section>
				<section><h3><p>A paragraph inside a heading, long enough to count, as well.</p></h3></section>
				<div><blockquote><p>$prose</p><p>$prose</p></blockquote></div>
				</body>
				""".trimIndent(),
			)

		page.text shouldContain "tomato plants"
		page.text shouldNotContain "teaser paragraph"
		page.text shouldNotContain "inside a heading"
	}

	@Test
	fun `an article element is never dropped because of its class`() {
		extract("<body><article class=\"share-header\"><p>$prose</p></article></body>").text shouldContain "tomato plants"
	}

	@Test
	fun `class names that look unlikely but also say content are kept`() {
		extract("<body><div class=\"sidebar-content\"><p>$prose</p></div></body>").text shouldContain "tomato plants"
	}

	@Test
	fun `a form wrapping the whole page keeps its article`() {
		val page =
			extract(
				"""
				<body><form id="aspnetForm" action="/default.aspx"><input type="hidden" name="__VIEWSTATE" value="x">
				<div class="page"><article><p>$prose</p><p>$prose</p></article></div></form></body>
				""".trimIndent(),
			)

		page.text shouldContain "tomato plants"
		page.html shouldNotContain "<form"
		page.html shouldNotContain "__VIEWSTATE"
	}

	@Test
	fun `a form wrapping the whole page without an article falls back to an extraction that keeps forms`() {
		val page =
			extract("<body><form id=\"aspnetForm\" action=\"/default.aspx\"><div><p>$prose</p><p>$prose</p></div></form></body>")

		page.text shouldContain "tomato plants"
		page.html shouldNotContain "<form"
	}

	@Test
	fun `a wrapper with an unlikely class around the article is kept`() {
		val page =
			extract(
				"""
				<body><div class="layout has-sidebar"><article><p>$prose</p><p>$prose</p></article>
				<div class="sidebar"><p>Subscribe to the newsletter, it has tips, tricks and more tips every week.</p></div></div></body>
				""".trimIndent(),
			)

		page.text shouldContain "tomato plants"
		page.text shouldNotContain "Subscribe"
	}

	@Test
	fun `a wrapper with an unlikely class and no main element falls back to an extraction that keeps it`() {
		extract("<body><div class=\"layout has-sidebar\"><div><p>$prose</p><p>$prose</p></div></div></body>").text shouldContain
			"tomato plants"
	}

	// --- wrappers of the main element and the retry pass (CR-1)

	@Test
	fun `a main element or role main inside an unlikely wrapper keeps the wrapper in the first pass`() {
		// Three paragraphs are more than 500 characters, so no retry runs: the newsletter paragraph (an unlikely class)
		// stays removed only if the first pass kept the wrapper and removed just the newsletter.
		for (main in listOf("<main>" to "</main>", "<div role=\"main\">" to "</div>")) {
			val page =
				extract(
					"""
					<body><div class="layout has-sidebar">${main.first}<p>$prose</p><p>$prose</p><p>$prose</p>${main.second}
					<p class="newsletter-signup">Subscribe to the fictitious newsletter. It is free.</p></div></body>
					""".trimIndent(),
				)

			page.text shouldContain "tomato plants"
			page.text shouldNotContain "Subscribe"
		}
	}

	@Test
	fun `a short first result is replaced by the longer result of the retry`() {
		val page =
			extract(
				"""
				<body><div><p>A short teaser outside the form, long enough to count.</p></div>
				<form action="/search"><div><p>$prose</p><p>$prose</p></div></form></body>
				""".trimIndent(),
			)

		page.text shouldContain "tomato plants"
		page.html shouldNotContain "<form"
	}

	@Test
	fun `the first result is kept when the retry finds less text`() {
		// The retry keeps the unlikely `header-text` block, which then scores best (positive class) but holds less text.
		val page =
			extract(
				"""
				<body><div class="header-text"><p>Tip of the day, water early.</p></div>
				<section><div><div><p>$prose</p><p>$prose</p></div></div></section></body>
				""".trimIndent(),
			)

		page.text shouldContain "tomato plants"
		page.text shouldNotContain "Tip of the day"
	}

	@Test
	fun `the retry runs below 500 characters of text and not at 500`() {
		fun text(length: Int) = "Basil grows well in sun and warm soil ".repeat(20).take(length - 1) + "."

		fun page(length: Int) =
			extract(
				"<body><div><p>${text(length)}</p></div><p class=\"newsletter\">Subscribe to the fictitious newsletter. It is free.</p></body>",
			)

		page(500).text shouldNotContain "Subscribe"
		page(500).text.length shouldBe 500
		// One character less: the retry keeps the newsletter paragraph (a prose sibling), which makes the result longer.
		page(499).text shouldContain "Subscribe"
	}

	@Test
	fun `roles that are never content are dropped in the retry as well`() {
		val page =
			extract(
				"""
				<body><form action="/search"><p role="navigation">Home. Garden. Contact.</p>
				<div><p>$prose</p><p>$prose</p></div></form></body>
				""".trimIndent(),
			)

		page.text shouldContain "tomato plants"
		page.text shouldNotContain "Contact"
	}

	@Test
	fun `the result of the retry is sanitized like the first`() {
		val page =
			extract(
				"""
				<html><head><title>T</title></head><body>
				<form id="aspnetForm" action="javascript:alert(1)" onsubmit="alert(1)"><input type="hidden" name="__VIEWSTATE" value="x">
				<div class="layout has-sidebar" onclick="alert(1)"><div><p>$prose</p><p>$prose</p>$vectors</div></div></form></body></html>
				""".trimIndent(),
			)

		assertOnlySafeMarkup(page.html)
		page.text shouldContain "tomato plants"
		page.html shouldNotContain "<form"
		page.html shouldNotContain "__VIEWSTATE"
		page.html shouldNotContain "onclick"
		page.html shouldNotContain "onsubmit"
	}

	@Test
	fun `hidden elements, navigation and footers stay out of the retry`() {
		// The form wraps everything and holds no main element, so only the retry finds the content. The sanitizer drops
		// the hidden and style attributes, so hidden text would become visible if the retry kept it.
		val page =
			extract(
				"""
				<body><form action="/search"><nav><p>Home. Garden. Shop. Contact us for more tips and tricks.</p></nav>
				<div><p>$prose</p><p>$prose</p>
				<p hidden>Hidden paragraph of the fictitious page, it must never show up in the copy.</p>
				<p style="display: none">Collapsed paragraph of the fictitious page, it must never show up in the copy.</p></div>
				<footer><p>Footer of the fictitious page with an imprint, a privacy policy and more.</p></footer></form></body>
				""".trimIndent(),
			)

		page.text shouldContain "tomato plants"
		page.text shouldNotContain "Hidden paragraph"
		page.text shouldNotContain "Collapsed paragraph"
		page.text shouldNotContain "Contact us"
		page.text shouldNotContain "imprint"
	}

	@Test
	fun `an element with a never-content role around the article is kept, other such roles inside it are dropped`() {
		val page =
			extract(
				"""
				<body><div role="complementary"><article><p>$prose</p><p>$prose</p></article>
				<div role="navigation"><p>Navigation entry of the fictitious page, with enough words to be scored.</p></div></div></body>
				""".trimIndent(),
			)

		page.text shouldContain "tomato plants"
		page.text shouldNotContain "Navigation entry"
	}

	// --- only the page's main element protects its wrappers (CR-4)

	private val comments =
		"""
		<div id="comments" class="comments-area"><ol class="comment-list">
		<li class="comment"><article class="comment-body">
		<p>First fictitious comment, with thanks, questions, and a story.</p></article></li>
		<li class="comment"><article class="comment-body">
		<p>Second fictitious comment, with more thanks, and more stories.</p></article></li>
		</ol></div>
		""".trimIndent()

	@Test
	fun `a comment section with an article per comment inside the post is dropped`() {
		// Three paragraphs are more than 500 characters, so no retry runs and the first pass alone decides.
		val page = extract("<body><article class=\"post\"><p>$prose</p><p>$prose</p><p>$prose</p>$comments</article></body>")

		page.text shouldContain "tomato plants"
		page.text shouldNotContain "fictitious comment"
	}

	@Test
	fun `a comment section with an article per comment next to the post is dropped`() {
		for (wrapper in listOf("<main id=\"main\">" to "</main>", "<div class=\"site-content\">" to "</div>")) {
			val page =
				extract(
					"<body>${wrapper.first}<article class=\"post\"><p>$prose</p><p>$prose</p><p>$prose</p></article>$comments" +
						"${wrapper.second}</body>",
				)

			page.text shouldContain "tomato plants"
			page.text shouldNotContain "fictitious comment"
		}
	}

	@Test
	fun `a related block or a complementary role with teaser articles is dropped`() {
		val teasers =
			"""
			<article class="teaser"><p>A fictitious teaser of another story, with seeds, pots, and more soil.</p></article>
			<article class="teaser"><p>Another fictitious teaser of a story, with herbs, sun, and more water.</p></article>
			""".trimIndent()
		// Not `related-posts`: "post" says the block may be content (MAYBE), so it is no unlikely block.
		for (block in listOf("<div class=\"related\">$teasers</div>", "<div role=\"complementary\">$teasers</div>")) {
			val page = extract("<body><article class=\"post\"><p>$prose</p><p>$prose</p><p>$prose</p>$block</article></body>")

			page.text shouldContain "tomato plants"
			page.text shouldNotContain "fictitious teaser"
		}
	}
}
