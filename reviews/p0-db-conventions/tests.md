# Test Report

Date: 2026-10-20

Task: p0-db-conventions - Persistence conventions: base entity, UUIDv7, clock, migrations layout

Scope: second round after the review fixes. Immutable base `0fcbe9f6d370200695523fb59ddfd7003b32979d` (from the parent),
measured HEAD `173c0895c733d0695e134d699ea3b009219b75ff` on branch `main`. The parent named HEAD `5c646026439a06708b4190a5c246bac95c781607`.
In the meantime the branch was rebased onto two `assume:` commits (`d7b3da2`, `ca9df68`), which only touch
`tasks/p0-db-conventions.md`. `git diff 5c64602 HEAD --stat` shows only that task file, so code and tests are identical.
Task-owned commits at HEAD: `bdffe2a` (implementation), `d5c837b` (tests, first review reports) and `173c089` (review fixes
CR-1, CR-2, CR-4). These are the rebased equivalents of `e504db6`, `0ed477d` and `5c64602`.
Uncommitted inputs: my edit to `PersistenceConventionsTests.kt` (blob `5983dfbeeb583f2bac5b58748d75ebae4085b519`). In addition,
`reviews/p0-db-conventions/review.md` was modified by the code-reviewer; I did not touch it, and it is not a test input.

Requirements:
- `tasks/p0-db-conventions.md`: acceptance criteria 1-4 and the recorded assumptions
- `reviews/p0-db-conventions/review.md` (CR-1 to CR-4) and `reviews/p0-db-conventions/fixes.md` (fixes for CR-1, CR-2, CR-4; CR-3 already covered)
- `SPEC.md` §4.2, `docs/ARCHITECTURE.md` "Persistence conventions" and "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/persistence/PersistenceConventionsTests.kt` (1 test added in this round)

No existing test was modified, weakened or disabled. No production file was changed.

## Tests added or updated

Count: 1 (this round). The package now has 36 tests: BaseEntityTests 5, ClockConfigurationTests 4, MigrationConventionTests 2,
MutableClockTests 3, PersistenceConventionsTests 17, UuidV7Tests 5.

- `an entity compares with a detached uninitialized proxy without touching the session` (Postgres via Testcontainers). This test
  guards the CR-2 fix. It gets a proxy with `getReferenceById` outside a transaction, so the proxy is detached and uninitialized.
  It then asserts that `saved == detached` is true, that `other == detached` is false, and that the proxy is still uninitialized.
  With the previous `Hibernate.getClass(other)`, this would have thrown `LazyInitializationException`.
  It also implicitly guards the non-final `deletedAt`: a non-proxyable class would return a loaded entity here.

Review of how the fixes are tested (existing tests, all run in this round):
- CR-1 (microsecond clock): `the application clock ticks in microseconds, the precision of timestamptz`,
  `auditing timestamps are truncated to microseconds, also from a substituted clock`, and
  `sub-microsecond clock instants are stored so that saved and reloaded timestamps are equal` (DB round trip for
  `createdAt`, `updatedAt` and `deletedAt` via `softDelete(clock)`). These tests are adequate.
- CR-2 (proxy-safe equals/toString, proxyable entities): `a lazy proxy equals the loaded entity with the same id` (asserts that
  the proxy is uninitialized and that `saved == proxy` does not initialize it), plus the new detached-proxy test above. With these, the fix is adequately tested.
- CR-3: `creating the same client id twice fails instead of overwriting the first row`. The test is adequate.
- CR-4 (test migration `V202610200000_1__...`): `MigrationConventionTests` (the suffix is required exactly for `test_` migrations,
  and the lowest version is compared including the suffix) and `the first core migration creates no tables` (Flyway `target=202610200000`). The tests are adequate.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `BaseEntity` UUIDv7 id generated server-side | `create assigns a UUIDv7 id, timestamps from the clock and version 0`; `a new entity gets a fresh UUIDv7 id and counts as new` | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.persistence.*'` | Pass |
