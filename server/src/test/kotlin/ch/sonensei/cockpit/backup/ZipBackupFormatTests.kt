package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.InvalidBackupException
import ch.sonensei.cockpit.backup.domain.json
import ch.sonensei.cockpit.backup.domain.jsonLines
import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import ch.sonensei.cockpit.backup.infra.ZipBackupWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

class ZipBackupFormatTests {
	@TempDir
	lateinit var dir: Path

	private val mapper = BackupFormat.canonical(JsonMapper.builder().build())
	private val createdAt = Instant.parse("2026-10-20T15:30:12Z")

	data class Row(
		val zeta: String,
		val alpha: Int,
	)

	private fun write(
		name: String = "backup.zip",
		block: (ZipBackupWriter) -> Unit,
	): Path {
		val path = dir.resolve(name)
		path.outputStream().use { out ->
			val writer = ZipBackupWriter(out, mapper, createdAt)
			block(writer)
			writer.finish("1.2.3") { "# Readme\n${it.counts}\n" }
		}
		return path
	}

	private fun sample() =
		write { writer ->
			writer.data.jsonLines("rows", sequenceOf(Row("z", 1), Row("y", 2)))
			writer.data.json("settings", mapOf("b" to 2, "a" to 1))
			writer.data.json("areas", listOf(Row("x", 3)))
			writer.data.file("Documents/tax/2025.pdf", "pdf".byteInputStream())
			writer.readable.text("tasks/inbox.md", "- [ ] Call plumber\n")
		}

	private fun entries(zip: Path): Map<String, ByteArray> =
		ZipFile(zip.toFile()).use { file ->
			file.entries().toList().associate { it.name to file.getInputStream(it).readAllBytes() }
		}

	@Test
	fun `writes data, files, readable, README and a manifest with counts and sha256 of every file`() {
		val entries = entries(sample())

		entries.keys shouldContainExactly
			setOf(
				"data/rows.jsonl",
				"data/settings.json",
				"data/areas.json",
				"files/Documents/tax/2025.pdf",
				"readable/tasks/inbox.md",
				"README.md",
				"manifest.json",
			)
		String(entries.getValue("data/rows.jsonl")) shouldBe "{\"alpha\":1,\"zeta\":\"z\"}\n{\"alpha\":2,\"zeta\":\"y\"}\n"
		val manifest = mapper.readTree(entries.getValue("manifest.json"))
		manifest["formatVersion"].asInt() shouldBe 1
		manifest["appVersion"].asString() shouldBe "1.2.3"
		manifest["createdAt"].asString() shouldBe "2026-10-20T15:30:12Z"
		manifest["counts"].toString() shouldBe "{\"areas\":1,\"rows\":2,\"settings\":1}"
		val files = manifest["files"].associate { it["path"].asString() to it["sha256"].asString() }
		files.keys shouldBe entries.keys - "manifest.json"
		files.forEach { (path, sha) -> sha shouldBe sha256(entries.getValue(path)) }
		String(entries.getValue("README.md")) shouldContain "areas=1"
	}

	@Test
	fun `manifest counts are records for JSON Lines, the size of collections and arrays, else one`() {
		val path =
			write { writer ->
				writer.data.jsonLines("empty_lines", emptySequence())
				writer.data.json("array", arrayOf("a", "b", "c"))
				writer.data.json("empty_list", emptyList<String>())
				writer.data.json("object", mapOf("x" to 1, "y" to 2))
				writer.recordCounts shouldBe mapOf("empty_lines" to 0L, "array" to 3L, "empty_list" to 0L, "object" to 1L)
			}

		val manifest = mapper.readTree(entries(path).getValue("manifest.json"))
		manifest["counts"].toString() shouldBe "{\"array\":3,\"empty_lines\":0,\"empty_list\":0,\"object\":1}"
		String(entries(path).getValue("data/empty_lines.jsonl")) shouldBe ""
	}

	@Test
	fun `nested data is canonical too, and entries carry the backup time`() {
		val path = write { it.data.json("nested", mapOf("z" to mapOf("b" to Row("q", 1), "a" to listOf(3, 1)))) }

		val text = String(entries(path).getValue("data/nested.json"))
		(text.indexOf("\"a\"") < text.indexOf("\"b\"")) shouldBe true
		(text.indexOf("\"alpha\"") < text.indexOf("\"zeta\"")) shouldBe true
		ZipFile(path.toFile()).use { zip ->
			zip.entries().toList().forEach { it.lastModifiedTime.toInstant() shouldBe createdAt }
		}
	}

	@Test
	fun `equal data gives byte-equal data files`() {
		val first = entries(sample())
		val second = entries(write("again.zip") { it.data.json("settings", linkedMapOf("a" to 1, "b" to 2)) })

		second.getValue("data/settings.json") shouldBe first.getValue("data/settings.json")
	}

	@Test
	fun `the reader reads back what the writer wrote`() {
		ZipBackupReader.open(sample(), mapper).use { reader ->
			reader.formatVersion shouldBe 1
			reader.has("rows") shouldBe true
			reader.has("settings") shouldBe true
			reader.has("missing") shouldBe false
			val rows = mutableListOf<Row>()
			reader.jsonLines<Row>("rows") { rows += it }
			rows shouldContainExactly listOf(Row("z", 1), Row("y", 2))
			reader.json<Map<String, Int>>("settings") shouldBe mapOf("a" to 1, "b" to 2)
			reader.json<Map<String, Int>>("missing") shouldBe null
			reader.files() shouldContainExactly listOf("Documents/tax/2025.pdf")
			reader.file("Documents/tax/2025.pdf")!!.use { String(it.readAllBytes()) } shouldBe "pdf"
			reader.file("nope") shouldBe null
		}
	}

