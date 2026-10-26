package ch.sonensei.cockpit.files.fulltext

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize

/** Limits of the text extraction for full-text search (`cockpit.files.fulltext.*`). */
@ConfigurationProperties("cockpit.files.fulltext")
class FileTextProperties(
	/** Larger files are not read at all; only their name is searchable. */
	@DefaultValue("20MB") val maxFileSize: DataSize,
	/** At most this many characters of text are kept per file (the rest is cut off). */
	@DefaultValue("100000") val maxChars: Int,
	/** Only the first pages of a longer PDF are read. */
	@DefaultValue("500") val maxPages: Int,
	/** Files looked at per batch of the background job. */
	@DefaultValue("20") val batchSize: Int,
) {
	init {
		require(maxFileSize.toBytes() >= 1) { "cockpit.files.fulltext.max-file-size must be at least 1 byte" }
		require(maxChars >= 1) { "cockpit.files.fulltext.max-chars must be at least 1" }
		require(maxPages >= 1) { "cockpit.files.fulltext.max-pages must be at least 1" }
		require(batchSize >= 1) { "cockpit.files.fulltext.batch-size must be at least 1" }
	}
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileTextProperties::class)
class FileTextConfiguration
