package ch.sonensei.cockpit.files.domain

import ch.sonensei.cockpit.core.web.InvalidRequestException

/**
 * Validation of file and folder names. A name is one path segment: it never contains a separator or a control
 * character and is never `.` or `..`, so no name can point outside its folder (in a zip, a backup or a download).
 */
object FileNames {
	const val MAX_LENGTH = 255

	/**
	 * The trimmed [name], or an [InvalidRequestException] (400) for [field] when it is blank, too long, `.`/`..`, or
	 * contains `/`, `\`, NUL or another control character.
	 */
	fun validate(
		name: String,
		field: String = "name",
	): String {
		val trimmed = name.trim()
		val problem =
			when {
				trimmed.isEmpty() -> "must not be blank"
				trimmed.length > MAX_LENGTH -> "must be at most $MAX_LENGTH characters"
				trimmed == "." || trimmed == ".." -> "must not be . or .."
				trimmed.any { it == '/' || it == '\\' } -> "must not contain / or \\"
				trimmed.any { it.isISOControl() } -> "must not contain control characters"
				else -> null
			}
		if (problem != null) throw InvalidRequestException(field, problem)
		return trimmed
	}

	/** [name] with ` (n)` before its extension, for the n-th copy (`report.pdf` → `report (2).pdf`). */
	fun numbered(
		name: String,
		n: Int,
	): String {
		val dot = name.lastIndexOf('.')
		val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
		val suffix = " ($n)"
		return (base.take((MAX_LENGTH - suffix.length - ext.length).coerceAtLeast(1)) + suffix + ext).take(MAX_LENGTH)
	}
}
