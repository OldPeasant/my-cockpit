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
			"rgb", "rgba", "hsl", "hsla", "hwb", "lab", "lch", "oklab", "oklch",
			"calc", "min", "max", "clamp",
			"linear-gradient", "radial-gradient", "conic-gradient",
			"repeating-linear-gradient", "repeating-radial-gradient", "repeating-conic-gradient",
			"-webkit-linear-gradient", "-webkit-radial-gradient",
			"translate", "translatex", "translatey", "rotate", "scale", "scalex", "scaley",
			"skew", "skewx", "skewy", "matrix",
		)

	private val propertyName = Regex("^-?[a-z][a-z0-9-]*$")
	private val functionCall = Regex("""(-?[a-z_][a-z0-9_-]*)\s*\(""")
	private val forbiddenInValue = listOf("\\", "<", ">", "/*", "*/", "@", "javascript:", "expression")
	private val comment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

	/** Returns the safe declarations of [style], or an empty string if none is left. */
	fun sanitize(style: String): String =
		splitDeclarations(comment.replace(style, " "))
			.mapNotNull(::sanitizeDeclaration)
			.joinToString("; ")

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
				functionCall.findAll(lowerValue).all { it.groupValues[1] in allowedFunctions }
		return if (safe) "$property: $value" else null
	}

	/** Splits on `;` outside quotes and parentheses; drops a declaration with an unclosed quote. */
	private fun splitDeclarations(style: String): List<String> {
		val declarations = mutableListOf<String>()
		val current = StringBuilder()
		var quote: Char? = null
		var depth = 0
		for (c in style) {
			when {
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