| AC1 id accepted from the client (idempotent create), must be UUIDv7 | `a client-supplied UUIDv7 id is kept, and creating it again is detectable`; `a client-supplied UUIDv7 id is kept`; `creating the same client id twice fails instead of overwriting the first row`; `ids that are not UUIDv7 are rejected`; `a version 7 id with a non-RFC variant is rejected`; `the nil id is rejected` | same | Pass |
| AC1 `createdAt`/`updatedAt` UTC `timestamptz` | `timestamps are stored as timestamptz`; `timestamps are stored as the exact UTC instant of the clock`; `update moves updatedAt to the clock's time, keeps createdAt and bumps the version`; `sub-microsecond clock instants are stored so that saved and reloaded timestamps are equal` | same | Pass |
| AC1 `version` (`@Version`) | `update moves updatedAt ... bumps the version`; `saving a stale version raises an optimistic locking conflict` | same | Pass |
| AC1 `SoftDeletable` with `deletedAt` | `soft delete keeps the first deletion time and restore brings the row back`; `soft-deleted rows are excluded by the repository helper` | same | Pass |
| AC1 entity identity (equals/hashCode, proxies; CR-2) | `entities are equal by type and id`; `a lazy proxy equals the loaded entity with the same id`; `an entity compares with a detached uninitialized proxy without touching the session` | same | Pass |
| AC2 injectable `Clock` bean for all timestamps; tests substitute a mutable clock (incl. CR-1 precision) | `the injectable clock is the one tests substitute`; ClockConfigurationTests (4); MutableClockTests (3); `sub-microsecond clock instants ...` | same | Pass |
| AC3 migration convention (`<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql`; test-only suffix, CR-4) | `every migration lives in a module folder and is named V yyyyMMddHHmm __ module _ desc` (the documentation text in ARCHITECTURE.md was checked by reading, not by a test) | same | Pass |
| AC3 first migration enables `pg_trgm` and creates nothing else | `the first core migration ran and enabled pg_trgm`; `the first core migration creates no tables`; `the core pg_trgm migration has the lowest version` | same | Pass |
| AC3 "never edit applied ones" | process rule; Flyway checksum validation (existing config) | n/a | Not testable as task behavior |
| AC4 UUIDv7 ids are time-ordered | `ids are time-ordered and unique, also within the same millisecond`; `ids created later have a later timestamp prefix`; `active rows come in creation order and deleted rows newest deletion first` | same | Pass |
| AC4 optimistic locking conflict on a stale version | `saving a stale version raises an optimistic locking conflict` | same | Pass |
| AC4 soft-deleted rows excluded by the repository helper | `soft-deleted rows are excluded by the repository helper`; `active rows come in creation order and deleted rows newest deletion first` | same | Pass |
| AC4 `./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.*'` passes (Testcontainers) | all 36 tests in the package | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.persistence.*'` (Docker, Postgres Testcontainers) | Pass (36 tests, 0 failures, 0 skipped) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.persistence.*' --console=plain`

Result:

Pass (BUILD SUCCESSFUL, 2026-10-20 ~09:09:42Z, 36 tests). The first attempt of the new test also asserted
`setOf(saved).contains(detached)`. It failed with `LazyInitializationException`, because `contains` calls `hashCode()` on the
proxy itself, and Hibernate sends overridden `equals`/`hashCode`/`toString` called on a proxy to the target, which loads it.
Entity code cannot change that. The CR-2 fix only claims, and can only provide, safety on the receiving side (`entity == proxy`).
That assertion was therefore wrong for the requirement. I removed it and documented the limitation below. This is not a production defect.

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport --console=plain`

Result:

Pass. BUILD SUCCESSFUL, exit 0, 21 suites, 88 tests, 0 failures, 0 errors, 0 skipped (from `server/build/test-results/test/*.xml`,
suite timestamps 2026-10-20T09:09:48Z-09:10:00Z). This includes ktlint. The web and Android suites were not run, because the task touches only `server/`.

## .taskcheck

Result:

Not run. The tool permission system denied `sh scripts/check.sh` in this sub-agent session. Its server part (`./gradlew check`)
passed above. The parent worker must run `.taskcheck`.

## Bugs found

None. No test exposed behavior that violates the acceptance criteria, the recorded assumptions or the fixed review findings.

Known limitation (not a defect, informational): calling `hashCode()`, `toString()` or `equals()` on an uninitialized Hibernate
proxy itself (for example, putting a detached proxy into a `HashSet`) still initializes it, and throws `LazyInitializationException`
outside a session. Hibernate behaves this way for every overridden method. CR-2 asked to stop `equals` from initializing the
*other* side, which is now fixed and tested.

## Coverage

