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
