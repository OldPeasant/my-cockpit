package ch.sonensei.cockpit.files.backup

import ch.sonensei.cockpit.backup.BackupFixture
import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.files.domain.Folder
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ch.sonensei.cockpit.files.infra.BlobStore
import ch.sonensei.cockpit.files.infra.FileRepository
import ch.sonensei.cockpit.files.infra.FolderRepository
import java.io.InputStream
import java.time.Clock

/**
 * A drive with nested folders, the special Attachments folders, name clashes (same name, other case, a folder and a
 * file of the same name, a trashed folder next to an active one of the same name), empty and unicode files, items in
 * the Trash and a 60 MB file (streamed, never held in memory).
 */
class FilesBackupFixture(
	private val folders: FolderRepository,
	private val files: FileRepository,
	private val blobs: BlobStore,
	private val clock: Clock,
) : BackupFixture {
	override val contributor = FilesBackupContributor.ID

	override fun seed() {
		val documents = folder("Documents")
		val tax = folder("tax", documents)
		val archive = folder("archive", tax)
		val deep = folder("2025", archive)
		folder("Empty folder", documents)
		val attachments = folder("Attachments") { special = SpecialFolder.ATTACHMENTS }
		folder("Notes", attachments) { special = SpecialFolder.NOTES }
		// Trashed before it is saved: the unique name index only allows it next to "tax" once it is in the Trash.
		val trashedTax = folder("Tax", documents) { softDelete(clock) }

		file("2025.pdf", tax, "application/pdf", "%PDF-1.7 tax 2025")
		file("2025.pdf", tax, "application/pdf", "%PDF-1.7 tax 2025, corrected")
		file("2025.PDF", tax, "application/pdf", "%PDF-1.7 tax 2025, scanned")
		file("2025", archive, "text/plain", "a file named like its sibling folder")
		file("tax", documents, "text/plain", "a file named like its sibling folder")
		file("Grüezi «Welt» 👋.md", deep, "text/markdown", "# Grüezi\n")
		file("empty.txt", null, "text/plain", "")
		file("old.txt", trashedTax, "text/plain", "in a trashed folder")
		file("2025.pdf", tax, "application/pdf", "%PDF-1.7 deleted draft") { softDelete(clock) }
		val large = blobs.write(Pattern(LARGE_SIZE))
		files.save(FileItem("video.mp4", documents.id, "video/mp4", large.size, large.sha256, large.key))
		files.flush()
	}

	private fun folder(
		name: String,
		parent: Folder? = null,
		configure: Folder.() -> Unit = {},
	): Folder = folders.saveAndFlush(Folder(name, parent?.id).apply(configure))

	private fun file(
		name: String,
		folder: Folder?,
		mime: String,
		content: String,
		configure: FileItem.() -> Unit = {},
	): FileItem {
		val blob = blobs.write(content.byteInputStream())
		return files.save(FileItem(name, folder?.id, mime, blob.size, blob.sha256, blob.key).apply(configure))
	}

	/** [size] bytes of a repeating, non-constant pattern, generated on the fly. */
	private class Pattern(
		private val size: Long,
	) : InputStream() {
		private var position = 0L

		override fun read(): Int = if (position < size) ((position++ * 31) % 251).toInt() else -1

		override fun read(
			buffer: ByteArray,
			offset: Int,
			length: Int,
		): Int {
			if (position >= size) return -1
			val count = minOf(length.toLong(), size - position).toInt()
			for (i in 0 until count) buffer[offset + i] = ((position++ * 31) % 251).toByte()
			return count
		}
	}

	companion object {
		const val LARGE_SIZE = 60L * 1024 * 1024
	}
}
