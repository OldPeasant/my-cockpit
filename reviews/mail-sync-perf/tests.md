# Test Report

Date: 2026-10-25

Task: mail-sync-perf - Cheaper link checks and loadOlder candidate narrowing in mail sync

Scope: second pass on the final code. `git diff 0f66c50cd5799eb033006c685e971562070fcf01..12d84719ae2eb552575cc0630e471d8831bbc7cf` (base = `git merge-base HEAD origin/main`; task commits b6beff59, ca4f9b1c, cd0774df, 12d84719 from `git log origin/main..HEAD`; branch `main`), plus the uncommitted test addition below. No uncommitted production changes.

Requirements:
- `tasks/mail-sync-perf.md` acceptance criteria
- `reviews/mail-sync-perf/review.md` (CR-1..CR-3) and `reviews/mail-sync-perf/fixes.md` (final behaviour: window grows back from now, `oldestReceivedAt` removed, `hasLinks` uses derived `existsBySourceTypeAndSourceId` / `existsByTargetTypeAndTargetId`)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent instructions: Docker is not running; run `./gradlew test --tests '*MailHeaderSyncServiceTests*' ktlintCheck compileTestKotlin` and report Docker-dependent tests as not run

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt` (this pass, uncommitted)

Task test files already committed (checked against the final behaviour, still matching):
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStoreTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/items/LinkServiceTests.kt`

## Tests added or updated

Count: 1 added in this pass

- Added: `paging with loadOlder loads every missing message once, newest arrival first, with narrowed windows`. Three consecutive pages (limit 3) over a folder where UID order is not arrival order and one message has no INTERNALDATE. Page 1 needs windows 7 and 14 days and reads only the dates of `[1, 3, 5]`; page 2 grows to 224 days and reads only `[2, 4, 6]`; page 3 takes the undated leftover without any window or date read, `hasMore = false`. Every missing message is loaded exactly once, newest arrival first.

Review of the existing task tests against the final code:
- No remaining references to `oldestReceivedAt` or `existsTouching` in `server/src` (grep). The `oldestReceivedAt` store test and the `setUp` stub were removed by the fixer.
- The loadOlder tests in `MailHeaderSyncServiceTests` assert windows anchored at `now` (7, 14, 28, ... days), including the CR-1 regression test `loadOlder keeps the candidates narrow when one far older message is cached`.
- `LinkServiceTests` `hasLinks ...` tests check both directions and the item type, which are exactly the two derived exists queries. `MailHeaderStoreTests` checks target-only links and same-id-other-type rows through `isLinked`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `LinkService.hasLinks` backed by exists queries, both directions, type-sensitive | `hasLinks is true for the source and the target of a link and false for unlinked items`, `hasLinks counts sync links and is false again once the links are gone` (`LinkServiceTests`) | `./gradlew test --tests '*LinkServiceTests'` | Not run: needs Testcontainers Postgres, Docker daemon not running |
| AC1: `MailHeaderStore.isLinked` uses `hasLinks` | `a message that is only the target of a link counts as linked and is detached, not deleted`, `a message linked under another item type with the same id is not linked`, plus existing linked-row tests (`MailHeaderStoreTests`) | `./gradlew test --tests '*MailHeaderStoreTests*'` | Not run: Docker not running |
| AC2: loadOlder narrows candidates, window from now, not widened by an old cached message | `loadOlder keeps the candidates narrow when one far older message is cached`, `loadOlder of a folder without cached messages starts the window a week before now`, `loadOlder doubles the window until it holds at least the limit of missing messages`, `loadOlder takes every missing message as candidate once the window reaches the epoch` | `./gradlew test --tests '*MailHeaderSyncServiceTests*' ktlintCheck compileTestKotlin` | Pass |
| AC2: still picks by arrival date (gaps, UID order != arrival order, undated last, across pages) | `loadOlder still picks missing messages that arrived after the oldest cached one first`, `loadOlder fetches the most recently arrived messages missing from the cache, whatever their UID`, `loadOlder ranks messages without a date last and ties by UID`, `paging with loadOlder loads every missing message once, newest arrival first, with narrowed windows` | same | Pass |
| AC2: no window and no date reads when missing messages fit the limit | `loadOlder takes every missing message without reading dates when they fit into the limit` | same | Pass |
| AC3: class comment (CR-8) | none | n/a | Not testable (documentation). Checked in the diff: the Message-ID lookup of linked rows is under the full sync bullet |
| AC4: unit tests pass | `MailHeaderSyncServiceTests` (40 tests) | same | Pass (40/40). The Postgres-backed unit tests of the server suite were not run (Docker) |
| AC4: `external` GreenMail sync tests pass | `HeaderSyncGreenMailTests` | `./gradlew integrationTest --tests '*HeaderSyncGreenMailTests*'` | Not run: extends `PostgresTestBase`, Docker not running |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*MailHeaderSyncServiceTests*' ktlintCheck compileTestKotlin`

Result:

Pass (BUILD SUCCESSFUL; `TEST-ch.sonensei.cockpit.mail.sync.MailHeaderSyncServiceTests.xml`: tests=40, failures=0, errors=0, skipped=0; ktlintCheck and compileTestKotlin pass, so the Postgres test files also compile and lint clean)

## Full test execution

Command:

`cd server && ./gradlew test` / `./gradlew integrationTest`

Result:

Not run in this pass: Docker daemon is not running, so every `PostgresTestBase` class (including `LinkServiceTests`, `MailHeaderStoreTests`, `HeaderSyncGreenMailTests`) cannot start. The fixer's run of `./gradlew check` on the same code failed only with Docker/Testcontainers initialisation errors (see `fixes.md`).

