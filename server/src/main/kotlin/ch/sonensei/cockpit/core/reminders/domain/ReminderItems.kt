package ch.sonensei.cockpit.core.reminders.domain

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * Display data of the reminders' items, isolated from the caller's transaction: each resolver call runs in its own
 * read-only transaction (`REQUIRES_NEW`). The module resolvers are `@Transactional` proxies; if one throws while
 * joining the caller's transaction, it marks that transaction rollback-only, so a single broken item would roll back a
 * whole firing batch or turn a read into a 500. Isolated, a failing resolver only degrades its own item.
 */
@Component
class ReminderItems(
	private val resolvers: ItemResolverRegistry,
	transactionManager: PlatformTransactionManager,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	private val isolated =
		TransactionTemplate(transactionManager).apply {
			propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
			isReadOnly = true
		}

	/** The item's display data; `null` when it does not exist, has no resolver or its resolver fails. */
	fun resolve(ref: ItemRef): ResolvedItem? {
		val resolver = resolvers.find(ref.type) ?: return null
		return try {
			isolated.execute { resolver.resolve(ref.id) }
		} catch (e: RuntimeException) {
			// One broken module must not stop the reminders of every other one.
			log.warn("ItemResolver for {} failed for {}", ref.type, ref.id, e)
			null
		}
	}

	/** Whether the item's resolver says it does not exist; `false` without a resolver or when the resolver fails. */
	fun isGone(ref: ItemRef): Boolean {
		val resolver = resolvers.find(ref.type) ?: return false
		return runCatching { isolated.execute { !resolver.exists(ref.id) } }.getOrNull() ?: false
	}
}