Status: verified (whole-file, for every instrumentable task-changed production file, final code).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage. Every scoped production file was added by this task
(`git diff 0fcbe9f HEAD` shows only new files under `server/src/main`), so all executable lines are changed lines. No separate changed-line tool was run.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p0-db-conventions`; base `0fcbe9f6d370200695523fb59ddfd7003b32979d`
  (from the parent); HEAD `173c0895c733d0695e134d699ea3b009219b75ff`; task commits `bdffe2a`, `d5c837b`, `173c089` (via `git log 0fcbe9f..HEAD`;
  the other two commits in the range are task-file-only `assume:` commits). The file list was cross-checked against `git diff --stat 0fcbe9f..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `173c089`, plus the uncommitted `PersistenceConventionsTests.kt`
  (blob `5983dfbeeb583f2bac5b58748d75ebae4085b519`). There are no uncommitted production changes (`git status --short` after the run).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/{BaseEntity,ClockConfiguration,MutableClock,SoftDeletable,UuidV7}.kt`.
  Excluded: `SoftDeletableRepository.kt` (an interface with only abstract `@Query` methods, no bytecode counters; it is exercised through the Spring Data
  proxy in PersistenceConventionsTests); `db/migration/core/V202610200000__core_enable_pg_trgm.sql` (SQL, cannot be instrumented; verified by the
  Flyway tests); `docs/ARCHITECTURE.md` (documentation). There are no renamed or deleted production files. The test migration rename (CR-4) concerns test resources only.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15.202606040825 via the Gradle `jacoco` plugin (Gradle 9.8.1);
  `./gradlew check jacocoTestReport --console=plain` run in `server/`; the full server `test` task.
- Run start/completion time and exit status: JaCoCo session `R1963MACOS.local-6174c320`, start 2026-10-20 11:09:48 (+02:00), dump 11:10:00 (+02:00). Exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (git blob
  `faeb14c9e91073476c6145718a73c2e9394f4500`), `server/build/reports/jacoco/test/html/index.html`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Gradle re-executed `test` (the test selection changed from the focused run) after the
  last test edit, the session postdates that edit, and `git status` after the run shows no further source changes.
- Final run after latest test additions and production fixes: yes. The run covers the final code including fixes CR-1, CR-2 and CR-4 and this round's test.

Values below use covered/total (percentage). "Statements" are JaCoCo INSTRUCTION counters, because JaCoCo has no statement counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/BaseEntity.kt` | 113/134 (84.3%) | 16/16 (100%) | 11/15 (73.3%) | 21/26 (80.8%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/ClockConfiguration.kt` | 29/29 (100%) | N/A (0/0) | 5/5 (100%) | 4/4 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/MutableClock.kt` | 81/81 (100%) | 2/2 (100%) | 8/8 (100%) | 9/9 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/SoftDeletable.kt` | 39/39 (100%) | 4/4 (100%) | 7/7 (100%) | 11/11 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/persistence/UuidV7.kt` | 24/24 (100%) | 4/4 (100%) | 3/3 (100%) | 3/3 (100%) |
| **Aggregate (summed counters, 5 measured files; excludes `SoftDeletableRepository.kt` [no bytecode] and the SQL migration [not instrumentable])** | 286/307 (93.2%) | 26/26 (100%) | 34/38 (89.5%) | 48/53 (90.6%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BaseEntity.kt:31-33` (class header; partly covered, lines 32-33 not covered) | Kotlin-generated constructor bytecode (default-argument / no-arg plugin constructor) that no application or test code calls | Non-material generated glue; left uncovered |
| `BaseEntity.kt:41,46,51` (`protected set` of `createdAt`/`updatedAt`/`version`) | Generated setters. Hibernate uses field access and auditing writes the fields, so nothing calls them | Non-material; the compiler enforces read-only access from outside |
| All branches in `equals`/`entityClass` (`BaseEntity.kt:71-74,84`), including both proxy paths | CR-2 behavior | Covered (16/16 branches); the detached-proxy test was added this round |
| `SoftDeletable.kt` `deletedAt` setter | In the previous round it was uncovered (final). Now it is open and Hibernate's proxy machinery reaches it | Covered |

## Unable to test

- `.taskcheck` (`sh scripts/check.sh`): the tool permission was denied here. Follow-up: the parent runs it.
- AC3 "never edit applied migrations": a process rule, enforced at startup by Flyway checksum validation (existing configuration). No test was added and no follow-up is needed.
- Nanosecond behavior of the real `Clock.systemUTC()` on Linux: on macOS the system clock already returns microseconds. The tick to microseconds is asserted
  (`nano % 1000 == 0`), but the Linux nanosecond source itself is not exercised on this machine. The truncation path is covered with a nanosecond `MutableClock`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The review fixes (CR-1, CR-2, CR-4) and CR-3 are covered
by tests. One detached-proxy regression test was added for CR-2. All 36 `core.persistence` tests and the full server suite (88 tests, ktlint, JaCoCo)
pass on the final code. `.taskcheck` remains to be run by the parent worker.
