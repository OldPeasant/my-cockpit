# Test Report

Date: 2026-10-26

Task: mail-sync-perf - Cheaper link checks and loadOlder candidate narrowing in mail sync

Scope: final code. Production and test diff `git diff 98d1e2753a2b03eb43223d062a0e4ccdcfaef7f4 HEAD -- server/src/main server/src/test` (base given by the parent; task commits af6b0e07c..7f974dee3 merged at 6b431d6a615a46e9d4910ad022320facd6356611). The checkout's HEAD when this pass ran was `a9ce90562f190d7264af3f6d065764b448d181a0` on `main` (merge-base with 6b431d6a6 is the task base 98d1e2753). `git diff 6b431d6a6 HEAD` touches only `tasks.py`, `tasks/*.md` and `tests/test_push_race.py`, so the `server/` tree is identical at both revisions and this report applies to revision 6b431d6a6. Uncommitted inputs: the two test files listed below (this pass), plus `reviews/mail-sync-perf/review.md`, which another helper was changing (not by me, not a test input).

Requirements:
- `tasks/mail-sync-perf.md` acceptance criteria and its recorded ASSUMPTION (SINCE windows from now, doubling, then bisection to at most about 2x limit)
- `reviews/mail-sync-perf/fixes.md` (round 1: window from now, derived exists queries; round 2: bisection)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent instructions: no Gradle in this sub-agent environment; the parent runs any test classes I name

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt` (this pass, uncommitted)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/HeaderSyncGreenMailTests.kt` (this pass, uncommitted)

Task test files already committed, rechecked against the final code (still matching it):
- `server/src/test/kotlin/ch/sonensei/cockpit/core/items/LinkServiceTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStoreTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt`

## Tests added or updated

Count: 2 added

- `MailHeaderSyncServiceTests`: `loadOlder stops bisecting at a day when more than twice the limit arrived on the same day`. Gap closed: the bisection's second stop condition (`span - short > 1 day`) was not exercised. Every existing bisection test stopped because the candidates fell to `2 * limit` or fewer. In the new test 10 missing messages share one arrival time 2 days ago, 1 more is a month old, and limit = 2. The test asserts the exact `uidsSince` sequence (7 d, 3.5 d, 1.75 d, 0.875 d). It also asserts that bisection then stops with all 10 candidates, that the month-old date is never read, and that equal dates are tie-broken by the higher UID (`[9, 10]`). The test has a JUnit `@Timeout` of 10 s (separate thread), which follows the pattern in `InlineStyleSanitizerTests`. If the stop condition breaks and bisection never terminates, the test fails instead of hanging the suite.
- `HeaderSyncGreenMailTests`: `paging older mail with narrowed SINCE windows on the server loads the newest arrival first`. Gap closed: the existing GreenMail loadOlder tests reach `newestMissing` only through the doubling. Their first window holding the limit never holds more than `2 * limit`, so the bisection never ran against a real IMAP `SEARCH SINCE`. The new test puts 1 recent (cached) message and 5 uncached ones (400, 403, 406, 409, 900 days old, UIDs in shuffled order) into `Archive`. The 448-day window holds 4 of the 5 uncached messages, which is more than `2 * limit` = 2, so pages 1 and 2 bisect on the server. Then it pages with limit 1 and asserts that each page loads exactly one message, newest arrival first (400, 403, 406, 409, 900), that `hasMore` is true until the last page, and that a final call loads 0.

Review of existing tests against the final code (no change needed):
- `LinkService.hasLinks`: `LinkServiceTests` checks the source direction, the target direction, an unknown id, the type being part of the reference (same id under another type is false), and that SYNC links count and disappear after `sync(note, emptyList())`. Together these exercise both derived exists queries and the `||` short-circuit.
- `MailHeaderStore.isLinked`: `MailHeaderStoreTests` checks a target-only link (detached, kept by `pruneDetached`) and a link row under another item type with the same id (not linked, deleted). Pre-existing linked-row tests cover source-side links.
- `loadOlder` / `newestMissing`: window from now (one old cached message keeps it narrow), doubling (7/14/28/56 d), the epoch exit (all missing become candidates), the "window holds all missing" exit, bisection on a 10-year archive (100..200 dates read, at most 25 searches), both bisection branches (middle with fewer than limit -> `short`; with enough -> `span`), arrival-date pick order, undated messages, and multi-page paging.

## Requirement coverage

