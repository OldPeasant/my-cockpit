package ch.sonensei.cockpit.backup.infra

import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupManifest
import ch.sonensei.cockpit.backup.domain.InvalidBackupException
import tools.jackson.core.JacksonException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Reads a backup zip that [open] has validated: the manifest is readable, its format version is supported, every
 * file it lists exists with the listed sha256 and size, and the zip holds no other files.
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
	): T? = dataEntry(name, "json")?.let { entry -> zip.getInputStream(entry).use { mapper.readValue(it, type) } }

	override fun <T : Any> jsonLines(
		name: String,
		type: TypeReference<T>,
		consumer: (T) -> Unit,
	) {
		val entry = dataEntry(name, "jsonl") ?: return
		zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
			lines.filter { it.isNotBlank() }.forEach { consumer(mapper.readValue(it, type)) }
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
			val zip =
				try {
					ZipFile(path.toFile())
				} catch (e: ZipException) {
					throw InvalidBackupException(listOf("Not a zip file"))
				}
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

		private fun readManifest(
			zip: ZipFile,
			mapper: JsonMapper,
		): BackupManifest {
			val entry = zip.getEntry(BackupFormat.MANIFEST) ?: throw InvalidBackupException(listOf("${BackupFormat.MANIFEST} is missing"))
			val manifest: BackupManifest =
				try {
					// A manifest that is JSON `null` reads as null.
					zip.getInputStream(entry).use { mapper.readValue<BackupManifest?>(it, BackupManifest::class.java) }
				} catch (e: JacksonException) {
					null
				} ?: throw InvalidBackupException(listOf("${BackupFormat.MANIFEST} is not valid"))
			if (manifest.formatVersion != BackupFormat.VERSION) {
				throw InvalidBackupException(
					listOf("Unsupported format version ${manifest.formatVersion} (supported: ${BackupFormat.VERSION})"),
				)
			}
			return manifest
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
