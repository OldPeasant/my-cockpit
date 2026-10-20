# Code Review

Date: 2026-10-20

Task: p0-db-conventions - Persistence conventions: base entity, UUIDv7, clock, migrations layout

Scope: second review round on the final code after fixes. `git diff 0fcbe9f6d370200695523fb59ddfd7003b32979d..5c646026439a06708b4190a5c246bac95c781607` (committed changes only). The working tree has one uncommitted change from the concurrently running test-writer (`PersistenceConventionsTests.kt`, a new detached-proxy test). It is outside the reviewed scope and is only mentioned as an observation below.

Requirements:
- `tasks/p0-db-conventions.md` acceptance criteria 1-4 and its ASSUMPTION notes (JPA auditing with a Clock-backed DateTimeProvider; client ids must be UUIDv7; `Persistable` for assigned ids; test-only fixture migration)
- SPEC.md §4.2 (UUIDv7 ids, UTC `timestamptz` timestamps, `version` optimistic locking, soft delete via `deletedAt`)
- docs/ARCHITECTURE.md ("Persistence conventions" section added by this task), AGENTS.md project notes (migration path/naming, module packages), docs/QUALITY.md (report contract)
- Previous review `reviews/p0-db-conventions/review.md` (CR-1..CR-4) and `reviews/p0-db-conventions/fixes.md`
- Existing configuration: `server/build.gradle.kts` (`plugin.jpa`, `allOpen` for `@Entity`/`@MappedSuperclass`/`@Embeddable`, no `invokeInitializers`)

Git range:
- Branch: main
- HEAD: 5c646026439a06708b4190a5c246bac95c781607
- Base/start: 0fcbe9f6d370200695523fb59ddfd7003b32979d
- Commits reviewed: 3 (e504db6 implementation, 0ed477d tests and first review reports, 5c64602 review fixes)

Commands and results:
- `git status --short` -> one modified, uncommitted test file (test-writer work in progress, not reviewed)
- `git diff 0fcbe9f..HEAD -- docs/ARCHITECTURE.md server/src/main/resources server/src/test/resources` -> ARCHITECTURE.md section, `core/V202610200000__core_enable_pg_trgm.sql` (extension only), test fixture migration renamed to `V202610200000_1__core_test_persistence_fixture.sql`
- I read all 6 main files in `core/persistence` and the committed tests (`BaseEntityTests`, `ClockConfigurationTests`, `MigrationConventionTests`, `PersistenceConventionsTests`, `PersistenceFixtures`) in full.
- `grep` for `Instant.now(`, other `Clock` beans and `@EnableJpaAuditing` in `server/src/main` -> only `ClockConfiguration`. No conflicting beans and no direct `Instant.now()` calls.
- Test runs were not repeated by the reviewer, because a test-writer is editing the same test package at the same time. Reported in fixes.md: `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.persistence.*'` (Docker, Postgres Testcontainers) -> 35 tests passed; `cd server && ./gradlew check` -> BUILD SUCCESSFUL. `.taskcheck` was not run by the fixer, so the parent must run it.

Acceptance criteria assessment:
1. `BaseEntity` has a UUIDv7 id (generated, or supplied by the client and validated for version 7 and the RFC variant), `createdAt`/`updatedAt` (`Instant`, `timestamptz`, asserted against `information_schema`) and `@Version version`. `SoftDeletable`/`SoftDeletableEntity` has `deletedAt`. `Persistable` makes `save()` insert for assigned ids. A duplicate client id fails with `DataIntegrityViolationException` and does not overwrite the row (tested). Met.
2. A single `Clock` bean (UTC, microsecond tick) feeds the auditing `DateTimeProvider` and `softDelete(clock)`. Tests substitute a `@Primary MutableClock` and check this (`appClock shouldBe clock`). Met.
3. The migration convention is documented with the required path pattern and the "never edit applied ones" rule. The first migration only enables `pg_trgm`, checked by a Flyway `target` run in an empty schema and by `MigrationConventionTests`. Met.
4. Tests cover UUIDv7 ordering, the optimistic-locking conflict on a stale save, and soft-delete exclusion through `findActiveById`/`findAllActive`/`findAllDeleted`, against Postgres Testcontainers. The focused Gradle command is reported passing (see above). Met, provided the parent's own verified run confirms it.

