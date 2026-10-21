package ch.sonensei.cockpit.backup.domain

import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import ch.sonensei.cockpit.backup.infra.ZipBackupWriter
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.io.OutputStream
import java.nio.file.Path
import java.time.Clock
import java.time.temporal.ChronoUnit

/** Where an export is: [done] of [total] steps finished, now at [step] (e.g. `gtd: data`). */
data class BackupProgress(
	val step: String,
	val done: Int,
	val total: Int,
)

/**
 * Writes and reads backups in format version 1 (SPEC.md §10.2) by calling every [BackupContributor] in dependency
 * order. The API (async jobs, download) is in [BackupJobService]; restore with validation report builds on [import].
 */
@Service
class BackupService(
	private val contributors: BackupContributorRegistry,
	jsonMapper: JsonMapper,
	private val clock: Clock,
	transactions: PlatformTransactionManager,
) {
	/** Sorted properties and map keys: equal data gives byte-equal canonical JSON. */
	val canonicalJson: JsonMapper = BackupFormat.canonical(jsonMapper)

	val appVersion: String = BackupService::class.java.`package`.implementationVersion ?: "dev"

	/** One consistent snapshot of all modules: every contributor reads in the same repeatable-read transaction. */
	private val snapshot =
		TransactionTemplate(transactions).apply {
			isReadOnly = true
			isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
		}

	private val importTransaction = TransactionTemplate(transactions)

	/** Number of progress steps of an export: data and mirrors of each contributor, then README and manifest. */
	val exportSteps: Int get() = contributors.ordered.size * 2 + 1

	/**
	 * Writes a complete backup zip to [target] (which the caller closes) and returns its manifest. With a [passphrase],
	 * the secrets go encrypted to `data/secrets.enc.json`; without, they are left out. [progress] is called before each
	 * step and once at the end with `done == total`.
	 */
	fun export(
		target: OutputStream,
		passphrase: String? = null,
		progress: (BackupProgress) -> Unit = {},
	): BackupManifest {
		val createdAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
		val total = exportSteps
		var done = 0
		val step = { name: String -> progress(BackupProgress(name, done++, total)) }
		val writer = ZipBackupWriter(NonClosingOutputStream(target), canonicalJson, createdAt, passphrase)
		return writer.use {
			snapshot.executeWithoutResult { _ ->
				contributors.ordered.forEach { contributor ->
					step("${contributor.id}: data")
					contributor.export(writer.data)
				}
				contributors.ordered.forEach { contributor ->
					step("${contributor.id}: readable")
					contributor.readable(writer.readable)
				}
			}
			step("manifest")
			writer.finish(appVersion, ::readme).also { progress(BackupProgress("done", total, total)) }
		}
	}

	/**
	 * Imports the backup at [source] into an empty install, in one transaction; the caller wipes existing data first.
	 * The secrets are imported only with the right [passphrase].
	 *
	 * @throws InvalidBackupException when the zip, its manifest or its format version is invalid
	 * @throws WrongPassphraseException when [passphrase] does not decrypt the backup's secrets
	 */
	fun import(
		source: Path,
		passphrase: String? = null,
	): BackupManifest =
		ZipBackupReader.open(source, canonicalJson, passphrase).use { reader ->
			importTransaction.executeWithoutResult { _ ->
				contributors.ordered.forEach { it.import(reader) }
			}
			reader.manifest
		}

	/**
	 * Every contributor's [BackupContributor.restoreWarnings] for the backup at [source], in dependency order.
	 *
	 * @throws InvalidBackupException when the zip, its manifest, its format version or the data read is invalid
	 */
	fun restoreWarnings(source: Path): List<String> =
		ZipBackupReader.open(source, canonicalJson).use { reader ->
			contributors.ordered.flatMap { it.restoreWarnings(reader) }
		}

	/**
	 * Whether the backup at [source] holds encrypted secrets.
	 *
	 * @throws InvalidBackupException when the zip, its manifest or its format version is invalid
	 */
	fun hasSecrets(source: Path): Boolean = ZipBackupReader.open(source, canonicalJson).use { it.hasSecrets }

	/**
	 * Checks that [passphrase] decrypts the secrets of the backup at [source] (true when it has none).
	 *
	 * @throws InvalidBackupException when the zip, its manifest, its format version or its secrets file is invalid
	 * @throws WrongPassphraseException when it does not
	 */
	fun checkPassphrase(
		source: Path,
		passphrase: String,
	) {
		ZipBackupReader.open(source, canonicalJson, passphrase).close()
	}

	/**
	 * Imports the backup at [source] in one transaction that first runs [wipe] (which empties the database), so the
	 * data is replaced completely or, on any failure, not at all. The secrets are imported only with the right
	 * [passphrase]; a wrong one fails before [wipe].
	 *
	 * @throws InvalidBackupException when the zip, its manifest, its format version or its data is invalid
	 * @throws WrongPassphraseException when [passphrase] does not decrypt the backup's secrets
	 */
	fun replace(
		source: Path,
		passphrase: String? = null,
		wipe: () -> Unit,
	): BackupManifest =
		ZipBackupReader.open(source, canonicalJson, passphrase).use { reader ->
			importTransaction.executeWithoutResult { _ ->
				wipe()
				contributors.ordered.forEach { it.import(reader) }
			}
			reader.manifest
		}

	private fun readme(manifest: BackupManifest): String =
		buildString {
			appendLine("# My Cockpit backup")
			appendLine()
			appendLine("Created ${manifest.createdAt} by My Cockpit ${manifest.appVersion}, format version ${manifest.formatVersion}.")
			appendLine()
			appendLine("## Contents")
			appendLine()
			appendLine("- `manifest.json`: format version, app version, creation time, record counts and the sha256 of every file.")
			appendLine("- `data/`: the canonical data, one JSON (`.json`) or JSON Lines (`.jsonl`) file per entity type.")
			appendLine("  Restore reads only this folder and `files/`.")
			appendLine("- `files/`: your files in their original folders and names (name clashes get \" (2)\").")
			appendLine("- `readable/`: the same data for reading without the app (Markdown, iCalendar, vCard, HTML, CSV).")
			appendLine("  It is regenerated on every backup and never read on restore.")
			appendLine()
			appendLine("Secrets (mail passwords, OAuth tokens, the TOTP secret) are only included when the backup was created")
			appendLine("with a passphrase: then `data/secrets.enc.json` holds them, encrypted with AES-256-GCM under a key derived")
			appendLine("from the passphrase with Argon2id. Without them, accounts must be reconnected after a restore and TOTP is")
			appendLine("set up again on the first login.")
			appendLine()
			appendLine("## Restore")
			appendLine()
			appendLine("- In the app: Settings → Restore, upload this zip, check the report, confirm. All current data is")
			appendLine("  replaced (and saved to `pre-restore/` in the data directory first).")
			appendLine("- On a fresh install: `java -jar cockpit-server.jar --restore=/path/to/this.zip`.")
			appendLine()
			appendLine("## Record counts")
			appendLine()
			if (manifest.counts.isEmpty()) appendLine("No data.")
			manifest.counts.forEach { (name, count) -> appendLine("- $name: $count") }
		}
}

/** Lets the zip writer finish the zip without closing the caller's stream. */
private class NonClosingOutputStream(
	target: OutputStream,
) : java.io.FilterOutputStream(target) {
	override fun write(
		b: ByteArray,
		off: Int,
		len: Int,
	) = out.write(b, off, len)

	override fun close() = flush()
}
