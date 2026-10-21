package ch.sonensei.cockpit.bookmarks

import ch.sonensei.cockpit.bookmarks.domain.BookmarkUrls
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Normalizing URLs as users paste or share them ([BookmarkUrls]), without Spring. */
class BookmarkUrlsTests {
	@Test
	fun `valid URIs are kept as they are (only trimmed)`() {
		for (url in listOf(
			"https://example.org",
			"https://example.org/",
			"HTTP://Example.ORG:8080/a/b;c?x=1&y=%20#top",
			"https://user:pw@example.org/",
			"http://127.0.0.1:9999/path",
			"http://[::1]:8080/x",
			"https://example.org/?q=a/b?c",
			"https://example.org.:443/",
			"https://example.org:/",
		)) {
			withClue(url) { BookmarkUrls.normalize("  $url\n") shouldBe url }
		}
	}

	@Test
	fun `IDN hosts become ASCII and characters a URI does not allow are percent-encoded`() {
		BookmarkUrls.normalize("https://bücher.de/x") shouldBe "https://xn--bcher-kva.de/x"
		BookmarkUrls.normalize("https://a_b.example.com/") shouldBe "https://a_b.example.com/"
		BookmarkUrls.normalize("https://example.org/a b") shouldBe "https://example.org/a%20b"
		BookmarkUrls.normalize("https://example.org/?q=a|b") shouldBe "https://example.org/?q=a%7Cb"
		BookmarkUrls.normalize("https://example.org/?f[0]=x&g={1}") shouldBe "https://example.org/?f%5B0%5D=x&g=%7B1%7D"
		BookmarkUrls.normalize("https://example.org/café#été") shouldBe "https://example.org/caf%C3%A9#%C3%A9t%C3%A9"
		BookmarkUrls.normalize("https://example.org/🍅") shouldBe "https://example.org/%F0%9F%8D%85"
		BookmarkUrls.normalize("https://example.org/100%") shouldBe "https://example.org/100%25"
		BookmarkUrls.normalize("https://example.org/a#b#c") shouldBe "https://example.org/a#b%23c"
		BookmarkUrls.normalize("https://example.org/a\\b") shouldBe "https://example.org/a%5Cb"
	}

	@Test
	fun `percent escapes are kept in either case, a percent without two hex digits is encoded`() {
		BookmarkUrls.normalize("https://example.org/caf%c3%A9?q=%7e%7E#%aF") shouldBe "https://example.org/caf%c3%A9?q=%7e%7E#%aF"
		BookmarkUrls.normalize("https://example.org/%zz") shouldBe "https://example.org/%25zz"
		BookmarkUrls.normalize("https://example.org/%4g") shouldBe "https://example.org/%254g"
		BookmarkUrls.normalize("https://example.org/a%4") shouldBe "https://example.org/a%254"
		BookmarkUrls.normalize("https://example.org/?q=%") shouldBe "https://example.org/?q=%25"
	}

	@Test
	fun `the readable form has a Unicode host and decoded path, query and fragment`() {
		BookmarkUrls.readable("https://xn--bcher-kva.de/Stra%C3%9Fe?q=caf%C3%A9%20au%20lait#%C3%A9t%C3%A9") shouldBe
			"https://bücher.de/Straße?q=café au lait#été"
		BookmarkUrls.readable("https://user@xn--bcher-kva.de:8443/%F0%9F%8D%85") shouldBe "https://user@bücher.de:8443/🍅"
		BookmarkUrls.readable("http://[::1]:8080/a%20b") shouldBe "http://[::1]:8080/a b"
		// The readable form of a normalized URL.
		BookmarkUrls.readable(BookmarkUrls.normalize("https://bücher.de/Straße")!!) shouldBe "https://bücher.de/Straße"
	}

	@Test
	fun `escapes that are no valid UTF-8 or decode to control characters stay encoded in the readable form`() {
		BookmarkUrls.readable("https://example.org/a%FFb%C3") shouldBe null
		BookmarkUrls.readable("https://example.org/a%00b%0A?q=%7F") shouldBe null
		BookmarkUrls.readable("https://example.org/%FF%20x%C3%A9") shouldBe "https://example.org/%FF%20xé"
		BookmarkUrls.readable("https://example.org/100%25") shouldBe "https://example.org/100%"
	}

	@Test
	fun `the readable form decodes lowercase escapes and keeps incomplete ones and a port as they are`() {
		BookmarkUrls.readable("https://example.org/caf%c3%a9") shouldBe "https://example.org/café"
		// A trailing escape without two hex digits is no escape: kept, the rest is still decoded.
		BookmarkUrls.readable("https://example.org/a%20b%4") shouldBe "https://example.org/a b%4"
		BookmarkUrls.readable("https://example.org/%") shouldBe null
		BookmarkUrls.readable("https://xn--bcher-kva.de:8080") shouldBe "https://bücher.de:8080"
	}

	@Test
	fun `a URL that already reads as it is has no separate readable form`() {
		BookmarkUrls.readable("https://example.org/a/b?x=1#top") shouldBe null
		BookmarkUrls.readable("not a url") shouldBe null
	}

	@Test
	fun `URLs that are not absolute http(s) URLs with a valid host are refused`() {
		for (url in listOf(
			"",
			"example.org",
			"ftp://example.org/",
			"javascript:alert(1)",
			"https:example.org",
			"https:/example.org",
			"https:///no-host",
			"https://exa mple.org/",
			"https://example..org/",
			"https://example.org:80a/",
			"https://[::1/",
			"https://[::1]x/",
			"https://[not-ipv6]/",
			"https://example.org/\u0000",
			"https://example.org/a\nb",
			"https://exa<mple.org/",
		)) {
			withClue(url) { BookmarkUrls.normalize(url) shouldBe null }
		}
	}
}
