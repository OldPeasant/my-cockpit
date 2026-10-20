package ch.sonensei.cockpit.mail.sanitize

import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random

/**
 * Checks the CR-1/CR-2 fix of [InlineStyleSanitizer]:
 * - the linear comment and function-name scans give the same results as the regexes they replaced;
 * - a browser that reads the sanitized output never finds a `position` declaration in it.
 */
class InlineStyleSanitizerParityTests {
	// The regexes used before commit 3b9d491. They serve only as reference oracles here.
	private val oldFunctionCall = Regex("""(-?[a-z_][a-z0-9_-]*)\s*\(""")
	private val oldComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

	// Of the allowed functions, only these two can be built from the tokens in nameTokens.
	private val allowedInAlphabet = setOf("rgb", "calc")
	private val nameTokens =
		listOf("rgb", "calc", "url", "x", "R", "URL", "-", "_", "9", " ", "\t", "\u000b", "\n", "(", ")", "é", " ")

	/** What the old sanitizer did with `width: <value>`, for values without quotes, `;`, `:` or forbidden text. */
	private fun oldResultForWidth(value: String): String {
		var depth = 0
		for (c in value) {
			if (c == '(') depth++
			if (c == ')' && depth > 0) depth--
		}
		val trimmed = value.trim()
		val kept =
			depth == 0 &&
				trimmed.isNotEmpty() &&
				oldFunctionCall.findAll(trimmed.lowercase()).all { it.groupValues[1] in allowedInAlphabet }
		return if (kept) "width: $trimmed" else ""
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"rgb(1 2 3)",
			"rgb  (1 2 3)",
			"rgb\u000b(1 2 3)",
			"RGB(1 2 3)",
			"x-rgb(1)",
			"-rgb(1)",
			"_rgb(1)",
			"9rgb(1)",
			"-9rgb(1)",
			"rgb-(1)",
			"--(1)",
			"-(1)",
			"_(1)",
			"-_(1)",
			"rgb((1))",
			"(url)",
			"rgb(1) url",
			"calc(1px + url (x))",
			"calc(1px + rgb(1 2 3))",
			"rgb (1)",
			"urlé(x)",
			"éurl(x)",
			"a b rgb (1)",
			"rgb\n(1) x\t\t(2)",
		],
	)
	fun `function names are found like the old regex found them`(value: String) {
		InlineStyleSanitizer.sanitize("width: $value") shouldBe oldResultForWidth(value)
	}

	@Test
	fun `function names match the old regex on random values`() {
		val random = Random(20261020)
		repeat(20_000) {
			val value = (0 until random.nextInt(1, 12)).joinToString("") { nameTokens.random(random) }
			InlineStyleSanitizer.sanitize("width: $value") shouldBe oldResultForWidth(value)
		}
	}

	@ParameterizedTest
	@ValueSource(
		strings = [
			"color:/**/red",
			"color: red/**/",
			"co/*x*/lor: red",
			"color: red; /*/ */ margin: 0",
			"color: /*/*/red",
			"color: red; x: /*/",
			"color: red; /* a */ /* b */ margin: 0",
			"color: red; x: */ /*",
			"color: r/*\n*/ed",
			"color: red; /* a /* b */ margin: 0 */ padding: 0",
			"font-family: \"a/*\"; color: red; x: \"*/\"",
		],
	)
	fun `comments are stripped like the old lazy regex stripped them`(style: String) {
		// The old regex leaves no closed comment behind, so the new scan must not change its result.
		InlineStyleSanitizer.sanitize(style) shouldBe InlineStyleSanitizer.sanitize(oldComment.replace(style, " "))
	}

	@Test
	fun `comments are stripped like the old lazy regex on random styles`() {
		val tokens = listOf("/*", "*/", "/", "*", "color: red", "margin", ": 0", ";", " ", "a", "\"", "\n")
		val random = Random(4242)
		repeat(20_000) {
			val style = (0 until random.nextInt(1, 14)).joinToString("") { tokens.random(random) }
			InlineStyleSanitizer.sanitize(style) shouldBe InlineStyleSanitizer.sanitize(oldComment.replace(style, " "))
		}
	}

	@Test
	fun `a newline in a quoted value rejects the whole style, also its safe declarations`() {
		InlineStyleSanitizer.sanitize("color: red; font-family: 'a\nb'; margin: 0") shouldBe ""
	}

	@Test
	fun `a newline inside a single-quoted string cannot smuggle a position declaration`() {
		InlineStyleSanitizer.sanitize("font-family: 'a\r; position: fixed; top: 0; x: '") shouldNotContain "position"
	}

	@Test
	fun `a newline inside parentheses but outside quotes is kept as whitespace`() {
		InlineStyleSanitizer.sanitize("color: rgb(1,\n2, 3)") shouldBe "color: rgb(1,\n2, 3)"
	}

	@Test
	fun `a newline inside a comment inside a quoted value is removed with the comment`() {
		// The browser reads the output, which has neither the comment nor the newline.
		InlineStyleSanitizer.sanitize("font-family: \"a/*\n*/; position: fixed; x: \"") shouldBe
			"font-family: \"a ; position: fixed; x: \""
	}

	@Test
	fun `a style one character over the length cap is dropped whole`() {
		val style = "color: red; font-family: " + "a".repeat(InlineStyleSanitizer.MAX_STYLE_LENGTH - 24)
		style.length shouldBe InlineStyleSanitizer.MAX_STYLE_LENGTH + 1
		InlineStyleSanitizer.sanitize(style) shouldBe ""
	}

	@Test
	fun `no random style yields a position declaration as a browser reads the output`() {
		val tokens =
			listOf(
				"position: fixed",
				"POSITION:absolute",
				"color: red",
				";",
				"\"",
				"'",
				"\n",
				"\r",
				"\u000c",
				"(",
				")",
				"[",
				"]",
				"{",
				"}",
				"/*",
				"*/",
				" ",
				"a",
				":",
			)
		val random = Random(1234)
		repeat(50_000) {
			val style = (0 until random.nextInt(1, 16)).joinToString("") { tokens.random(random) }
			val output = InlineStyleSanitizer.sanitize(style)
			browserPropertyNames(output) shouldNotContain "position"
			InlineStyleSanitizer.sanitize(output) shouldBe output
		}
	}

	@Test
	fun `no smuggling-shaped random style yields a position declaration as a browser reads the output`() {
		val openers = listOf("font-family: ", "color: red; font-family: ", "a: (", "a: [", "content: x")
		val fillers = listOf("a", " ", "\n", "\r", "\u000c", "\r\n", "/*", "*/", "(", ")", "[", "{", "\"", "'")
		val closers = listOf("", ")", "]", "}", "; color: red")
		val random = Random(777)
		repeat(50_000) {
			val quote = listOf("\"", "'").random(random)
			val filler = (0 until random.nextInt(0, 5)).joinToString("") { fillers.random(random) }
			val style =
				openers.random(random) + quote + filler + "; position: fixed; x: " +
					listOf(quote, "").random(random) + closers.random(random)
			val output = InlineStyleSanitizer.sanitize(style)
			browserPropertyNames(output) shouldNotContain "position"
		}
	}

	@Test
	fun `the browser reading used above finds the declaration that the round-1 bug let through`() {
		// The pre-fix output of TW-1. Proves that the property check above can see this bug class.
		browserPropertyNames("font-family: \"a\n; position: fixed; top: 0; x: \"") shouldBe
			listOf("font-family", "position", "top", "x")
		browserPropertyNames("a: (\"x\"; position: fixed); b: [;]; c: {;}; d: /* ; */ 1") shouldBe listOf("a", "b", "c", "d")
	}

	/**
	 * A simplified CSS Syntax 3 reading of a declaration list, independent of the sanitizer: a comment
	 * outside a string separates tokens, a string ends at its quote or at a newline (bad string), (),
	 * [] and {} nest, and `;` at the top level ends a declaration.
	 */
	private fun browserPropertyNames(css: String): List<String> {
		val names = mutableListOf<String>()
		val current = StringBuilder()
		val closers = ArrayDeque<Char>()
		var quote: Char? = null
		var i = 0
		while (i < css.length) {
			val c = css[i]
			when {
				quote != null -> {
					if (c == quote || c == '\n' || c == '\r' || c == '\u000c') quote = null
					current.append(c)
				}
				css.startsWith("/*", i) -> {
					val end = css.indexOf("*/", i + 2)
					current.append(' ')
					i = if (end < 0) css.length else end + 2
					continue
				}
				c == '"' || c == '\'' -> {
					quote = c
					current.append(c)
				}
				c in blockClosers -> {
					closers.addLast(blockClosers.getValue(c))
					current.append(c)
				}
				closers.isNotEmpty() && c == closers.last() -> {
					closers.removeLast()
					current.append(c)
				}
				c == ';' && closers.isEmpty() -> {
					names += propertyNameOf(current)
					current.clear()
				}
				else -> current.append(c)
			}
			i++
		}
		names += propertyNameOf(current)
		return names
	}

	private val blockClosers = mapOf('(' to ')', '[' to ']', '{' to '}')

	private fun propertyNameOf(declaration: CharSequence): String {
		val name = declaration.toString().substringBefore(':', "")
		return name.trim().lowercase()
	}
}
