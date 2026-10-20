package ch.sonensei.cockpit.backup.api

import ch.sonensei.cockpit.CockpitServerApplication
import ch.sonensei.cockpit.backup.domain.InvalidBackupException
import ch.sonensei.cockpit.backup.domain.RestoreFailedException
import ch.sonensei.cockpit.backup.domain.RestoreReport
import ch.sonensei.cockpit.backup.domain.RestoreService
import ch.sonensei.cockpit.core.auth.domain.SetupService
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Disaster recovery on a fresh install (SPEC.md §10.1): `java -jar cockpit-server.jar --restore=/path/backup.zip`
 * starts the application without the web server, validates the backup, replaces all data with it like a confirmed
 * restore (the current data, if any, goes to `pre-restore/` first) and exits. The next normal start skips the setup
 * flow when the backup contains the owner account.
 */
object RestoreCli {
	const val OPTION = "--restore="

	fun isRequested(args: Array<String>): Boolean = args.any { it.startsWith(OPTION) }

	/**
	 * Runs the restore named by [OPTION] in [args] (the other arguments configure the application as usual) and returns
	 * the exit code: 0 restored, 1 invalid backup or failed import (nothing changed), 2 no such file.
	 */
	fun run(
		args: Array<String>,
		out: PrintStream = System.out,
	): Int {
		val path = Path.of(args.last { it.startsWith(OPTION) }.removePrefix(OPTION))
		if (!Files.isRegularFile(path)) {
			out.println("Restore: $path is not a file")
			return 2
		}
		val application = SpringApplication(CockpitServerApplication::class.java).apply { setWebApplicationType(WebApplicationType.NONE) }
		application.run(*args.filterNot { it.startsWith(OPTION) }.toTypedArray()).use { context ->
			val restores = context.getBean(RestoreService::class.java)
			val report = restores.validate(path)
			print(report, out)
			if (!report.valid) {
				out.println("Restore: the backup is not valid; nothing was changed")
				return 1
			}
			val result =
				try {
					restores.replace(path)
				} catch (e: InvalidBackupException) {
					e.problems.forEach { out.println("Problem: $it") }
					out.println("Restore: the backup could not be imported; nothing was changed")
					return 1
				} catch (e: RestoreFailedException) {
					out.println("Restore: ${e.message} (see the log)")
					return 1
				}
			out.println("Restore: done. The replaced data was saved to ${result.preRestoreBackup} in the data directory.")
			if (context.getBean(SetupService::class.java).isSetupRequired()) {
				out.println("Restore: the backup has no active owner account; open the app to run the setup.")
			} else {
				out.println("Restore: the owner account was restored; setup is skipped.")
			}
			return 0
		}
	}

	private fun print(
		report: RestoreReport,
		out: PrintStream,
	) {
		out.println(
			"Backup: format version ${report.formatVersion ?: "?"}, app version ${report.appVersion ?: "?"}, created ${report.createdAt ?: "?"}",
		)
		report.counts.forEach { (name, count) -> out.println("  $name: $count") }
		report.problems.forEach { out.println("Problem: $it") }
		report.warnings.forEach { out.println("Warning: $it") }
	}
}
