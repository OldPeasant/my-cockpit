# Test Report

Date: 2026-10-25

Task: mail-sync-perf - Cheaper link checks and loadOlder candidate narrowing in mail sync

Scope: `git diff eeb4f9dc0cef3ec4b9b62cc62e2a708b9fe58bbb..0dfb037cde206c3f5a819c41c932d2e30b31748b` (task commit 0dfb037c on top of base eeb4f9dc, branch `main`), plus the uncommitted test changes listed below. No uncommitted production changes.

Requirements:
- `tasks/mail-sync-perf.md` acceptance criteria
- `reviews/mail-header-sync/review.md` CR-6, CR-7, CR-8 (background)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent instructions (scope: tests in `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync` and core/items tests)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStoreTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/items/LinkServiceTests.kt`

## Tests added or updated

Count: 11 (10 added, 1 updated)

`MailHeaderSyncServiceTests` (pure unit tests, MockK + fake `RemoteFolder`):
- Fake `RemoteFolder` gained an opt-in `datedSearch` mode: `uidsSince` answers like IMAP `SEARCH SINCE` (INTERNALDATE compared by UTC day). Default behaviour is unchanged for the existing tests.
- `@BeforeEach` now stubs `store.oldestReceivedAt(any())` to `null`. Before, the relaxed mock answered with a mocked `Instant` whose `isAfter` was `false`, so the existing loadOlder tests bypassed the narrowing loop by accident.
- Updated: `loadOlder fetches the most recently arrived messages missing from the cache, whatever their UID` now expects the `uidsSince` call (now - 7 days) before `receivedDates`. Its assertions (selected UIDs, dates read for all missing, headers) are unchanged.
- Added: `loadOlder reads the dates only of missing messages that arrived from a week before the oldest cached one`
- Added: `loadOlder doubles the window until it holds at least the limit of missing messages`
- Added: `loadOlder takes every missing message as candidate once the window reaches the epoch`
- Added: `loadOlder without a cached arrival time starts the window a week before now`
- Added: `loadOlder still picks missing messages that arrived after the oldest cached one first`

`MailHeaderStoreTests` (Postgres, `PostgresTestBase`):
- Added: `a message that is only the target of a link counts as linked and is detached, not deleted`
- Added: `a message linked under another item type with the same id is not linked`
- Added: `oldestReceivedAt is the earliest arrival of the located messages of that folder only` (ignores null dates, other folders and detached rows; null for an empty folder)

`LinkServiceTests` (Postgres, `PostgresTestBase`):
- Added: `hasLinks is true for the source and the target of a link and false for unlinked items` (also checks that the same id under another type is false)
- Added: `hasLinks counts sync links and is false again once the links are gone`

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `LinkService.hasLinks` is backed by an exists query and works in both directions | `hasLinks is true for the source and the target of a link and false for unlinked items`, `hasLinks counts sync links and is false again once the links are gone` | `./gradlew test --tests '*LinkServiceTests'` | Not run: initializationError, Docker daemon unavailable (Testcontainers `DockerClientProviderStrategy`). Tests compile and pass ktlint. |
| AC1: `MailHeaderStore.isLinked` uses it (source and target direction, type matters) | `a message that is only the target of a link counts as linked and is detached, not deleted`, `a message linked under another item type with the same id is not linked`, plus existing `vanished UIDs are deleted unless linked, then only detached`, `detachFolder and pruneDetached keep linked rows ...`, `linkedDetachedMessageIds ...` | `./gradlew test --tests '*MailHeaderStoreTests*'` | Not run: Docker unavailable (initializationError) |
| AC1 (support): `oldestReceivedAt` query | `oldestReceivedAt is the earliest arrival of the located messages of that folder only` | `./gradlew test --tests '*MailHeaderStoreTests*'` | Not run: Docker unavailable |
| AC2: loadOlder narrows candidates and reads dates only of candidates | `loadOlder reads the dates only of missing messages ...`, `loadOlder without a cached arrival time ...` | `./gradlew test --tests '*MailHeaderSyncServiceTests*'` | Pass (39/39) |
| AC2: window doubling, and the fallback to all missing at the epoch | `loadOlder doubles the window ...`, `loadOlder takes every missing message as candidate once the window reaches the epoch` | same | Pass |
| AC2: still picks by arrival date (gaps newer than the oldest cached first, UID order is not arrival order, undated last) | `loadOlder still picks missing messages that arrived after the oldest cached one first`, `loadOlder fetches the most recently arrived ... whatever their UID`, `loadOlder ranks messages without a date last and ties by UID` | same | Pass |
| AC2: no date reads when the missing messages fit the limit | existing `loadOlder takes every missing message without reading dates when they fit into the limit` | same | Pass |
| AC3: class comment (CR-8) | none (documentation only) | n/a | Not testable (verified by reading the diff: the Message-ID lookup is now described under the full sync) |
| AC4: unit tests pass | full server unit suite | `./gradlew test --continue` | Fail: 2650 tests, 369 failed. All 175 failing classes fail on Testcontainers/Docker initialization or the resulting ApplicationContext load failure (classified from `build/test-results/test/*.xml`; no failure without a Docker/context cause). All non-Postgres tests pass. |
| AC4: `external` GreenMail sync tests pass | `HeaderSyncGreenMailTests` (and other `external` tests) | `./gradlew integrationTest --tests '*HeaderSyncGreenMailTests*'` | Not run: initializationError, Docker unavailable (the class extends `PostgresTestBase`) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*MailHeaderSyncServiceTests*'`

Result:

Pass (39 tests, 0 failures, 0 errors, 0 skipped)

`cd server && ./gradlew test --tests '*MailHeaderStoreTests*' --tests '*LinkServiceTests'`

Result:

Fail: 2 initializationError (Docker daemon unavailable). Not a test or production failure. Must be re-run on a machine with Docker.

`cd server && ./gradlew compileTestKotlin ktlintCheck`

Result:

Pass

## Full test execution

Command:

`cd server && ./gradlew test --continue`

Result:

Fail: 2650 tests completed, 369 failed. Every failure is a Docker/Testcontainers initialization failure (Postgres) or the resulting ApplicationContext load failure. No failure has another cause. The in-scope classes that could not start: `LinkServiceTests`, `LinkServiceEdgeCaseTests`, `LinkWebTests`, `MailHeaderStoreTests`.

## .taskcheck

Result:

Fail (environment): `sh scripts/check.sh tfp server`: tfp gate tests passed (20 tests OK); server `./gradlew check` failed with the same 369 Docker-only failures. Web and Android parts not run: the task does not touch them.

## Bugs found

None. No production defects found in the reviewed scope.

Note (test-quality, not a production bug): before this change, the existing loadOlder tests passed only because the relaxed `MailHeaderStore` mock returned a mocked `Instant` for `oldestReceivedAt`, which made `newestMissing` return early. The explicit `null` stub fixes this, so the narrowing loop now actually runs in those tests.

## Coverage

Status: partial: only `MailHeaderSyncService.kt` is measured meaningfully. The Postgres-backed code (`LinkService`, `MailHeaderStore`, the JPQL repository queries) cannot run without Docker. Its numbers below come from a unit-only run and are not representative.

Metric: whole-file coverage of task-changed production files, from a run that selected only `MailHeaderSyncServiceTests`.
Changed-line coverage: measured by intersecting the added/modified lines of `git diff -U0 eeb4f9dc..0dfb037c -- server/src/main` with the JaCoCo XML `<line>` entries (executable lines only, covered when `ci > 0`): 13/16 lines. `MailHeaderSyncService.kt` 13/13; `LinkService.kt` 0/1 (line 114); `MailHeaderStore.kt` 0/2 (lines 45, 175). The uncovered lines are exercised only by the Postgres tests, which could not run. Changed-branch coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-sync-perf`; base `eeb4f9dc0cef3ec4b9b62cc62e2a708b9fe58bbb`; HEAD `0dfb037cde206c3f5a819c41c932d2e30b31748b`; task commit `0dfb037c` (given by the parent, confirmed against the task's acceptance criteria and `touches`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 0dfb037c, no uncommitted production changes. Uncommitted test files (sha256): `LinkServiceTests.kt` 26396cb030d560b02b640b47954651dd959b5f53927a94dec39b8536c847a2c7; `MailHeaderStoreTests.kt` e79c3cafe1b85969e4903567b19b91ac667c033564c13d0b0d1a0fd2da5a9af1; `MailHeaderSyncServiceTests.kt` a9fd19bb0679e1622122cb5257a19a6b14d56923ac6df64255e97b24ae150ba8.
- Exact included files; renamed/deleted/excluded files and reasons: `server/src/main/kotlin/ch/sonensei/cockpit/core/items/domain/LinkService.kt`, `.../core/items/infra/LinkRepository.kt`, `.../mail/sync/MailHeaderStore.kt`, `.../mail/sync/MailHeaderSyncService.kt`, `.../mail/sync/MailSyncRepository.kt`. No renames or deletions. `LinkRepository.kt` and `MailSyncRepository.kt` are Spring Data interfaces with no executable bytecode, so JaCoCo reports N/A for them. Their JPQL is tested by the Postgres tests, which were not run.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (default toolVersion, not overridden in `build.gradle.kts`), OpenJDK 21.0.5; `./gradlew test --tests '*MailHeaderSyncServiceTests*' jacocoTestReport` in `server/`; selection: `MailHeaderSyncServiceTests` only. The full `./gradlew test jacocoTestReport` from ARCHITECTURE.md cannot produce a report here: the test task fails on Docker.
- Run start/completion time and exit status: 2026-10-25T22:30:18Z to 2026-10-25T22:30:26Z, BUILD SUCCESSFUL (exit 0), 39 tests and 0 failures.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 8cdc944019009d779cfa5ec721660b5fd8bf7d73a83151f50dbf76bd5db3889e; `server/build/jacoco/test.exec`.
- Evidence outputs are fresh and inputs stayed unchanged: the `:test` and `:jacocoTestReport` tasks executed (not UP-TO-DATE), and both files were written at 22:30:24Z and 22:30:26Z, inside the run window. `build/test-results/test` held only that one class's result. The test files did not change after the run.
- Final run after latest test additions and production fixes: yes. No production fixes were made.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt` | 1258/1269 (99.1%) | 98/100 (98.0%) | 33/36 (91.7%) | 165/165 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt` (unit-only run, not representative) | 80/798 (10.0%) | 0/52 (0.0%) | 7/27 (25.9%) | 7/134 (5.2%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/items/domain/LinkService.kt` (unit-only run, not representative) | 0/520 (0.0%) | 0/44 (0.0%) | 0/20 (0.0%) | 0/72 (0.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/items/infra/LinkRepository.kt` | N/A | N/A | N/A | N/A |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailSyncRepository.kt` | N/A | N/A | N/A | N/A |

No aggregate reported: the Postgres-backed files are effectively unmeasured, so a sum would understate them.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailHeaderSyncService.kt:235` (1 of 4 branches of `candidates.size >= limit \|\| candidates.size == missing.size`) | Second operand true while the first is false | Unreachable: `newestMissing` is only called when `missing.size > limit`, so `candidates.size == missing.size` implies `>= limit`. Justified gap. |
| `MailHeaderSyncService.kt:38,44,48` | Generated data-class members of result types | Not task behavior. Justified gap. |
| `MailHeaderSyncService.kt:310` (`folder.uidNext ?: 1`) | Incremental sync, outside this task | Outside the task's changed lines. |
| `LinkService.kt:114` (`hasLinks`), `LinkRepository.existsTouching` | AC1: exists check in both directions | Tests added in `LinkServiceTests`; not run (Docker). |
| `MailHeaderStore.kt:45` (`oldestReceivedAt`), `MailSyncRepository.oldestReceivedAt` | AC2 anchor: oldest located arrival per folder | Test added in `MailHeaderStoreTests`; not run (Docker). |
| `MailHeaderStore.kt:175` (`isLinked`) | AC1: store uses `hasLinks` | Tests added in `MailHeaderStoreTests` (target-only link, other item type); not run (Docker). |

Follow-up: on a machine with Docker, run `cd server && ./gradlew test --tests '*LinkServiceTests' --tests '*MailHeaderStoreTests*' && ./gradlew integrationTest --tests '*HeaderSyncGreenMailTests*' && ./gradlew test jacocoTestReport` to verify the Postgres tests, the `external` GreenMail tests and full coverage.

## Unable to test

- `LinkServiceTests`, `MailHeaderStoreTests` (added tests and existing ones): need Testcontainers Postgres, and the Docker daemon is not running and cannot be started here. Follow-up required: run them on a machine with Docker before `done`.
- `external` GreenMail sync tests (`HeaderSyncGreenMailTests`, AC4): need Postgres through Testcontainers. Not run, for the same reason. Follow-up required.
- Real IMAP `SEARCH SINCE` behaviour (server-local day semantics) is only modelled by the fake in unit tests. The GreenMail tests would cover it end to end. Follow-up: the same run as above.

## Summary

Tests added. The unit-testable behaviour (loadOlder narrowing) passes, and no production defects were found. Meaningful task behaviour stays unverified, and follow-up is needed: the new and existing Postgres-backed tests for `hasLinks`, `isLinked` and `oldestReceivedAt`, and the `external` GreenMail sync tests, could not run because Docker is not available.