Status of earlier findings:
- CR-1 (P2, clock precision): **Resolved.** `ClockConfiguration.kt:23` ticks in microseconds. `Clock.storableInstant()` (`ClockConfiguration.kt:30`) truncates the values used by auditing and `softDelete`, so a substituted nanosecond test clock is also covered. Tests: `ClockConfigurationTests` (tick and truncation) and the Testcontainers test `sub-microsecond clock instants are stored so that saved and reloaded timestamps are equal`.
- CR-2 (P3, proxy initialization in `equals`): **Resolved for the entity-as-receiver case.** `BaseEntity.kt:70-85` reads the class and id of a proxy argument through `hibernateLazyInitializer` without initializing it, and the committed test asserts that `saved == proxy` leaves the proxy uninitialized. Removing `final` from `deletedAt` (`SoftDeletable.kt:27`) fixed a real bug that the fixer found: Hibernate could not create lazy proxies for `SoftDeletableEntity` subclasses at all (HHH000305). A remaining limitation is described in CR-5.
- CR-3 (P3, duplicate client id not tested): **Resolved.** The test `creating the same client id twice fails instead of overwriting the first row` (`PersistenceConventionsTests.kt:161`) asserts the exception, and that the stored title and version are unchanged.
- CR-4 (P3, test migration version collision): **Resolved.** The fixture is now `V202610200000_1__...` (Flyway version `202610200000.1`), which a plain `V<yyyyMMddHHmm>` file cannot produce. `MigrationConventionTests` requires the `_<n>` suffix exactly for `test_` migrations, and ARCHITECTURE.md documents the rule. I agree with the fixer's reason for rejecting a 1970 version.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-5 | Proxy as receiver still initializes on `equals`/`hashCode`/`toString`, which the KDoc and fixes.md do not say**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/BaseEntity.kt:77` (also `:79`, `:82-84`)
    - Category: Quality
    - Problem: `equals`, `hashCode` and `toString` are overridden and open (`allOpen`). Hibernate's proxy interceptor therefore delegates these calls to the real entity, and to do that it initializes the proxy. Only the entity-as-receiver direction (`entity == proxy`) avoids initialization. `proxy == entity`, `proxy.hashCode()` (for example `hashSetOf(proxy)` or `proxy in setOf(entity)`) and `proxy.toString()` still load the row, and they throw `LazyInitializationException` on a detached proxy. Inside the target object `this` is never a proxy, so the proxy branch of `entityClass(this)` in `toString` is never reached. The KDoc comment ("read from a lazy proxy without initializing it") and fixes.md ("`equals` and `toString` use it") suggest that `toString` is also proxy-safe. The committed proxy test makes the receiver-side calls inside a transaction, so it cannot detect this.
    - Why it matters: Module code may log a detached reference or put proxies into a hash set (for example link sets, SPEC.md §14). The KDoc leads developers to expect this to be safe, but it fails at runtime when the session is closed.
    - Suggested fix: State the limitation in the `BaseEntity` KDoc and the ARCHITECTURE.md "Entities" bullet. Example wording: "comparing with or hashing an uninitialized proxy as the receiver loads it; compare `entity == proxy`, or compare ids". In `toString`, use `javaClass.simpleName` or keep the helper only for the `other` argument. A code-level fix (final `equals`/`hashCode`) is not recommended, because Hibernate proxies need open methods.

## Observations (outside the reviewed scope, not counted)

- The test `an entity compares with a detached uninitialized proxy without touching the session` in the uncommitted working tree is not part of the reviewed scope. It is likely to fail as written. `setOf(saved)` is a `Collections.singleton`, and its `contains(detached)` calls `detached.equals(saved)`. That call initializes the detached proxy (see CR-5) and should throw `LazyInitializationException`. The test-writer should run it before committing it, and should either assert `saved in setOf(detached)`-style receiver-safe usage only, or document the limitation instead.

## Review conclusion

No material issues found.

CR-1 to CR-4 are resolved in the current code, and all four acceptance criteria are implemented with meaningful tests. CR-5 is a low-priority documentation and clarity item that the parent worker may fix or explicitly defer. The parent must still run `.taskcheck` and record its own verified test evidence, and then decide whether the uncommitted test-writer change requires another review round.
