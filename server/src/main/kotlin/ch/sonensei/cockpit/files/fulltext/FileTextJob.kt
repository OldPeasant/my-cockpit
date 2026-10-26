package ch.sonensei.cockpit.files.fulltext

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * Runs [FileTextJob] unless `cockpit.files.fulltext.scheduler.enabled=false` (the test configuration turns it off, so
 * tests call [FileTextIndexer.indexPending] themselves).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["cockpit.files.fulltext.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class FileTextJobConfiguration {
	@Bean
	fun fileTextJob(indexer: FileTextIndexer) = FileTextJob(indexer)
}

/**
 * Every `cockpit.files.fulltext.interval` (default 30 s, first run 1 min after the start) extracts the text of new
 * files in batches ([FileTextIndexer.indexPending]), until none is left or the run's batch limit is reached. Files
 * stored before full-text search existed are caught up the same way.
 */
class FileTextJob(
	private val indexer: FileTextIndexer,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(
		fixedDelayString = "\${cockpit.files.fulltext.interval:PT30S}",
		initialDelayString = "\${cockpit.files.fulltext.initial-delay:PT1M}",
	)
	fun run() {
		try {
			for (batch in 1..MAX_BATCHES_PER_RUN) {
				if (indexer.indexPending() == 0) break
			}
		} catch (e: RuntimeException) {
			// The database or a bug; retried on the next run.
			log.warn("File text extraction failed: {}", e.javaClass.name)
		}
	}

	private companion object {
		/** Bounds one run; a larger backlog continues on the next run. */
		const val MAX_BATCHES_PER_RUN = 50
	}
}
