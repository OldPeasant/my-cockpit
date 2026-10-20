package ch.sonensei.cockpit.support

import org.junit.jupiter.api.Tag

/**
 * Marks tests against external systems (GreenMail, Radicale, WireMock, ...). They run only with
 * `./gradlew integrationTest`, never in `test`/`check`.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag(EXTERNAL_TAG)
annotation class ExternalTest

const val EXTERNAL_TAG = "external"
