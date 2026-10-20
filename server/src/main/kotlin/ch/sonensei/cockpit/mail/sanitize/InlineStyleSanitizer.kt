package ch.sonensei.cockpit.mail.sanitize

/**
 * Filters the declarations of an inline `style` attribute from untrusted HTML mail.
 *
 * Works per declaration: a declaration that could position content over the viewer, load a
 * resource (`url()`, `image-set()`, ...) or hide its intent (CSS escapes, comments, at-rules) is
 * dropped; everything else (colors, fonts, spacing, widths, borders, gradients) is kept.
 */
internal object InlineStyleSanitizer {
	private val forbiddenProperties = setOf("position", "behavior", "-ms-behavior", "-moz-binding")

	// CSS functions that only compute values. Anything else (url, image, image-set, element,
	// expression, src, paint, var, env, attr, ...) drops the declaration.
	private val allowedFunctions =
		setOf(
			"rgb",
			"rgba",
			"hsl",
			"hsla",
			"hwb",
			"lab",
			"lch",
			"oklab",
			"oklch",
			"calc",
			"min",
			"max",
			"clamp",
			"linear-gradient",
			"radial-gradient",
			"conic-gradient",
			"repeating-linear-gradient",
			"repeating-radial-gradient",
			"repeating-conic-gradient",
			"-webkit-linear-gradient",
			"-webkit-radial-gradient",
			"translate",
			"translatex",
			"translatey",
			"rotate",
			"scale",
			"scalex",
			"scaley",
			"skew",
			"skewx",
			"skewy",
			"matrix",
		)

	private val propertyName = Regex("^-?[a-z][a-z0-9-]*$")
	private val regexWhitespace = setOf(' ', '\t', '\n', '\u000b', '\u000c', '\r')
	private val forbiddenInValue = listOf("\\", "<", ">", "/*", "*/", "@", "javascript:", "expression")
	private val cssNewlines = setOf('\n', '\r', '\u000c')

	// Real inline styles, also in newsletters, stay far below this. A longer attribute is dropped
	// whole, which also bounds the cost of the per-declaration regex checks on hostile input.
	internal const val MAX_STYLE_LENGTH = 16 * 1024

	/** Returns the safe declarations of [style], or an empty string if none is left. */
	fun sanitize(style: String): String {
		if (style.length > MAX_STYLE_LENGTH) return ""
		return splitDeclarations(stripComments(style))
			.mapNotNull(::sanitizeDeclaration)
			.joinToString("; ")
	}

	// Replaces every closed CSS comment with a space in one linear pass (indexOf, no backtracking
	// regex). An unclosed comment opener is kept, so the declaration holding it is dropped by
	// forbiddenInValue.
	private fun stripComments(style: String): String {
		val result = StringBuilder(style.length)
		var index = 0
		while (index < style.length) {
			val start = style.indexOf("/*", index)
			val end = if (start < 0) -1 else style.indexOf("*/", start + 2)
			if (end < 0) {
				result.append(style, index, style.length)
				break
			}
			result.append(style, index, start).append(' ')
			index = end + 2
		}
		return result.toString()
	}

	private fun sanitizeDeclaration(declaration: String): String? {
		val colon = declaration.indexOf(':')
		if (colon <= 0) return null
		val property = declaration.substring(0, colon).trim().lowercase()
		val value = declaration.substring(colon + 1).trim()
		val lowerValue = value.lowercase()
		val safe =
			value.isNotEmpty() &&
				propertyName.matches(property) &&
				property !in forbiddenProperties &&
				forbiddenInValue.none { it in lowerValue } &&
				functionNames(lowerValue).all { it in allowedFunctions }
		return if (safe) "$property: $value" else null
	}

	/**
	 * Names of the function calls in [value]: like matching `(-?[a-z_][a-z0-9_-]*)\s*\(` with
	 * `findAll`, but linear. For every `(`, take the run of name characters before it (skipping
	 * whitespace) and its leftmost position where a name can start.
	 */
	private fun functionNames(value: String): List<String> {
		val names = mutableListOf<String>()
		var paren = value.indexOf('(')
		while (paren >= 0) {
			var end = paren
			while (end > 0 && value[end - 1] in regexWhitespace) end--
			var start = end
			while (start > 0 && isNameChar(value[start - 1])) start--
			for (i in start until end) {
				val c = value[i]
				if (isNameStart(c) || (c == '-' && i + 1 < end && isNameStart(value[i + 1]))) {
					names += value.substring(i, end)
					break
				}
			}
			paren = value.indexOf('(', paren + 1)
		}
		return names
	}

	private fun isNameStart(c: Char) = c in 'a'..'z' || c == '_'

	private fun isNameChar(c: Char) = isNameStart(c) || c in '0'..'9' || c == '-'

	/**
	 * Splits on `;` outside quotes and parentheses; drops a declaration with an unclosed quote.
	 *
	 * A CSS string ends at a raw newline (CR and FF count as newlines too): the browser turns it into
	 * a bad string and parses what follows as new declarations (CSS Syntax 3, 4.3.5). A newline inside
	 * a quote therefore rejects the whole style, so nothing can hide behind it.
	 */
	private fun splitDeclarations(style: String): List<String> {
		val declarations = mutableListOf<String>()
		val current = StringBuilder()
		var quote: Char? = null
		var depth = 0
		for (c in style) {
			when {
				quote != null && c in cssNewlines -> return emptyList()
				quote != null -> if (c == quote) quote = null
				c == '"' || c == '\'' -> quote = c
				c == '(' -> depth++
				c == ')' -> if (depth > 0) depth--
				c == ';' && depth == 0 -> {
					declarations += current.toString()
					current.clear()
					continue
				}
			}
			current.append(c)
		}
		if (quote == null && depth == 0) declarations += current.toString()
		return declarations.filter { it.isNotBlank() }
	}
}
