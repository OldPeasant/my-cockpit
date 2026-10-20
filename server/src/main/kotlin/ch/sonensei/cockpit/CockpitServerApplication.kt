package ch.sonensei.cockpit

import ch.sonensei.cockpit.backup.api.RestoreCli
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@SpringBootApplication
class CockpitServerApplication

fun main(args: Array<String>) {
	// `--restore=/path/backup.zip`: restore and exit, without the web server (SPEC.md §10.1).
	if (RestoreCli.isRequested(args)) exitProcess(RestoreCli.run(args))
	runApplication<CockpitServerApplication>(*args)
}