Results of the commands the parent ran on HEAD 6b431d6a6 before this pass are marked "(parent, pre-pass)". The two tests added in this pass have not run yet.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `LinkService.hasLinks` backed by exists queries, both directions, type-sensitive | `hasLinks is true for the source and the target of a link and false for unlinked items`, `hasLinks counts sync links and is false again once the links are gone` | `server/gradlew -p server test --tests '*LinkServiceTests*'` | Pass (parent, pre-pass): 21/21 |
| AC1: `MailHeaderStore.isLinked` uses `hasLinks` | `a message that is only the target of a link counts as linked and is detached, not deleted`, `a message linked under another item type with the same id is not linked`, plus existing linked-row tests | `server/gradlew -p server test --tests '*MailHeaderStoreTests*'` | Pass (parent, pre-pass): 24/24 |
| AC2: loadOlder narrows candidates (window from now, doubling, bisection, epoch / all-missing exits) | `loadOlder keeps the candidates narrow when one far older message is cached`, `loadOlder doubles the window until it holds at least the limit of missing messages`, `loadOlder reads only about the limit of dates when the cache is complete far back`, `loadOlder takes every missing message as candidate once the window reaches the epoch`, `loadOlder of a folder without cached messages starts the window a week before now` | `server/gradlew -p server test --tests '*MailHeaderSyncServiceTests*'` | Pass (parent, pre-pass): 41/41 |
| AC2: bisection stops at a one-day bracket when one day holds more than 2x limit | `loadOlder stops bisecting at a day when more than twice the limit arrived on the same day` (new) | `server/gradlew -p server test --tests '*MailHeaderSyncServiceTests*'` | Not run: added in this pass, parent must run it |
| AC2: still picks by arrival date | `loadOlder fetches the most recently arrived messages missing from the cache, whatever their UID`, `loadOlder still picks missing messages that arrived after the oldest cached one first`, `paging with loadOlder loads every missing message once, newest arrival first, with narrowed windows` | same | Pass (parent, pre-pass) |
| AC2: real IMAP SINCE path incl. bisection, arrival order across pages | `older mail is loaded on demand`, `older mail is picked by arrival date, not by UID`, `paging older mail with narrowed SINCE windows on the server loads the newest arrival first` (new) | `server/gradlew -p server integrationTest --tests '*HeaderSyncGreenMailTests*'` | Existing: Pass (parent, pre-pass, 14/14). New test: Not run, parent must run it |
| AC3: class comment (CR-8) | none | n/a | Not testable (documentation). Read at HEAD: the Message-ID lookup of linked rows is in the Full sync bullet |
| AC4: unit tests pass | server unit suite | `server/gradlew -p server check` | Pass except 1 unrelated failure (parent, pre-pass): 4575 tests, 1 failed: `IcsFeedClientTests` "a feed of exactly the maximum size..." (calendar, IOException under load); rerun alone 44/44 |
| AC4: `external` GreenMail sync tests pass | `HeaderSyncGreenMailTests`, `HeaderSyncDovecotTests`, `MailIdleGreenMailTests`, `MailIdlePushGreenMailTests` | `server/gradlew -p server integrationTest --tests '*mail.sync*'` | Pass (parent, pre-pass): 14/14, 2/2, 4/4, 1/1 |

## Focused test execution

Command:

`server/gradlew -p server test --tests '*MailHeaderSyncServiceTests*'` and `server/gradlew -p server integrationTest --tests '*HeaderSyncGreenMailTests*'`

Result:

Not run in this pass. This sub-agent cannot run Gradle, and the parent runs these commands. Expected after this pass: MailHeaderSyncServiceTests 42/42 and HeaderSyncGreenMailTests 15/15. The pre-pass results on the same production code were 41/41 and 14/14 (parent).

## Full test execution

Command:

`server/gradlew -p server check`

Result:

Run by the parent before this pass. Lint passed. 4575 tests ran with 1 unrelated failure (`IcsFeedClientTests` under load), which passed when rerun alone (44/44). Not rerun after this pass.

## .taskcheck

Result:

Not run by me (no Gradle here). The parent runs `sh scripts/check.sh`.

## Bugs found

None. I traced the final `newestMissing` by hand:
- Termination: the doubling ends at the epoch, or when the window holds all missing messages or the limit. The bisection's bracket halves on every step and stops at one day.
- Correctness under the single-timezone SINCE assumption: every window starts from now, so it contains every missing message newer than its cut.

I found no defect.

## Coverage

Status: unavailable. The JaCoCo command in `docs/ARCHITECTURE.md` (`cd server && ./gradlew test jacocoTestReport`) cannot run in this sub-agent environment because Gradle is not available to me. The parent did not run a JaCoCo report. The report that exists under `server/build/reports/jacoco/test/jacocoTestReport.xml` (written 2026-10-25 23:26 +0100) is from an earlier, unidentified run before this pass and was not used.

