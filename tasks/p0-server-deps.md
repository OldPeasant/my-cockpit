---
title: Server dependencies, profiles and test infrastructure
status: todo
owner:
deps: []
touches: [server/build.gradle.kts,server/src/main/resources/application.properties,server/src/main/resources/application-dev.properties,server/src/main/resources/application-e2e.properties,server/src/test/kotlin/ch/sonensei/cockpit/support]
prio: 1
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
