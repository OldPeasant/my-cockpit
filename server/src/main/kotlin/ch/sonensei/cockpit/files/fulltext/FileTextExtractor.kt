package ch.sonensei.cockpit.files.fulltext

import ch.sonensei.cockpit.files.domain.PreviewService
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the searchable text of a file: PDFs with PDFBox, text files ([PreviewService.isText]) as UTF-8. At most
 * [FileTextProperties.maxChars] characters and [FileTextProperties.maxPages] pages are read; files above
 * [FileTextProperties.maxFileSize] are not opened. PDFBox buffers in temporary files, not in the heap.
 */
@Component
class FileTextExtractor(
	private val properties: FileTextProperties,
) {
	/** Whether files of [mime] (any case, with parameters) have text this extractor reads. */
	fun supports(mime: String): Boolean = PreviewService.baseMime(mime).let { it == PDF || PreviewService.isText(it) }

	/**
	 * The text of the file at [path], `null` when [mime] is not supported or [size] is above the limit.
	 *
	 * @throws IOException when the file cannot be read or is no valid (or an encrypted) PDF
	 */
	fun extract(
		path: Path,
		mime: String,
		size: Long,
	): String? {
		if (!supports(mime) || size > properties.maxFileSize.toBytes()) return null
		return if (PreviewService.baseMime(mime) == PDF) pdf(path) else text(path)
	}

	private fun pdf(path: Path): String {
		val out = BoundedWriter(properties.maxChars)
		Loader.loadPDF(path.toFile(), "", null, null, IOUtils.createTempFileOnlyStreamCache()).use { document ->
			val stripper = PDFTextStripper().apply { endPage = properties.maxPages }
			try {
				stripper.writeText(document, out)
			} catch (_: BoundedWriter.Full) {
				// Enough text; the rest of the document is not read.
			}
		}
		return out.toString()
	}

	private fun text(path: Path): String {
		// A UTF-8 character has at most 4 bytes, so this many bytes hold at least maxChars characters.
		val limit = minOf(properties.maxChars.toLong() * 4, Int.MAX_VALUE.toLong() - 1).toInt()
		val bytes = Files.newInputStream(path).use { it.readNBytes(limit + 1) }
		val length = if (bytes.size > limit) PreviewService.utf8Boundary(bytes, limit) else bytes.size
		return PreviewService.decodeUtf8(bytes, length).take(properties.maxChars)
	}

	/** Collects up to [max] characters, then throws [Full] so the producer stops. */
	private class BoundedWriter(
		private val max: Int,
	) : Writer() {
		private val text = StringBuilder()

		class Full : IOException("text limit reached")

		override fun write(
			cbuf: CharArray,
			off: Int,
			len: Int,
		) {
			val room = max - text.length
			text.appendRange(cbuf, off, off + minOf(len, room))
			if (len >= room) throw Full()
		}

		override fun flush() = Unit

		override fun close() = Unit

		override fun toString() = text.toString()
	}

	private companion object {
		const val PDF = "application/pdf"
	}
}
