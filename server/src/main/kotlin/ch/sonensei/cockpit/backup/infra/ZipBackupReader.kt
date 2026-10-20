package ch.sonensei.cockpit.backup.infra

import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupManifest
import ch.sonensei.cockpit.backup.domain.InvalidBackupException
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.zip.ZipFile

/** Result of [ZipBackupReader.inspect]: [manifest] is `null` when it cannot be read; [problems] is empty for a valid backup. */
data class BackupInspection(
	val manifest: BackupManifest?,
	val problems: List<String>,
)

/**
 * Reads a backup zip that [open] has validated: the manifest is readable, its format version is supported, every
 * file it lists exists with the listed sha256 and size, and the zip holds no other files. Data that does not parse
 * (only possible in a hand-edited zip whose manifest was updated too) fails with [InvalidBackupException] naming the
 * file and line, never its content.
 */
class ZipBackupReader private constructor(
	private val zip: ZipFile,
	private val mapper: JsonMapper,
	val manifest: BackupManifest,
) : BackupDataReader,
	AutoCloseable {
	override val formatVersion: Int get() = manifest.formatVersion

	override fun has(name: String): Boolean = dataEntry(name, "json") != null || dataEntry(name, "jsonl") != null

	override fun <T : Any> json(
		name: String,
		type: TypeReference<T>,
	): T? {
		val entry = dataEntry(name, "json") ?: return null
		val value: T? =
			try {
				zip.getInputStream(entry).use { mapper.readValue(it, type) }
			} catch (e: JacksonException) {
				null
			}
		return value ?: throw InvalidBackupException(listOf("${entry.name} is not valid"))
	}

	override fun <T : Any> jsonLines(
		name: String,
		type: TypeReference<T>,
		consumer: (T) -> Unit,
	) {
		val entry = dataEntry(name, "jsonl") ?: return
		zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
			lines.forEachIndexed { index, line ->
				if (line.isBlank()) return@forEachIndexed
				val record: T? =
					try {
						mapper.readValue(line, type)
					} catch (e: JacksonException) {
						null
					}
				// The line number only: the line itself is user content.
				consumer(record ?: throw InvalidBackupException(listOf("${entry.name} line ${index + 1} is not valid")))
			}
		}
	}

	override fun file(path: String): InputStream? = zip.getEntry(BackupFormat.FILES_DIR + path)?.let(zip::getInputStream)

	override fun files(): List<String> =
		manifest.files
			.map { it.path }
			.filter { it.startsWith(BackupFormat.FILES_DIR) }
			.map { it.removePrefix(BackupFormat.FILES_DIR) }

	private fun dataEntry(
		name: String,
		extension: String,
	) = zip.getEntry("${BackupFormat.DATA_DIR}$name.$extension")

	override fun close() = zip.close()

	companion object {
		/** Opens and validates [path]. @throws InvalidBackupException listing every problem found */
		fun open(
			path: Path,
			mapper: JsonMapper,
		): ZipBackupReader {
			val zip = openZip(path)
			try {
				val manifest = readManifest(zip, mapper)
				val problems = validate(zip, manifest)
				if (problems.isNotEmpty()) throw InvalidBackupException(problems)
				return ZipBackupReader(zip, mapper, manifest)
			} catch (e: Exception) {
				zip.close()
				throw e
			}
		}

		/**
		 * Validates [path] like [open], for a validation report, and closes it again: the manifest when it can be read
		 * (also when files don't match it) and every problem found.
		 */
		fun inspect(
			path: Path,
			mapper: JsonMapper,
		): BackupInspection {
			val zip =
				try {
					openZip(path)
				} catch (e: InvalidBackupException) {
					return BackupInspection(null, e.problems)
				}
			return zip.use {
				try {
					val manifest = readManifest(zip, mapper)
					BackupInspection(manifest, validate(zip, manifest))
				} catch (e: InvalidBackupException) {
					BackupInspection(null, e.problems)
				}
			}
		}

		private fun openZip(path: Path): ZipFile =
			try {
				ZipFile(path.toFile())
			} catch (e: IOException) {
				// ZipException included: whatever it is, it cannot be read as a zip.
				throw InvalidBackupException(listOf("Not a zip file"))
			}

		private fun readManifest(
			zip: ZipFile,
			mapper: JsonMapper,
		): BackupManifest {
			val entry = zip.getEntry(BackupFormat.MANIFEST) ?: throw InvalidBackupException(listOf("${BackupFormat.MANIFEST} is missing"))
			val invalid = InvalidBackupException(listOf("${BackupFormat.MANIFEST} is not valid"))
			val tree: JsonNode =
				try {
					zip.getInputStream(entry).use { mapper.readTree(it) }
				} catch (e: JacksonException) {
					throw invalid
				} catch (e: IOException) {
					throw invalid
				}
			// The format version first: a manifest of another version need not have the fields of this one.
			val version = tree.get("formatVersion")
			if (version == null || !version.isNumber) throw invalid
			if (!version.isIntegralNumber || !version.canConvertToInt() || version.asInt() != BackupFormat.VERSION) {
				throw InvalidBackupException(
					listOf("Unsupported format version ${version.asString()} (supported: ${BackupFormat.VERSION})"),
				)
			}
			// Jackson's Kotlin module would let JSON nulls into the lists and maps of non-null types.
			if (tree.path("files").any { !it.isObject } || tree.path("counts").any { !it.isNumber }) throw invalid
			return try {
				mapper.treeToValue(tree, BackupManifest::class.java)
			} catch (e: JacksonException) {
				null
			} ?: throw invalid
		}

		private fun validate(
			zip: ZipFile,
			manifest: BackupManifest,
		): List<String> {
			val problems = mutableListOf<String>()
			val listed = manifest.files.associateBy { it.path }
			if (listed.size != manifest.files.size) problems += "${BackupFormat.MANIFEST} lists a file twice"
			val names =
				zip
					.entries()
					.asSequence()
					.filterNot { it.isDirectory }
					.map { it.name }
					.toList()
			// ZipFile.getEntry sees only one of several entries with the same name; other tools may see another one.
			names
				.groupingBy { it }
				.eachCount()
				.filterValues { it > 1 }
				.keys
				.sorted()
				.forEach { problems += "$it is in the zip more than once" }
			names
				.distinct()
				.filter { it != BackupFormat.MANIFEST && it !in listed }
				.sorted()
				.forEach { problems += "$it is not in the manifest" }
			listed.values.sortedBy { it.path }.forEach { expected ->
				if (!isSafeEntryName(expected.path)) {
					problems += "${expected.path} is not a valid path"
					return@forEach
				}
				val entry = zip.getEntry(expected.path)
				if (entry == null || entry.isDirectory) {
					problems += "${expected.path} is missing"
					return@forEach
				}
				val actual =
					try {
						zip.getInputStream(entry).use { input ->
							HashingOutputStream(OutputStream.nullOutputStream()).also { input.transferTo(it) }
						}
					} catch (e: IOException) {
						problems += "${expected.path} cannot be read"
						return@forEach
					}
				if (actual.sha256() != expected.sha256.lowercase() || actual.size != expected.size) {
					problems += "${expected.path} does not match its sha256 in the manifest"
				}
			}
			return problems
		}

		private fun isSafeEntryName(name: String): Boolean =
			runCatching {
				BackupFormat.requireSafePath(name)
				name == BackupFormat.README ||
					name.startsWith(BackupFormat.DATA_DIR) ||
					name.startsWith(BackupFormat.FILES_DIR) ||
					name.startsWith(BackupFormat.READABLE_DIR)
			}.getOrDefault(false)
	}
}