## .taskcheck

Result:

Not run: `scripts/check.sh` includes the server `./gradlew check`, which needs Docker here. The parent worker runs it.

## Bugs found

None. No production defects found in the final code.

## Coverage

Status: partial. `MailHeaderSyncService.kt` is measured meaningfully. `LinkService.kt` and `MailHeaderStore.kt` are only exercised by Postgres tests, which cannot run without Docker; their numbers below come from a unit-only run and are not representative.

Metric: whole-file coverage of task-changed production files, from a run selecting only `MailHeaderSyncServiceTests`.
Changed-line coverage: added/modified lines of `git diff -U0 0f66c50c..12d84719 -- server/src/main` intersected with JaCoCo XML executable `line` elements (covered when `ci > 0`): 13/15. `MailHeaderSyncService.kt` 13/13 (lines 153-155, 231-239, 344); `LinkService.kt` 0/1 (line 115, `hasLinks` body); `MailHeaderStore.kt` 0/1 (line 171, `isLinked`). Both uncovered lines are exercised only by the Postgres tests that could not run. Changed-branch coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-sync-perf`; base `0f66c50cd5799eb033006c685e971562070fcf01` (`git merge-base HEAD origin/main`); HEAD `12d84719ae2eb552575cc0630e471d8831bbc7cf`; task commits from `git log origin/main..HEAD` (given by the parent, consistent with the task's `touches`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 12d84719; only uncommitted change is `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt` sha256 `1f4cc0763318e85f0f84238a505ded63b18b114f32b176cd51f490c89e7d25c7` (same before and after the run).
- Exact included files; renamed/deleted/excluded files and reasons: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt`, `.../mail/sync/MailHeaderStore.kt`, `.../core/items/domain/LinkService.kt`, `.../core/items/infra/LinkRepository.kt`. `LinkRepository.kt` is a Spring Data interface with no bytecode in the report (N/A). `MailSyncRepository.kt` is no longer in the net diff (the `oldestReceivedAt` addition was reverted by the fixer). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (default toolVersion); `./gradlew test --tests '*MailHeaderSyncServiceTests*' jacocoTestReport --rerun-tasks` in `server/`; selection: `MailHeaderSyncServiceTests` only (the full `./gradlew test jacocoTestReport` fails here on Docker).
- Run start/completion time and exit status: 2026-10-25T22:41:29Z to 2026-10-25T22:41:55Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 `890e511db493bcc7da3718e954eb5b8dc509973f07e0f3f215a6354924a5bbd5`; `server/build/jacoco/test.exec`.
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun-tasks` forced `:test` and `:jacocoTestReport` to execute; the report was written at 22:41:55Z, inside the run window; the test file hash and `git status` were identical before and after.
- Final run after latest test additions and production fixes: yes (after fix commit 12d84719 and this pass's test addition).

Values below use covered/total (percentage); Statements = JaCoCo INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt` | 1249/1260 (99.1%) | 96/98 (98.0%) | 33/36 (91.7%) | 165/165 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt` (unit-only run, not representative) | 80/793 (10.1%) | 0/52 (0.0%) | 7/26 (26.9%) | 7/133 (5.3%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/items/domain/LinkService.kt` (unit-only run, not representative) | 0/532 (0.0%) | 0/48 (0.0%) | 0/20 (0.0%) | 0/72 (0.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/items/infra/LinkRepository.kt` | N/A | N/A | N/A | N/A |

No aggregate reported: `LinkService.kt` and `MailHeaderStore.kt` are effectively unmeasured (Docker), so a sum would misrepresent the task.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailHeaderSyncService.kt:238` (1 of 4 branches of `candidates.size >= limit \|\| candidates.size == missing.size`) | Second operand true while the first is false | Unreachable: `newestMissing` is only called when `missing.size > limit`. Justified gap. |
| `MailHeaderSyncService.kt:38,44,48` | Generated data-class members of result types | Not task behaviour. Justified gap. |
| `MailHeaderSyncService.kt:313` (`folder.uidNext ?: 1`) | Incremental sync, outside this task | Outside the task's changed lines. |
| `LinkService.kt:115` (`hasLinks`, 4 branches), `LinkRepository` derived exists queries | AC1: exists check in both directions | Tests exist in `LinkServiceTests`; not run (Docker). |
| `MailHeaderStore.kt:171` (`isLinked`) | AC1: store uses `hasLinks` | Tests exist in `MailHeaderStoreTests`; not run (Docker). |

Follow-up: on a machine with Docker run `cd server && ./gradlew test --tests '*LinkServiceTests' --tests '*MailHeaderStoreTests*' && ./gradlew integrationTest --tests '*HeaderSyncGreenMailTests*' && ./gradlew test jacocoTestReport`.

## Unable to test

- `LinkServiceTests`, `MailHeaderStoreTests` (task tests and existing ones): need Testcontainers Postgres; Docker daemon not running. Follow-up required before relying on AC1.
- `external` GreenMail sync tests (`HeaderSyncGreenMailTests`, AC4): need Postgres via Testcontainers. Not run. Follow-up required.
- Real IMAP `SEARCH SINCE` day semantics (and the per-message-offset caveat of CR-3) are only modelled by the fake `RemoteFolder` (UTC day); end-to-end coverage depends on the GreenMail run above.

## Summary

Meaningful task behaviour remains untested and requires follow-up. The unit-testable loadOlder narrowing on the final code passes (40/40, one paging test added), lint and test compilation pass, and no production defects were found. The Postgres-backed `hasLinks` / `isLinked` tests and the `external` GreenMail sync tests could not run without Docker.