Metric: whole-file coverage of task-changed production files. Not measured.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-sync-perf`; base `98d1e2753a2b03eb43223d062a0e4ccdcfaef7f4` and task commits af6b0e07c..7f974dee3, both given by the parent; reviewed revision 6b431d6a615a46e9d4910ad022320facd6356611 (the checkout HEAD was a9ce90562f190d7264af3f6d065764b448d181a0, with an identical `server/` tree).
- Source/test snapshot identifier (including uncommitted inputs): sha256 `MailHeaderSyncService.kt` 632206a3162ed5dd76fa7a287dfd2cb6324f27bf813ba4462a41fb5dbe71f3cd, `LinkService.kt` a0141f4a060d226b9e95f6b5f3c5695c901da1c92c862cb4d794ac6065133871, `LinkRepository.kt` 5991806f1aae50c165b723c221675e3da2e88e42a3dc2412cb55287c946825f8, `MailHeaderStore.kt` 9b622a88b17ccbfc6147279c6e3c59193d395869ae99dc6d4cacee4797b7b35f; uncommitted tests `MailHeaderSyncServiceTests.kt` 9c946b7820ad267bb36b218828527e11bbdad967b3ebe27fea2fb96bb4f89ea0, `HeaderSyncGreenMailTests.kt` c09c7b445f61e0164163146dc943b26b01c100b6222c5a61737ffa58aaa0fab7.
- Exact included files; renamed/deleted/excluded files and reasons: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt`, `.../mail/sync/MailHeaderStore.kt`, `.../core/items/domain/LinkService.kt`, `.../core/items/infra/LinkRepository.kt` (Spring Data interface, no own bytecode: N/A). No renames or deletions in the net diff.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin); `cd server && ./gradlew test integrationTest jacocoTestReport` would be needed; not run.
- Run start/completion time and exit status: no run.
- Generated report paths and report hashes/run identifier: none for this revision.
- Evidence outputs are fresh and inputs stayed unchanged: not applicable (no measurement).
- Final run after latest test additions and production fixes: no, because Gradle is unavailable to this sub-agent.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt` | not measured | not measured | not measured | not measured |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt` | not measured | not measured | not measured | not measured |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/items/domain/LinkService.kt` | not measured | not measured | not measured | not measured |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/items/infra/LinkRepository.kt` | N/A | N/A | N/A | N/A |

No aggregate: nothing was measured.

In place of measurement, I traced each branch of the task's changed code to tests:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailHeaderSyncService.kt` `newestMissing`: epoch exit | Window cannot grow past 1970 | `loadOlder takes every missing message as candidate once the window reaches the epoch` |
| `newestMissing`: all-missing exit | Window holds everything | `loadOlder fetches the most recently arrived messages missing from the cache, whatever their UID` (fake answers all UIDs) |
| `newestMissing`: doubling | Too few in the window | `loadOlder doubles the window ...`, `loadOlder still picks ... after the oldest cached one first` |
| `newestMissing`: bisection, `inMiddle.size >= limit` true and false | Narrowing to about 2x limit | `loadOlder reads only about the limit of dates when the cache is complete far back`, `loadOlder keeps the candidates narrow ...`, `loadOlder of a folder without cached messages ...` |
| `newestMissing`: bisection stop at a one-day bracket | Termination when one day holds more than 2x limit | Added `loadOlder stops bisecting at a day when more than twice the limit arrived on the same day` |
| `newestMissing` against real IMAP `SEARCH SINCE` (bisection) | Day semantics of the real server | Added GreenMail `paging older mail with narrowed SINCE windows on the server loads the newest arrival first` |
| `LinkService.hasLinks` (source true / target true / both false) | AC1 | `LinkServiceTests` hasLinks tests |
| `MailHeaderStore.isLinked` | AC1 | `MailHeaderStoreTests` target-only and other-type tests |

Follow-up: to get numbers, the parent can run `cd server && ./gradlew test jacocoTestReport` with Docker available and read the four files above from `server/build/reports/jacoco/test/jacocoTestReport.xml`. Coverage is informational only (ARCHITECTURE.md: not a gate).

## Unable to test

- Servers that take each message's SINCE day in its own offset (the CR-3 caveat, a known and documented limitation): GreenMail and the fake both use one timezone. No follow-up is needed beyond the recorded assumption.
- Coverage measurement: Gradle cannot run in this sub-agent environment (see Coverage).

## Summary

Tests added (2). No production defects found. Two real gaps are closed: the bisection's one-day stop condition (unit test) and bisection against a real IMAP server (GreenMail test). The new tests have not run yet. The parent must run `server/gradlew -p server test --tests '*MailHeaderSyncServiceTests*'` and `server/gradlew -p server integrationTest --tests '*HeaderSyncGreenMailTests*'` before relying on them. Task-scoped coverage was not measured because Gradle is unavailable to this sub-agent.
