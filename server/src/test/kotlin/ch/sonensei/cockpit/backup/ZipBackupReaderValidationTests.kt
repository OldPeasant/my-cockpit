package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupFileEntry
import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupManifest
import ch.sonensei.cockpit.backup.domain.InvalidBackupException
import ch.sonensei.cockpit.backup.domain.json
import ch.sonensei.cockpit.backup.domain.jsonLines
import ch.sonensei.cockpit.backup.infra.BackupInspection
import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

/**
 * Validation of backups by [ZipBackupReader.open] (SPEC.md §10.2: manifest with the sha256 of every file, format
 * version check). Each test builds a zip by hand, so it can contain what [ch.sonensei.cockpit.backup.infra.ZipBackupWriter]
 * never writes.
 */
class ZipBackupReaderValidationTests {
	@TempDir
	lateinit var dir: Path

	/** Like the application's mapper: Kotlin module, then canonical. */
	private val mapper = BackupFormat.canonical(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())

	private val createdAt = Instant.parse("2026-10-20T15:30:12Z")

	private val readme = "# My Cockpit backup\n".toByteArray()
	private val tasks = "{\"title\":\"Call plumber\"}\n{\"title\":\"Buy milk\"}\n".toByteArray()
	private val blob = "Fictitious invoice 2025".toByteArray()

	private val validFiles =
		linkedMapOf(
			"README.md" to readme,
			"data/tasks.jsonl" to tasks,
			"files/Documents/invoice.txt" to blob,
		)

	private fun entry(
		path: String,
		bytes: ByteArray,
	) = BackupFileEntry(path, sha256(bytes), bytes.size.toLong())

	private fun manifest(
		files: List<BackupFileEntry> = validFiles.map { (path, bytes) -> entry(path, bytes) },
		formatVersion: Int = BackupFormat.VERSION,
	) = BackupManifest(formatVersion, "1.0.0", createdAt, mapOf("tasks" to 2L), files)

	/** A zip with [entries] in order (a `null` value is a directory entry) and, unless [manifest] is null, its manifest. */
	private fun zip(
		entries: Map<String, ByteArray?> = validFiles,
		manifest: ByteArray? = mapper.writeValueAsBytes(manifest()),
	): Path {
		val path = dir.resolve("backup-${System.nanoTime()}.zip")
		ZipOutputStream(path.outputStream()).use { out ->
			entries.forEach { (name, bytes) ->
				out.putNextEntry(ZipEntry(name))
				bytes?.let(out::write)
				out.closeEntry()
			}
			manifest?.let {
				out.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
				out.write(it)
				out.closeEntry()
			}
		}
		return path
	}

	private fun problems(zip: Path) = shouldThrow<InvalidBackupException> { ZipBackupReader.open(zip, mapper) }.problems

	@Test
	fun `a hand-built valid backup is accepted`() {
		ZipBackupReader.open(zip(), mapper).use { reader ->
			reader.manifest.counts shouldBe mapOf("tasks" to 2L)
			reader.files() shouldContainExactly listOf("Documents/invoice.txt")
		}
	}

	@Test
	fun `a file changed without changing its size fails the sha256 check`() {
		val tampered = blob.copyOf().also { it[0] = 'f'.code.toByte() }

		problems(zip(validFiles + ("files/Documents/invoice.txt" to tampered))) shouldContainExactly
			listOf("files/Documents/invoice.txt does not match its sha256 in the manifest")
	}

	@Test
	fun `a wrong size in the manifest fails even when the sha256 matches`() {
		val files = manifest().files.map { if (it.path == "data/tasks.jsonl") it.copy(size = it.size + 1) else it }

		problems(zip(manifest = mapper.writeValueAsBytes(manifest(files)))) shouldContainExactly
			listOf("data/tasks.jsonl does not match its sha256 in the manifest")
	}

	@Test
	fun `a wrong sha256 in the manifest fails`() {
		val files = manifest().files.map { if (it.path == "README.md") it.copy(sha256 = sha256("other".toByteArray())) else it }

		problems(zip(manifest = mapper.writeValueAsBytes(manifest(files)))) shouldContainExactly
			listOf("README.md does not match its sha256 in the manifest")
	}

