---
title: Server dependencies, profiles and test infrastructure
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/build.gradle.kts,server/src/main/resources/application.properties,server/src/main/resources/application-dev.properties,server/src/main/resources/application-e2e.properties,server/src/test/kotlin/ch/sonensei/cockpit/support]
prio: 1
lease_until: 2026-10-20T08:46:22Z
---
## Goal
Add every server library the spec needs up front (SPEC.md §2.1), so later tasks rarely touch the build file, and set up the test infrastructure: Postgres via Testcontainers (singleton container) in `test`, external-system tests (GreenMail, Radicale, WireMock) tagged `external` in a separate `integrationTest` task.

## Acceptance criteria
- [ ] build.gradle.kts declares: Spring Security, Data JPA, Flyway (+ flyway-database-postgresql), PostgreSQL driver, springdoc-openapi, Testcontainers (postgresql, junit-jupiter), Kotest assertions, MockK, springmockk, WireMock, GreenMail, Angus Mail, ical4j, ez-vcard, flexmark, jsoup, Thumbnailator, an Argon2 implementation (e.g. spring-security-crypto + BouncyCastle), a TOTP library, webauthn4j, a UUIDv7 generator, logstash-logback-encoder; `./gradlew dependencies` resolves
- [ ] `application.properties` holds the base config (datasource from `SPRING_DATASOURCE_*`, Flyway `out-of-order=true`, `locations=classpath:db/migration`, JSON logging in non-dev profiles, Jackson ISO dates); profiles `dev` and `e2e` exist
- [ ] `support/PostgresTestBase` (or an equivalent `@ServiceConnection` setup) starts one shared Postgres 16 container per test JVM; a sample repository-less `@SpringBootTest` using it passes with `./gradlew test` on Podman (DOCKER_HOST set as in ARCHITECTURE.md) — record the run
- [ ] `./gradlew integrationTest` runs only tests tagged `external` and is not part of `check`; `./gradlew check` still finishes in < 60 s warm (record time)
- [ ] ARCHITECTURE.md stack table and Testing section updated (test vs integrationTest, Podman variables)

## Notes
- ASSUMPTION (2026-10-20 10:21+0200 by dell26/agent-1) [confirmed 2026-10-20 10:26+0200 by Peter Gürtler]: JSON logging uses Spring Boot's built-in structured logging (logging.structured.format.console=logstash) instead of a logback-spring.xml; logstash-logback-encoder is declared as required but not wired yet. The dev profile blanks the property for plain logs; tests use the default profile and therefore log JSON.
- ASSUMPTION (2026-10-20 10:21+0200 by dell26/agent-1) [confirmed 2026-10-20 10:27+0200 by Peter Gürtler]: Library picks: TOTP = com.eatthepath:java-otp 0.4.0 (small, no QR deps); UUIDv7 = com.fasterxml.uuid:java-uuid-generator 5.1.0; Argon2 = spring-security-crypto Argon2PasswordEncoder + bcprov-jdk18on; flexmark core + GFM extensions instead of flexmark-all; wiremock-standalone (shaded Jetty) to avoid classpath clashes.
- ASSUMPTION (2026-10-20 10:21+0200 by dell26/agent-1) [confirmed 2026-10-20 10:26+0200 by Peter Gürtler]: Spring Security starter added without a SecurityFilterChain: Boot defaults apply (everything secured except /actuator/health, generated user) until p0-auth-setup adds the real config. Existing context tests now extend PostgresTestBase because JPA/Flyway need a database, so ./gradlew test (and .taskcheck) needs Docker/Podman.
- ASSUMPTION (2026-10-20 10:21+0200 by dell26/agent-1) [confirmed 2026-10-20 10:25+0200 by Peter Gürtler]: integrationTest reuses the test source set and selects by JUnit tag 'external' (@ExternalTest) instead of a separate source set; Hibernate ddl-auto=validate and open-in-view=false as base defaults.