	@Test
	fun `the reader streams nothing for data a backup does not have, and skips blank lines`() {
		// A JSON Lines file with blank lines (e.g. edited by hand), listed in the manifest with its real sha256.
		val lines = "\n{\"alpha\":1,\"zeta\":\"z\"}\n   \n\n{\"alpha\":2,\"zeta\":\"y\"}\n\n".toByteArray()
		val path = replaceListed(sample(), "data/rows.jsonl", lines)

		ZipBackupReader.open(path, mapper).use { reader ->
			val rows = mutableListOf<Row>()
			reader.jsonLines<Row>("rows") { rows += it }
			rows shouldContainExactly listOf(Row("z", 1), Row("y", 2))

			var called = false
			reader.jsonLines<Row>("missing") { called = true }
			called shouldBe false
			// `rows` exists only as JSON Lines: json() does not read it as a document.
			reader.json<List<Row>>("rows") shouldBe null
		}
	}

	@Test
	fun `unsafe paths, invalid names and duplicates are rejected`() {
		write { writer ->
			listOf("../evil", "/abs", "a//b", "a/./b", "a\\b", "", "a/..").forEach { path ->
				shouldThrow<IllegalArgumentException> { writer.data.file(path, "x".byteInputStream()) }
				shouldThrow<IllegalArgumentException> { writer.readable.text(path, "x") }
			}
			listOf("Tasks", "tasks.jsonl", "a-b", "", "_a").forEach { name ->
				shouldThrow<IllegalArgumentException> { writer.data.json(name, 1) }
			}
			writer.data.json("tasks", 1)
			shouldThrow<IllegalArgumentException> { writer.data.jsonLines("tasks", emptySequence()) }
			writer.data.file("a.txt", "x".byteInputStream())
			shouldThrow<IllegalArgumentException> { writer.data.file("a.txt", "y".byteInputStream()) }
		}
	}

	@Test
	fun `a file name with the backup time`() {
		BackupFormat.fileName(createdAt) shouldBe "cockpit-backup-2026-10-20T1530Z.zip"
	}

	@Test
	fun `the reader rejects a file that is not a zip`() {
		val path = dir.resolve("not.zip").also { Files.writeString(it, "hello") }
		shouldThrow<InvalidBackupException> { ZipBackupReader.open(path, mapper) }.problems shouldContainExactly listOf("Not a zip file")
	}

	@Test
	fun `the reader rejects a zip without manifest or with another format version`() {
		val noManifest = rewrite(sample()) { name, bytes -> if (name == "manifest.json") null else bytes }
		shouldThrow<InvalidBackupException> { ZipBackupReader.open(noManifest, mapper) }.problems shouldContainExactly
			listOf("manifest.json is missing")

		val v2 =
			rewrite(sample()) { name, bytes ->
				if (name == "manifest.json") String(bytes).replace("\"formatVersion\" : 1", "\"formatVersion\" : 2").toByteArray() else bytes
			}
		shouldThrow<InvalidBackupException> { ZipBackupReader.open(v2, mapper) }.problems.single() shouldContain
			"Unsupported format version 2"

		val garbled = rewrite(sample()) { name, bytes -> if (name == "manifest.json") "{".toByteArray() else bytes }
		shouldThrow<InvalidBackupException> { ZipBackupReader.open(garbled, mapper) }.problems shouldContainExactly
			listOf("manifest.json is not valid")
	}

	@Test
	fun `the reader rejects changed, missing and unlisted files`() {
		val tampered =
			rewrite(sample()) { name, bytes ->
				when (name) {
					"data/rows.jsonl" -> bytes + "{}\n".toByteArray()
					"readable/tasks/inbox.md" -> null
					else -> bytes
				}
			}
		addEntry(tampered, "data/extra.json", "{}")

		val problems = shouldThrow<InvalidBackupException> { ZipBackupReader.open(tampered, mapper) }.problems
		problems shouldContain "data/rows.jsonl does not match its sha256 in the manifest"
		problems shouldContain "readable/tasks/inbox.md is missing"
		problems shouldContain "data/extra.json is not in the manifest"
	}

	/** Copies [source] to a new zip, mapping each entry's bytes (null drops it). */
	private fun rewrite(
		source: Path,
		map: (String, ByteArray) -> ByteArray?,
	): Path {
		val target = dir.resolve("rewritten-${System.nanoTime()}.zip")
		ZipOutputStream(target.outputStream()).use { out ->
			entries(source).forEach { (name, bytes) ->
				map(name, bytes)?.let {
					out.putNextEntry(ZipEntry(name))
					out.write(it)
					out.closeEntry()
				}
			}
		}
		return target
	}

	/** Copies [source] with new [bytes] for the listed entry [name], and updates its sha256 and size in the manifest. */
	private fun replaceListed(
		source: Path,
		name: String,
		bytes: ByteArray,
	): Path =
		rewrite(source) { entry, old ->
			when (entry) {
				name -> {
					bytes
				}

				"manifest.json" -> {
					val manifest = mapper.readTree(old)
					manifest["files"]
						.filter { it["path"].asString() == name }
						.forEach { file ->
							(file as ObjectNode).put("sha256", sha256(bytes)).put("size", bytes.size.toLong())
						}
					mapper.writeValueAsBytes(manifest)
				}

				else -> {
					old
				}
			}
		}

	private fun addEntry(
		zip: Path,
		name: String,
		content: String,
	) {
		val all = entries(zip) + (name to content.toByteArray())
		ZipOutputStream(zip.outputStream()).use { out ->
			all.forEach { (entry, bytes) ->
				out.putNextEntry(ZipEntry(entry))
				out.write(bytes)
				out.closeEntry()
			}
		}
	}

	private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