	@Test
	fun `sha256 values in upper case are accepted`() {
		val files = manifest().files.map { it.copy(sha256 = it.sha256.uppercase()) }

		ZipBackupReader.open(zip(manifest = mapper.writeValueAsBytes(manifest(files))), mapper).close()
	}

	@Test
	fun `a manifest that lists a file twice is rejected`() {
		val files = manifest().files.let { it + it.first { entry -> entry.path == "data/tasks.jsonl" } }

		problems(zip(manifest = mapper.writeValueAsBytes(manifest(files)))) shouldContain "manifest.json lists a file twice"
	}

	@Test
	fun `listed paths outside README, data, files and readable or with dot segments are rejected`() {
		val unsafe =
			linkedMapOf(
				"../outside.txt" to blob,
				"files/../../etc/passwd" to blob,
				"other/notes.txt" to blob,
				"files/a\\b.txt" to blob,
			)
		val files = manifest().files + unsafe.map { (path, bytes) -> entry(path, bytes) }

		val problems = problems(zip(validFiles + unsafe, mapper.writeValueAsBytes(manifest(files))))

		unsafe.keys.forEach { path -> problems shouldContain "$path is not a valid path" }
		problems shouldHaveSize unsafe.size
	}

	@Test
	fun `a manifest that lists itself is rejected`() {
		val files = manifest().files + BackupFileEntry(BackupFormat.MANIFEST, sha256(ByteArray(0)), 0)

		problems(zip(manifest = mapper.writeValueAsBytes(manifest(files)))) shouldContain "manifest.json is not a valid path"
	}

	@Test
	fun `an unlisted entry with an unsafe name is reported, not extracted`() {
		problems(zip(validFiles + ("../../evil.sh" to "rm -rf".toByteArray()))) shouldContainExactly
			listOf("../../evil.sh is not in the manifest")
	}

	@Test
	fun `a listed directory entry counts as missing, other directory entries are ignored`() {
		ZipBackupReader.open(zip(linkedMapOf<String, ByteArray?>("files/" to null, "files/Documents/" to null) + validFiles), mapper).close()

		val files = manifest().files + BackupFileEntry("files/Empty", sha256(ByteArray(0)), 0)
		problems(zip(validFiles + ("files/Empty/" to null), mapper.writeValueAsBytes(manifest(files)))) shouldContainExactly
			listOf("files/Empty is missing")
	}

	@Test
	fun `every problem is listed, sorted by path`() {
		val files = manifest().files.filterNot { it.path == "README.md" } + entry("readable/tasks.md", "- Call plumber".toByteArray())

		problems(
			zip(validFiles + ("data/tasks.jsonl" to "{}\n".toByteArray()), mapper.writeValueAsBytes(manifest(files))),
		) shouldContainExactly
			listOf(
				"README.md is not in the manifest",
				"data/tasks.jsonl does not match its sha256 in the manifest",
				"readable/tasks.md is missing",
			)
	}

	@Test
	fun `format versions other than 1 are rejected`() {
		listOf(0, 2, -1).forEach { version ->
			problems(zip(manifest = mapper.writeValueAsBytes(manifest(formatVersion = version)))) shouldContainExactly
				listOf("Unsupported format version $version (supported: 1)")
		}
	}

	@Test
	fun `a manifest with missing or wrongly typed fields is not valid`() {
		listOf(
			"{\"formatVersion\":1}",
			"[]",
			"{\"formatVersion\":1,\"appVersion\":\"1.0.0\",\"createdAt\":\"yesterday\",\"counts\":{},\"files\":[]}",
			"{\"formatVersion\":\"one\",\"appVersion\":\"1.0.0\",\"createdAt\":\"2026-10-20T15:30:12Z\",\"counts\":{},\"files\":[]}",
		).forEach { manifest ->
			problems(zip(manifest = manifest.toByteArray())) shouldContainExactly listOf("manifest.json is not valid")
		}
	}

