package ch.sonensei.cockpit.calendar.infra.caldav

/**
 * A minimal iCalendar (RFC 5545) object model on content lines: components with their properties in order, each
 * property with its parameters and raw (still escaped) value. CalDAV resources are changed in place through it, so
 * properties and components the cockpit does not know (X- properties, VTODOs, alarm details, ...) survive a write.
 */
internal class IcsComponent(
	val name: String,
	val properties: MutableList<IcsProperty> = mutableListOf(),
	val components: MutableList<IcsComponent> = mutableListOf(),
) {
	fun property(name: String): IcsProperty? = properties.firstOrNull { it.name == name }

	fun all(name: String): List<IcsProperty> = properties.filter { it.name == name }

	/** The unescaped text of property [name]; null when it is missing. */
	fun text(name: String): String? = property(name)?.text

	fun remove(name: String) {
		properties.removeAll { it.name == name }
	}

	/**
	 * Replaces every property [name] by one with the raw [value] and [params] (raw values, see [quoteParam]), where the
	 * first one was; removes them for a null [value].
	 */
	fun set(
		name: String,
		value: String?,
		params: List<Pair<String, String>> = emptyList(),
	) {
		val index = properties.indexOfFirst { it.name == name }
		remove(name)
		if (value == null) return
		val property = IcsProperty(name, params.toMutableList(), value)
		if (index < 0) properties += property else properties.add(index, property)
	}

	/** Sets text property [name] (escaped); removes it for null. */
	fun setText(
		name: String,
		text: String?,
	) = set(name, text?.let(::escapeText))

	fun children(name: String): List<IcsComponent> = components.filter { it.name == name }

	/** This component as iCalendar text with CRLF line ends, lines folded at 75 octets. */
	fun format(): String = StringBuilder().also { write(it) }.toString()

	private fun write(out: StringBuilder) {
		fold("BEGIN:$name", out)
		properties.forEach { fold(it.format(), out) }
		components.forEach { it.write(out) }
		fold("END:$name", out)
	}

	companion object {
		/**
		 * Parses iCalendar [text] into its top-level components (usually one VCALENDAR). Lenient: LF or CRLF line ends,
		 * blank lines and lines that are no content lines are skipped.
		 *
		 * @throws IllegalArgumentException when BEGIN and END do not match
		 */
		fun parse(text: String): List<IcsComponent> {
			val roots = mutableListOf<IcsComponent>()
			val stack = ArrayDeque<IcsComponent>()
			for (line in unfold(text)) {
				val property = IcsProperty.parse(line) ?: continue
				when (property.name) {
					"BEGIN" -> stack.addLast(IcsComponent(property.value.trim().uppercase()))
					"END" -> {
						val done = stack.removeLastOrNull()
						require(done != null && done.name == property.value.trim().uppercase()) { "Unbalanced END in iCalendar data" }
						stack.lastOrNull()?.components?.add(done) ?: roots.add(done)
					}
					else -> stack.lastOrNull()?.properties?.add(property)
				}
			}
			require(stack.isEmpty()) { "Missing END in iCalendar data" }
			return roots
		}

		private fun unfold(text: String): List<String> {
			val lines = mutableListOf<StringBuilder>()
			for (raw in text.split("\r\n", "\n", "\r")) {
				if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) {
					lines.last().append(raw, 1, raw.length)
				} else if (raw.isNotBlank()) {
					lines += StringBuilder(raw)
				}
			}
			return lines.map { it.toString() }
		}

		/** Appends [line] folded into lines of at most 75 octets (UTF-8), never inside a character. */
		private fun fold(
			line: String,
			out: StringBuilder,
		) {
			var octets = 0
			var i = 0
			while (i < line.length) {
				val chars = Character.charCount(line.codePointAt(i))
				val size = line.substring(i, i + chars).toByteArray(Charsets.UTF_8).size
				if (octets + size > MAX_OCTETS) {
					out.append("\r\n ")
					// The leading space counts.
					octets = 1
				}
				out.append(line, i, i + chars)
				octets += size
				i += chars
			}
			out.append("\r\n")
		}

		private const val MAX_OCTETS = 75
	}
}

/**
 * One content line: `NAME;PARAM=value:value`. [value] is raw (TEXT values still escaped, see [text]), and so are the
 * parameter values in [params] (quotes kept, see [param] and [quoteParam]).
 */
internal class IcsProperty(
	val name: String,
	val params: MutableList<Pair<String, String>>,
	val value: String,
) {
	/** The value of parameter [name] (case-insensitive), unquoted. */
	fun param(name: String): String? = params.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second?.replace(QUOTE, "")

	/** Sets parameter [name] to [value] (quoted when needed) where it was; removes it for null. */
	fun setParam(
		name: String,
		value: String?,
	) {
		val index = params.indexOfFirst { it.first.equals(name, ignoreCase = true) }
		params.removeAll { it.first.equals(name, ignoreCase = true) }
		if (value != null) params.add(if (index < 0) params.size else index, name to quoteParam(value))
	}

	/** The value as TEXT (RFC 5545 §3.3.11): `\n`, `\,`, `\;` and `\\` unescaped. */
	val text: String get() = unescapeText(value)

	fun format(): String =
		buildString {
			append(name)
			params.forEach { (key, v) -> append(';').append(key).append('=').append(v) }
			append(':').append(value.replace("\r", "").replace("\n", "\\n"))
		}

	companion object {
		/** Parses one unfolded content line; null when it is none (no name or no colon). */
		fun parse(line: String): IcsProperty? {
			var i = 0
			while (i < line.length && line[i] != ';' && line[i] != ':') i++
			if (i == 0 || i == line.length) return null
			val name = line.substring(0, i).trim().uppercase()
			val params = mutableListOf<Pair<String, String>>()
			while (i < line.length && line[i] == ';') {
				i++
				val eq = line.indexOf('=', i)
				if (eq < 0) return null
				val key = line.substring(i, eq).trim().uppercase()
				i = eq + 1
				val start = i
				// A parameter may have several values (a,b) and quoted ones ("a:b"); kept as written.
				while (i < line.length && line[i] != ';' && line[i] != ':') {
					if (line[i] == '"') {
						val close = line.indexOf('"', i + 1)
						if (close < 0) return null
						i = close + 1
					} else {
						i++
					}
				}
				params += key to line.substring(start, i)
			}
			if (i >= line.length || line[i] != ':') return null
			return IcsProperty(name, params, line.substring(i + 1))
		}
	}
}

private const val QUOTE = "\""

/** [value] as a parameter value: quoted when it contains `:`, `;` or `,` (quotes and line breaks inside are dropped). */
internal fun quoteParam(value: String): String {
	val clean = value.replace(QUOTE, "").replace("\r", "").replace("\n", " ")
	return if (clean.any { it == ':' || it == ';' || it == ',' }) QUOTE + clean + QUOTE else clean
}

internal fun escapeText(text: String): String =
	text
		.replace("\\", "\\\\")
		.replace(";", "\\;")
		.replace(",", "\\,")
		.replace("\r\n", "\n")
		.replace("\r", "\n")
		.replace("\n", "\\n")

internal fun unescapeText(value: String): String {
	val out = StringBuilder(value.length)
	var i = 0
	while (i < value.length) {
		val c = value[i]
		if (c == '\\' && i + 1 < value.length) {
			val next = value[i + 1]
			out.append(if (next == 'n' || next == 'N') '\n' else next)
			i += 2
		} else {
			out.append(c)
			i++
		}
	}
	return out.toString()
}
