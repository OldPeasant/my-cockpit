package ch.sonensei.cockpit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CockpitServerApplication

fun main(args: Array<String>) {
	runApplication<CockpitServerApplication>(*args)
}