	@Test
	fun `a manifest that is JSON null is not valid`() {
		problems(zip(manifest = "null".toByteArray())) shouldContainExactly listOf("manifest.json is not valid")
	}

	@Test
	fun `an empty file and a truncated zip are not zip files`() {
		val empty = dir.resolve("empty.zip").also { Files.write(it, ByteArray(0)) }
		problems(empty) shouldContainExactly listOf("Not a zip file")

		val valid = Files.readAllBytes(zip())
		val truncated = dir.resolve("truncated.zip").also { Files.write(it, valid.copyOf(valid.size - 30)) }
		problems(truncated) shouldContainExactly listOf("Not a zip file")
	}

	/**
	 * A zip with a second, unlisted `data/tasks.jsonl` whose content the manifest does not cover. Other zip tools (and
	 * a streaming reader) may see the other copy, so the backup is not the one the manifest describes.
	 */
	private fun duplicateTasks(decoyFirst: Boolean): Path {
		// ZipOutputStream refuses duplicate names: write two names of equal length, then rename one in the raw bytes.
		val decoy = linkedMapOf("data/tasks.jsonX" to "{\"title\":\"Transfer savings\"}\n".toByteArray())
		val bytes = Files.readAllBytes(zip(if (decoyFirst) decoy + validFiles else validFiles + decoy))
		val from = "data/tasks.jsonX".toByteArray()
		val to = "data/tasks.jsonl".toByteArray()
		var index = indexOf(bytes, from, 0)
		while (index >= 0) {
			to.copyInto(bytes, index)
			index = indexOf(bytes, from, index + 1)
		}
		return dir.resolve("duplicate-$decoyFirst.zip").also { Files.write(it, bytes) }
	}

	@Test
	fun `a zip with two entries of the same name is rejected when the unlisted copy comes last`() {
		shouldThrow<InvalidBackupException> { ZipBackupReader.open(duplicateTasks(decoyFirst = false), mapper) }
	}

	@Test
	fun `a zip with two entries of the same name is rejected when the unlisted copy comes first`() {
		shouldThrow<InvalidBackupException> { ZipBackupReader.open(duplicateTasks(decoyFirst = true), mapper) }
	}

	@Test
	fun `a data file whose compressed bytes are corrupt is rejected`() {
		val big =
			(1..4000)
				.joinToString("\n", postfix = "\n") { "{\"n\":$it,\"title\":\"Fictitious task $it ${it * 7919 % 1000}\"}" }
				.toByteArray()
		val entries = linkedMapOf("data/big.jsonl" to big) + validFiles
		val files = manifest().files + entry("data/big.jsonl", big)
		val bytes = Files.readAllBytes(zip(entries, mapper.writeValueAsBytes(manifest(files))))
		// The first entry starts at offset 0: a 30-byte local header, the name, the extra field, then the deflated data.
		val nameLength = (bytes[26].toInt() and 0xff) or ((bytes[27].toInt() and 0xff) shl 8)
		val extraLength = (bytes[28].toInt() and 0xff) or ((bytes[29].toInt() and 0xff) shl 8)
		val data = 30 + nameLength + extraLength
		for (i in data until data + 64) bytes[i] = 0xff.toByte()
		val corrupt = dir.resolve("corrupt.zip").also { Files.write(it, bytes) }

		problems(corrupt) shouldContainExactly listOf("data/big.jsonl cannot be read")
	}

	@Test
	fun `a manifest whose compressed bytes are corrupt is not valid, for open and inspect`() {
		val path = dir.resolve("manifest-first.zip")
		ZipOutputStream(path.outputStream()).use { out ->
			out.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
			out.write(mapper.writeValueAsBytes(manifest()))
			out.closeEntry()
			validFiles.forEach { (name, bytes) ->
				out.putNextEntry(ZipEntry(name))
				out.write(bytes)
				out.closeEntry()
			}
		}
		val bytes = Files.readAllBytes(path)
		// The manifest is the first entry: a 30-byte local header, the name, the extra field, then the deflated data.
		val nameLength = (bytes[26].toInt() and 0xff) or ((bytes[27].toInt() and 0xff) shl 8)
		val extraLength = (bytes[28].toInt() and 0xff) or ((bytes[29].toInt() and 0xff) shl 8)
		val data = 30 + nameLength + extraLength
		for (i in data until data + 8) bytes[i] = 0xff.toByte()
		val corrupt = dir.resolve("corrupt-manifest.zip").also { Files.write(it, bytes) }

		problems(corrupt) shouldContainExactly listOf("manifest.json is not valid")
		ZipBackupReader.inspect(corrupt, mapper) shouldBe BackupInspection(null, listOf("manifest.json is not valid"))
	}

