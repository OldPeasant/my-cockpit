package ch.sonensei.cockpit.backup.infra

import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupFileEntry
import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupManifest
import ch.sonensei.cockpit.backup.domain.BackupReadableWriter
import ch.sonensei.cockpit.backup.domain.BackupSecrets
import tools.jackson.databind.json.JsonMapper
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streams a backup zip to [out], one entry at a time, hashing every entry on the way. Contributors write through
 * [data] and [readable]; [finish] adds `README.md` and `manifest.json` and closes the zip. Not thread-safe.
 *
 * [mapper] must be canonical (sorted properties and map keys, see [BackupFormat.canonical]). With a [passphrase], the
 * secrets ([BackupDataWriter.secret]) are kept in memory and [finish] writes them encrypted to
 * [BackupSecrets.PATH] (also when there are none, so a restore can check the passphrase); without, they are dropped.
 */
class ZipBackupWriter(
	out: OutputStream,
	private val mapper: JsonMapper,
	private val createdAt: Instant,
	private val passphrase: String? = null,
) : AutoCloseable {
	private val zip = ZipOutputStream(out.buffered())
	private val paths = mutableSetOf<String>()
	private val entries = mutableListOf<BackupFileEntry>()
	private val counts = sortedMapOf<String, Long>()
	private val secrets = sortedMapOf<String, Any>()

	/** Data names and record counts written so far. */
	val recordCounts: Map<String, Long> get() = counts.toMap()

	val data: BackupDataWriter =
		object : BackupDataWriter {
			override fun json(
				name: String,
				value: Any,
			) {
				val count =
					when (value) {
						is Collection<*> -> value.size.toLong()
						is Array<*> -> value.size.toLong()
						else -> 1L
					}
				dataEntry(name, "json") { mapper.writerWithDefaultPrettyPrinter().writeValue(it, value) }
				counts[name] = count
			}

			override fun jsonLines(
				name: String,
				records: Sequence<Any>,
			) {
				var count = 0L
				dataEntry(name, "jsonl") { out ->
					records.forEach { record ->
						out.write(mapper.writeValueAsBytes(record))
						out.write('\n'.code)
						count++
					}
				}
				counts[name] = count
			}

			override fun file(
				path: String,
				content: InputStream,
			) {
				BackupFormat.requireSafePath(path)
				entry(BackupFormat.FILES_DIR + path) { content.transferTo(it) }
			}

			override val includesSecrets: Boolean get() = passphrase != null

			override fun secret(
				name: String,
				value: Any,
			) {
				require(DATA_NAME.matches(name)) { "Invalid backup secret name '$name'" }
				require(name !in secrets) { "Backup secret '$name' is written twice" }
				if (passphrase != null) secrets[name] = value
			}
		}

	val readable: BackupReadableWriter =
		object : BackupReadableWriter {
			override fun text(
				path: String,
				content: String,
			) = stream(path) { it.write(content.toByteArray(Charsets.UTF_8)) }

			override fun stream(
				path: String,
				write: (OutputStream) -> Unit,
			) {
				BackupFormat.requireSafePath(path)
				entry(BackupFormat.READABLE_DIR + path, write = write)
			}
		}

	private fun dataEntry(
		name: String,
		extension: String,
		write: (OutputStream) -> Unit,
	) {
		require(DATA_NAME.matches(name)) { "Invalid backup data name '$name'" }
		require(name !in counts) { "Backup data '$name' is written twice" }
		entry("${BackupFormat.DATA_DIR}$name.$extension", write = write)
	}

	/** Writes `README.md` (from [readme], given the manifest without it) and `manifest.json`, then closes the zip. */
	fun finish(
		appVersion: String,
		readme: (BackupManifest) -> String,
	): BackupManifest {
		if (passphrase != null) writeSecrets(passphrase)
		val text = readme(manifest(appVersion))
		entry(BackupFormat.README) { it.write(text.toByteArray(Charsets.UTF_8)) }
		val manifest = manifest(appVersion)
		entry(BackupFormat.MANIFEST, hashed = false) { mapper.writerWithDefaultPrettyPrinter().writeValue(it, manifest) }
		zip.close()
		return manifest
	}

	private fun writeSecrets(passphrase: String) {
		require(BackupSecrets.COUNT_NAME !in counts) { "Backup data '${BackupSecrets.COUNT_NAME}' clashes with the secrets' count" }
		val envelope = BackupSecrets.encrypt(mapper.writeValueAsBytes(secrets), passphrase)
		entry(BackupSecrets.PATH) { mapper.writerWithDefaultPrettyPrinter().writeValue(it, envelope) }
		counts[BackupSecrets.COUNT_NAME] = secrets.size.toLong()
		secrets.clear()
	}

	private fun manifest(appVersion: String) =
		BackupManifest(
			formatVersion = BackupFormat.VERSION,
			appVersion = appVersion,
			createdAt = createdAt,
			counts = counts.toMap(),
			files = entries.sortedBy { it.path },
		)

	private fun entry(
		path: String,
		hashed: Boolean = true,
		write: (OutputStream) -> Unit,
	) {
		require(paths.add(path)) { "Duplicate backup entry '$path'" }
		zip.putNextEntry(ZipEntry(path).apply { lastModifiedTime = FileTime.from(createdAt) })
		val out = HashingOutputStream(zip)
		write(out)
		zip.closeEntry()
		if (hashed) entries += BackupFileEntry(path, out.sha256(), out.size)
	}

	override fun close() = zip.close()

	companion object {
		val DATA_NAME = Regex("[a-z0-9]+(_[a-z0-9]+)*")
	}
}

/** Hashes and counts what passes through; never closes [target] (Jackson closes the streams it writes to). */
internal class HashingOutputStream(
	private val target: OutputStream,
) : OutputStream() {
	private val digest = MessageDigest.getInstance("SHA-256")
	var size = 0L
		private set

	override fun write(b: Int) {
		target.write(b)
		digest.update(b.toByte())
		size++
	}

	override fun write(
		b: ByteArray,
		off: Int,
		len: Int,
	) {
		target.write(b, off, len)
		digest.update(b, off, len)
		size += len
	}

	override fun flush() = target.flush()

	override fun close() = flush()

	fun sha256(): String = HexFormat.of().formatHex(digest.digest())
}
