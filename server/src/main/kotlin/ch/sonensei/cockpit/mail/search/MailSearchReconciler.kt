package ch.sonensei.cockpit.mail.search

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * Runs [MailSearchReconciler] unless `cockpit.mail.search.reconcile.enabled=false` (the test configuration turns it
 * off, so tests reconcile explicitly).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["cockpit.mail.search.reconcile.enabled"], havingValue = "true", matchIfMissing = true)
class MailSearchReconcileConfiguration {
	@Bean
	fun mailSearchReconciler(index: MailSearchIndex) = MailSearchReconciler(index)
}

/**
 * Every `cockpit.mail.search.reconcile.interval` (default 1 h, first run 1 min after the start) repairs the mail
 * documents of the search index ([MailSearchIndex.reconcile]): indexes mail cached before mail search existed and
 * drops documents of messages removed without the hooks (e.g. with their account).
 */
class MailSearchReconciler(
	private val index: MailSearchIndex,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(
		fixedDelayString = "\${cockpit.mail.search.reconcile.interval:PT1H}",
		initialDelayString = "\${cockpit.mail.search.reconcile.initial-delay:PT1M}",
	)
	fun run() {
		try {
			var removed = 0
			var added = 0
			var batches = 0
			do {
				val (batchRemoved, batchAdded) = index.reconcile(BATCH_SIZE)
				removed += batchRemoved
				added += batchAdded
				batches++
			} while (batchAdded == BATCH_SIZE && batches < MAX_BATCHES_PER_RUN)
			if (removed + added > 0) log.info("Mail search index reconciled: {} documents removed, {} added", removed, added)
		} catch (e: RuntimeException) {
			// The database or a bug; retried on the next run.
			log.warn("Mail search reconcile failed: {}", e.javaClass.name)
		}
	}

	private companion object {
		const val BATCH_SIZE = 500

		/** Bounds one run; a larger backlog continues on the next run. */
		const val MAX_BATCHES_PER_RUN = 200
	}
}
