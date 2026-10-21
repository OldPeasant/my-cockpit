package ch.sonensei.cockpit.bookmarks

import ch.sonensei.cockpit.bookmarks.domain.PageMetadata
import ch.sonensei.cockpit.bookmarks.infra.FetchedPage
import ch.sonensei.cockpit.bookmarks.infra.PageMetadataParser
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

class PageMetadataParserTests {
	private fun parse(
		html: String,
		url: String = "https://blog.example.org/posts/1?ref=x",
		charset: String? = "utf-8",
	): PageMetadata = PageMetadataParser.parse(FetchedPage(URI(url), charset, html.toByteArray(Charsets.UTF_8)))

	@Test
	fun `OpenGraph title and description win over the HTML title and meta description`() {
		val metadata =
			parse(
				"""
				<html><head>
				<title>Plain title</title>
				<meta name="description" content="Plain description">
				<meta property="og:title" content="  Open Graph title ">
				<meta property="og:description" content="Open Graph description">
				<link rel="icon" href="/static/icon.png">
				</head><body>Hello</body></html>
				""".trimIndent(),
			)

		metadata shouldBe
			PageMetadata(
				title = "  Open Graph title ",
				description = "Open Graph description",
				favicon = "https://blog.example.org/static/icon.png",
			)
	}

	@Test
	fun `without OpenGraph the HTML title, meta description and the site's favicon_ico are used`() {
		val metadata = parse("<html><head><title>Plain title</title><meta name=description content='About gardening'></head></html>")

		metadata shouldBe PageMetadata("Plain title", "About gardening", "https://blog.example.org/favicon.ico")
	}

	@Test
	fun `Twitter cards are a fallback`() {
		val metadata =
			parse("<head><meta name='twitter:title' content='Card title'><meta name='twitter:description' content='Card text'></head>")

		metadata.title shouldBe "Card title"
		metadata.description shouldBe "Card text"
	}

	@Test
	fun `an empty page has no title or description`() {
		parse("") shouldBe PageMetadata(null, null, "https://blog.example.org/favicon.ico")
	}

	@Test
	fun `shortcut icon and apple-touch-icon are found, relative to the base href`() {
		parse("<head><base href='https://cdn.example.org/assets/'><link rel='shortcut icon' href='fav.ico'></head>").favicon shouldBe
			"https://cdn.example.org/assets/fav.ico"
		parse("<head><link rel='apple-touch-icon' href='//static.example.org/touch.png'></head>").favicon shouldBe
			"https://static.example.org/touch.png"
		// rel=icon is preferred over apple-touch-icon whatever their order.
		parse("<head><link rel='apple-touch-icon' href='/touch.png'><link rel='ICON' href='/icon.svg'></head>").favicon shouldBe
			"https://blog.example.org/icon.svg"
	}

	@Test
	fun `data and javascript favicons are ignored in favour of favicon_ico, credentials dropped`() {
		parse("<head><link rel='icon' href='data:image/png;base64,AAAA'></head>").favicon shouldBe "https://blog.example.org/favicon.ico"
		parse("<head><link rel='icon' href='javascript:alert(1)'></head>").favicon shouldBe "https://blog.example.org/favicon.ico"
		// Credentials in a favicon URL are dropped.
		parse("<head><link rel='icon' href='https://user:pw@other.example/x.ico'></head>").favicon shouldBe "https://other.example/x.ico"
	}

	@Test
	fun `the charset of the Content-Type is used, an unknown one falls back to the page's meta charset`() {
		val latin1 = "<head><title>Café</title></head>".toByteArray(Charsets.ISO_8859_1)
		PageMetadataParser.parse(FetchedPage(URI("https://example.org/"), "ISO-8859-1", latin1)).title shouldBe "Café"

		val declared = "<head><meta charset='iso-8859-1'><title>Café</title></head>".toByteArray(Charsets.ISO_8859_1)
		PageMetadataParser.parse(FetchedPage(URI("https://example.org/"), "no-such-charset", declared)).title shouldBe "Café"
	}
}
