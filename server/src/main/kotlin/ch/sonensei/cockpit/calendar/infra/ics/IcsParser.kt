package ch.sonensei.cockpit.calendar.infra.ics

/** One iCalendar property: upper-case [name], parameters by upper-case name (values unquoted), the raw [value]. */
internal data class IcsProperty(
	val name: String,
	val parameters: Map<String, String>,
	val value: String,
) {
	fun param(name: String): String? = parameters[name]
}

/** An iCalendar component (`VCALENDAR`, `VEVENT`, `VALARM`, ...) with its properties and sub-components in feed order. */
internal class IcsComponent(
	val name: String,
) {
	val properties = mutableListOf<IcsProperty>()
	val components = mutableListOf<IcsComponent>()

	fun first(name: String): IcsProperty? = properties.firstOrNull { it.name == name }

	fun all(name: String): List<IcsProperty> = properties.filter { it.name == name }

	fun children(name: String): List<IcsComponent> = components.filter { it.name == name }
}

/**
 * A tolerant reader of iCalendar text (RFC 5545 §3.1): unfolds lines, splits content lines into name, parameters and
 * value, and nests `BEGIN`/`END` blocks. Feeds in the wild are often slightly broken, so it never fails: lines without a
 * colon are skipped, an `END` without its `BEGIN` is ignored, and unclosed components are closed at the end. Text
 * values stay escaped ([unescape]).
 *
 * Its own instead of ical4j's: ical4j's builder resolves TZIDs through its time-zone registry (which may go to the
 * network) and refuses many of the malformed feeds this one reads.
 */
internal object IcsParser {
	/** The top-level components of [text] (normally one `VCALENDAR`). */
	fun parse(text: String): List<IcsComponent> {
		val roots = mutableListOf<IcsComponent>()
		val stack = ArrayDeque<IcsComponent>()
		for (line in unfold(text)) {
			val property = contentLine(line) ?: continue
			when (property.name) {
				"BEGIN" -> {
					val component = IcsComponent(property.value.trim().uppercase())
					(stack.lastOrNull()?.components ?: roots) += component
					stack.addLast(component)
				}
				"END" -> {
					val name = property.value.trim().uppercase()
					// Closes up to the matching BEGIN; an END that matches nothing is noise.
					if (stack.any { it.name == name }) {
						while (stack.removeLast().name != name) Unit
					}
				}
				else -> stack.lastOrNull()?.properties?.add(property)
			}
		}
		return roots
	}

	/** The logical lines: a line starting with a space or tab continues the previous one (CRLF or bare LF). */
	private fun unfold(text: String): List<String> {
		val lines = mutableListOf<StringBuilder>()
		for (raw in text.removePrefix("﻿").split('\n')) {
			val line = raw.removeSuffix("\r")
			if ((line.startsWith(' ') || line.startsWith('\t')) && lines.isNotEmpty()) {
				lines.last().append(line, 1, line.length)
			} else if (line.isNotBlank()) {
				lines += StringBuilder(line)
			}
		}
		return lines.map { it.toString() }
	}

	/** `NAME;PARAM=a,"b;c";OTHER=d:value`; null when the line has no name or no colon. */
	private fun contentLine(line: String): IcsProperty? {
		var i = 0
		while (i < line.length && line[i] != ';' && line[i] != ':') i++
		if (i == 0 || i == line.length) return null
		val name = line.substring(0, i).trim().uppercase()
		val parameters = mutableMapOf<String, String>()
		while (i < line.length && line[i] == ';') {
			i++
			val nameStart = i
			while (i < line.length && line[i] != '=' && line[i] != ';' && line[i] != ':') i++
			val paramName = line.substring(nameStart, i).trim().uppercase()
			val value = StringBuilder()
			if (i < line.length && line[i] == '=') {
				i++
				var quoted = false
				while (i < line.length) {
					val c = line[i]
					if (c == '"') {
						quoted = !quoted
					} else if (!quoted && (c == ';' || c == ':')) {
						break
					} else {
						value.append(c)
					}
					i++
				}
			}
			if (paramName.isNotEmpty()) parameters.putIfAbsent(paramName, value.toString())
		}
		if (i >= line.length || line[i] != ':') return null
		return IcsProperty(name, parameters, line.substring(i + 1))
	}

	/** A TEXT value without its escapes (`\n`, `\,`, `\;`, `\\`). */
	fun unescape(value: String): String {
		if ('\\' !in value) return value
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
}