	/** The valid manifest as a JSON object, to break one field of it. */
	private fun manifestTree() = mapper.valueToTree<ObjectNode>(manifest())

	@Test
	fun `a manifest without a numeric format version is not valid`() {
		listOf<(ObjectNode) -> Unit>(
			{ it.remove("formatVersion") },
			{ it.putNull("formatVersion") },
			{ it.put("formatVersion", "1") },
			{ it.putArray("formatVersion").add(1) },
		).forEach { change ->
			val manifest = manifestTree().also(change)
			problems(zip(manifest = mapper.writeValueAsBytes(manifest))) shouldContainExactly listOf("manifest.json is not valid")
		}
	}

	@Test
	fun `a fractional or out-of-range format version is unsupported, not invalid`() {
		problems(zip(manifest = mapper.writeValueAsBytes(manifestTree().put("formatVersion", 1.5)))).single() shouldStartWith
			"Unsupported format version 1.5"
		problems(zip(manifest = mapper.writeValueAsBytes(manifestTree().put("formatVersion", 4_294_967_297L)))) shouldContainExactly
			listOf("Unsupported format version 4294967297 (supported: 1)")
	}

	@Test
	fun `a manifest of another format version is reported as unsupported even without the fields of version 1`() {
		problems(zip(manifest = "{\"formatVersion\":2,\"somethingNew\":true}".toByteArray())) shouldContainExactly
			listOf("Unsupported format version 2 (supported: 1)")
	}

	@Test
	fun `null entries in the manifest's files or counts are not valid`() {
		listOf<(ObjectNode) -> Unit>(
			{ (it["files"] as ArrayNode).addNull() },
			{ (it["files"] as ArrayNode).add("README.md") },
			{ (it["counts"] as ObjectNode).putNull("tasks") },
			{ (it["counts"] as ObjectNode).put("tasks", "two") },
			{ it.putNull("files") },
		).forEachIndexed { index, change ->
			val manifest = manifestTree().also(change)
			withClue("change $index") {
				problems(zip(manifest = mapper.writeValueAsBytes(manifest))) shouldContainExactly listOf("manifest.json is not valid")
			}
		}
	}

	@Test
	fun `a manifest that is not JSON is not valid`() {
		problems(zip(manifest = "{\"formatVersion\":1,".toByteArray())) shouldContainExactly listOf("manifest.json is not valid")
	}

	@Test
	fun `a zip without manifest is reported`() {
		problems(zip(manifest = null)) shouldContainExactly listOf("manifest.json is missing")
	}

	@Test
	fun `inspect returns the manifest and no problems for a valid backup`() {
		val inspection = ZipBackupReader.inspect(zip(), mapper)

		inspection.problems.shouldBeEmpty()
		inspection.manifest shouldBe manifest()
	}

	@Test
	fun `inspect returns the manifest together with the problems when files don't match it`() {
		val inspection = ZipBackupReader.inspect(zip(validFiles + ("data/tasks.jsonl" to "{}\n".toByteArray())), mapper)

		inspection.manifest shouldBe manifest()
		inspection.problems shouldContainExactly listOf("data/tasks.jsonl does not match its sha256 in the manifest")
	}

