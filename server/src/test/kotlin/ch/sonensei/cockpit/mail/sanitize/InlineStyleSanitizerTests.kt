package ch.sonensei.cockpit.mail.sanitize

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class InlineStyleSanitizerTests {
	@Test
	fun `safe declarations are kept and normalized`() {
		InlineStyleSanitizer.sanitize("  COLOR :  Red ;font-size:14px;;  ") shouldBe "color: Red; font-size: 14px"
	}

	@Test
	fun `empty and blank styles give an empty string`() {
		InlineStyleSanitizer.sanitize("") shouldBe ""
		InlineStyleSanitizer.sanitize("   ;  ; ") shouldBe ""
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"color",
			": red",
			"color:",
			"color:   ",
			"--brand: red",
			"col\\or: red",
			"1color: red",
			"co lor: red",
		],
	)
	fun `malformed declarations are dropped`(style: String) {
		InlineStyleSanitizer.sanitize(style) shouldBe ""
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"position: fixed",
			"Position: absolute",
			"POSITION:relative !important",
			"behavior: something",
			"-ms-behavior: something",
			"-moz-binding: none",
		],
	)
	fun `forbidden properties are dropped`(style: String) {
		InlineStyleSanitizer.sanitize("color: red; $style; margin: 0") shouldBe "color: red; margin: 0"
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"background: url(https://tracker.example/a.png)",
			"background: URL( 'https://tracker.example/a.png' )",
			"background: url\n(https://tracker.example/a.png)",
			"background-image: image-set('https://tracker.example/a.png' 1x)",
			"background-image: -webkit-image-set('https://tracker.example/a.png' 1x)",
			"background-image: image('https://tracker.example/a.png')",
			"background: element(#x)",
			"list-style-image: url(\"javascript:alert(1)\")",
			"width: expression(alert(1))",
			"width: ex/**/pression(alert(1))",
			"color: var(--x)",
			"color: env(safe-area-inset-top)",
			"content: attr(title)",
			"background: paint(x)",
			"background: linear-gradient(red, blue), url(https://tracker.example/a.png)",
			"background: \\75 rl(https://tracker.example/a.png)",
			"color: re\\64",
			"font-family: <x>",
			"color: red /* unclosed comment",
			"color: red */",
			"font-family: \"@import\"",
			"background: javascript:alert(1)",
		],
	)
	fun `declarations that load resources or hide their intent are dropped`(declaration: String) {
		InlineStyleSanitizer.sanitize("color: red; $declaration; margin: 0") shouldBe "color: red; margin: 0"
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"color: rgb(10, 20, 30)",
			"color: rgba(10, 20, 30, 0.5)",
			"color: hsl(120, 50%, 50%)",
			"color: oklch(0.7 0.1 200)",
			"width: calc(100% - 20px)",
			"width: clamp(200px, 50%, 600px)",
			"width: min(100%, 600px)",
			"background: linear-gradient(to right, #fff, #000)",
			"background-image: -webkit-linear-gradient(top, #fff, #000)",
			"background: repeating-radial-gradient(circle, red, blue 10px)",
			"transform: translateX(10px) rotate(5deg) scale(1.1)",
			"font-family: \"Helvetica Neue\", Arial, sans-serif",
			"border-left: 1px #ccc solid",
			"color: #333 !important",
			"margin: 0 auto",
		],
	)
	fun `declarations with value-only functions and plain values are kept`(declaration: String) {
		InlineStyleSanitizer.sanitize(declaration) shouldBe declaration
	}

	@Test
	fun `comments are removed, also when they split a property name`() {
		InlineStyleSanitizer.sanitize("color:/* note */red; pos/**/ition: fixed") shouldBe "color: red"
	}

	@Test
	fun `a comment opened inside a string cannot hide a following declaration`() {
		// Removing the comment text before splitting leaves only the harmless remainder.
		val style = InlineStyleSanitizer.sanitize("content: \"/*\"; position: fixed; x: \"*/\"; color: red")
		style shouldNotContain "position"
		style shouldNotContain "fixed"
	}

	@Test
	fun `semicolons inside quotes and parentheses do not split declarations`() {
		InlineStyleSanitizer.sanitize("font-family: \"a;b\", 'c;d'; color: rgb(1, 2, 3)") shouldBe
			"font-family: \"a;b\", 'c;d'; color: rgb(1, 2, 3)"
	}

	@Test
	fun `an unclosed quote drops the trailing declaration but keeps earlier ones`() {
		InlineStyleSanitizer.sanitize("color: red; font-family: \"Arial; position: fixed") shouldBe "color: red"
	}

	@Test
	fun `an unclosed parenthesis drops the trailing declaration but keeps earlier ones`() {
		InlineStyleSanitizer.sanitize("color: red; width: calc(100% - 2px; position: fixed") shouldBe "color: red"
	}

	@Test
	fun `a stray closing parenthesis does not merge the next declaration`() {
		InlineStyleSanitizer.sanitize("color: red); position: fixed; margin: 0") shouldBe "color: red); margin: 0"
	}

	@Test
	fun `a value may contain a colon`() {
		InlineStyleSanitizer.sanitize("font-family: a:b") shouldBe "font-family: a:b"
	}

	@ParameterizedTest
	@ValueSource(strings = ["\n", "\r", "\u000c"])
	fun `a newline inside a quoted string cannot smuggle a position declaration`(newline: String) {
		// CSS ends a string at an unescaped newline (bad-string) and then starts a new declaration
		// at the next ';', so a browser would apply `position: fixed` here (CSS Syntax 3, 4.3.5).
		val style = InlineStyleSanitizer.sanitize("font-family: \"a$newline; position: fixed; top: 0; x: \"")
		style shouldNotContain "position"
	}
}
