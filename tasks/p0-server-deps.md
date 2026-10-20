---
title: Server dependencies, profiles and test infrastructure
status: in_progress
owner: R1963MACOS/agent-2
deps: []
touches: [server/build.gradle.kts,server/src/main/resources/application.properties,server/src/main/resources/application-dev.properties,server/src/main/resources/application-e2e.properties,server/src/test/kotlin/ch/sonensei/cockpit/support]
prio: 1
lease_until: 2026-10-20T08:59:44Z
releases: 0
extended: 
---
## Goal
Add every server library the spec needs up front (SPEC.md §2.1), so later tasks rarely touch the build file, and set up the test infrastructure: Postgres via Testcontainers (singleton container) in `test`, external-system tests (GreenMail, Radicale, WireMock) tagged `external` in a separate `integrationTest` task.

## Acceptance criteria
- [x] build.gradle.kts declares: Spring Security, Data JPA, Flyway (+ flyway-database-postgresql), PostgreSQL driver, springdoc-openapi, Testcontainers (postgresql, junit-jupiter), Kotest assertions, MockK, springmockk, WireMock, GreenMail, Angus Mail, ical4j, ez-vcard, flexmark, jsoup, Thumbnailator, an Argon2 implementation (e.g. spring-security-crypto + BouncyCastle), a TOTP library, webauthn4j, a UUIDv7 generator, logstash-logback-encoder; `./gradlew dependencies` resolves
  - verified (2026-10-20 10:37+0200 by dell26/agent-1): cd server && ./gradlew dependencies --configuration testRuntimeClasspath -> BUILD SUCCESSFUL, 0 FAILED/unresolved; tree lists starter-security (spring-security-crypto), data-jpa, flyway + flyway-database-postgresql, postgresql, springdoc-openapi-starter-webmvc-ui, testcontainers-postgresql/-junit-jupiter, kotest-assertions-core, mockk, springmockk, wiremock-standalone, greenmail-junit5, angus-mail, ical4j, ez-vcard, flexmark, jsoup, thumbnailator, bcprov-jdk18on (Argon2), java-otp (TOTP), webauthn4j-core, java-uuid-generator (UUIDv7), logstash-logback-encoder
- [x] `application.properties` holds the base config (datasource from `SPRING_DATASOURCE_*`, Flyway `out-of-order=true`, `locations=classpath:db/migration`, JSON logging in non-dev profiles, Jackson ISO dates); profiles `dev` and `e2e` exist
  - verified (2026-10-20 10:37+0200 by dell26/agent-1): read server/src/main/resources/application.properties -> datasource from ${SPRING_DATASOURCE_URL/USERNAME/PASSWORD}, flyway.locations=classpath:db/migration, out-of-order=true, write-dates-as-timestamps=false; application-dev/-e2e.properties exist; ./gradlew test -> DatasourcePropertiesTests, FlywayConfigurationTests, ProfileTests (Default JSON logstash, Dev, E2e) pass
- [x] `support/PostgresTestBase` (or an equivalent `@ServiceConnection` setup) starts one shared Postgres 16 container per test JVM; a sample repository-less `@SpringBootTest` using it passes with `./gradlew test` on Podman (DOCKER_HOST set as in ARCHITECTURE.md) — record the run
  - verified (2026-10-20 10:37+0200 by dell26/agent-1): cd server && ./gradlew test --rerun-tasks on Podman (Testcontainers connected to unix:///run/user/1000/podman/podman.sock) -> BUILD SUCCESSFUL in 11s, 9 suites / 21 tests, 0 failures/errors; PostgresTestBaseTests + ProfileTests share one postgres:16 container via @ServiceConnection
- [x] `./gradlew integrationTest` runs only tests tagged `external` and is not part of `check`; `./gradlew check` still finishes in < 60 s warm (record time)
  - verified (2026-10-20 10:37+0200 by dell26/agent-1): cd server && ./gradlew integrationTest --rerun-tasks -> BUILD SUCCESSFUL, results only for ExternalToolingSmokeTests (2 tests); ./gradlew check --console=plain task list has no integrationTest and test results exclude it; warm ./gradlew check --rerun-tasks --profile -> total build time 11.957s (< 60 s)
- [x] ARCHITECTURE.md stack table and Testing section updated (test vs integrationTest, Podman variables)
  - verified (2026-10-20 10:37+0200 by dell26/agent-1): read docs/ARCHITECTURE.md -> stack table rows Server/Server libraries/Server tests list the new libs and plugins; Testing table has ./gradlew check (test, ~12 s) vs ./gradlew integrationTest (@ExternalTest, not in check); Install + Testing document DOCKER_HOST/TESTCONTAINERS_RYUK_DISABLED for Podman

## Notes
- ASSUMPTION (2026-10-20 10:21+0200 by dell26/agent-1) [confirmed 2026-10-20 10:26+0200 by Peter Gürtler]: JSON logging uses Spring Boot's built-in structured logging (logging.structured.format.console=logstash) instead of a logback-spring.xml; logstash-logback-encoder is declared as required but not wired yet. The dev profile blanks the property for plain logs; tests use the default profile and therefore log JSON.
- ASSUMPTION (2026-10-20 10:21+0200 by dell26/agent-1) [confirmed 2026-10-20 10:27+0200 by Peter Gürtler]: Library picks: TOTP = com.eatthepath:java-otp 0.4.0 (small, no QR deps); UUIDv7 = com.fasterxml.uuid:java-uuid-generator 5.1.0; Argon2 = spring-security-crypto Argon2PasswordEncoder + bcprov-jdk18on; flexmark core + GFM extensions instead of flexmark-all; wiremock-standalone (shaded Jetty) to avoid classpath clashes.
- ASSUMPTION (2026-10-20 10:21+0200 by dell26/agent-1) [confirmed 2026-10-20 10:26+0200 by Peter Gürtler]: Spring Security starter added without a SecurityFilterChain: Boot defaults apply (everything secured except /actuator/health, generated user) until p0-auth-setup adds the real config. Existing context tests now extend PostgresTestBase because JPA/Flyway need a database, so ./gradlew test (and .taskcheck) needs Docker/Podman.
- ASSUMPTION (2026-10-20 10:21+0200 by dell26/agent-1) [confirmed 2026-10-20 10:25+0200 by Peter Gürtler]: integrationTest reuses the test source set and selects by JUnit tag 'external' (@ExternalTest) instead of a separate source set; Hibernate ddl-auto=validate and open-in-view=false as base defaults.
- ASSUMPTION (2026-10-20 10:38+0200 by dell26/agent-1): Fixed tests/test_quality_gate.py (outside touches): it leaked the runner's TASKS_ROOT into tasks.py, so .taskcheck failed with 'exists' and wrote stray tasks/a,b,c.md into the clone; one-line env filter fix
- release by dell26/agent-1: handed off by dell26/agent-1: You've hit your session limit · resets 2pm (Europe/Berlin)