	@Test
	fun `inspect returns no manifest for a file that is no zip, or whose manifest is missing, invalid or of another version`() {
		val notZip = dir.resolve("not.zip").also { Files.write(it, "PK not really".toByteArray()) }
		ZipBackupReader.inspect(notZip, mapper) shouldBe BackupInspection(null, listOf("Not a zip file"))
		ZipBackupReader.inspect(zip(manifest = null), mapper) shouldBe BackupInspection(null, listOf("manifest.json is missing"))
		ZipBackupReader.inspect(zip(manifest = "[]".toByteArray()), mapper) shouldBe
			BackupInspection(null, listOf("manifest.json is not valid"))
		ZipBackupReader.inspect(zip(manifest = mapper.writeValueAsBytes(manifest(formatVersion = 2))), mapper) shouldBe
			BackupInspection(null, listOf("Unsupported format version 2 (supported: 1)"))
	}

	@Test
	fun `inspect closes the zip, so the file can be deleted afterwards`() {
		val path = zip()
		ZipBackupReader.inspect(path, mapper)

		Files.delete(path)
		Files.exists(path) shouldBe false
	}

	/** A valid backup with these data files besides [validFiles], all listed in its manifest. */
	private fun withData(data: Map<String, ByteArray>): Path {
		val entries = validFiles + data
		return zip(entries, mapper.writeValueAsBytes(manifest(entries.map { (path, bytes) -> entry(path, bytes) })))
	}

	private data class Title(
		val title: String,
	)

	@Test
	fun `data is read through json and jsonLines, blank lines skipped`() {
		val zip = withData(mapOf("data/profile.json" to "{\"title\":\"Jane Fictitious\"}".toByteArray()))

		ZipBackupReader.open(zip, mapper).use { reader ->
			reader.formatVersion shouldBe BackupFormat.VERSION
			reader.has("profile") shouldBe true
			reader.has("tasks") shouldBe true
			reader.has("absent") shouldBe false
			reader.json<Title>("profile") shouldBe Title("Jane Fictitious")
			reader.json<Title>("absent") shouldBe null
			val titles = mutableListOf<String>()
			reader.jsonLines<Title>("tasks") { titles += it.title }
			reader.jsonLines<Title>("absent") { titles += "never" }
			titles shouldContainExactly listOf("Call plumber", "Buy milk")
			reader.file("Documents/invoice.txt")!!.use { String(it.readAllBytes()) } shouldBe "Fictitious invoice 2025"
			reader.file("Documents/missing.txt") shouldBe null
		}
	}

	@Test
	fun `a json data file that does not parse or is null fails with its name only, not its content`() {
		listOf("{\"title\":\"Secret diary entry\"", "null", "[\"Secret diary entry\"]").forEach { content ->
			val zip = withData(mapOf("data/profile.json" to content.toByteArray()))

			ZipBackupReader.open(zip, mapper).use { reader ->
				val e = shouldThrow<InvalidBackupException> { reader.json<Title>("profile") }
				e.problems shouldContainExactly listOf("data/profile.json is not valid")
				e.problems.single() shouldNotContain "Secret"
				(e.message ?: "") shouldNotContain "Secret"
			}
		}
	}

	@Test
	fun `a jsonl record that does not parse or is null fails with its file and line, not its content`() {
		// content, line of the bad record, records consumed before it
		listOf(
			Triple("{\"title\":\"Call plumber\"}\n\n{\"title\":\"Secret diary entry\"\n", 3, 1),
			Triple("{\"title\":\"Call plumber\"}\nnull\n", 2, 1),
			Triple("{\"wrong\":\"Secret diary entry\"}\n", 1, 0),
		).forEach { (content, line, before) ->
			val zip = withData(mapOf("data/diary.jsonl" to content.toByteArray()))

			ZipBackupReader.open(zip, mapper).use { reader ->
				val read = mutableListOf<String>()
				val e = shouldThrow<InvalidBackupException> { reader.jsonLines<Title>("diary") { read += it.title } }
				e.problems shouldContainExactly listOf("data/diary.jsonl line $line is not valid")
				(e.message ?: "") shouldNotContain "Secret"
				read shouldBe List(before) { "Call plumber" }
			}
		}
	}

	private fun indexOf(
		bytes: ByteArray,
		pattern: ByteArray,
		from: Int,
	): Int =
		(from..bytes.size - pattern.size).firstOrNull { start ->
			pattern.indices.all { bytes[start + it] == pattern[it] }
		} ?: -1

	private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
