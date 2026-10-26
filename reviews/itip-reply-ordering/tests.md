# Test Report

Date: 2026-10-26

Task: itip-reply-ordering - Ignore stale iTIP REPLYs across messages (per-attendee answered-at)

Scope: base 874bbd4d8a02391a0bd11b1c7b8037bb5fb8f304 .. HEAD aeb402ed32ada121189207305e392b9eb8adf589
(branch main, clean working tree). Task commits supplied by the parent: 98271343, 42b09d4f, 42fa26a9,
7f1e1823, 3b43ec83 (review only), aeb402ed. Production changes (`git diff 874bbd4d..HEAD -- server/src/main`):
`calendar/itip/ItipReplyProcessor.kt`, `calendar/itip/ItipReplyStamp.kt` (new), `calendar/itip/ItipMessages.kt`
(ItipReply.ORDER), `calendar/backup/CalendarBackupContributor.kt`, migration
`db/migration/calendar/V202610261600__calendar_itip_reply.sql` (new).

Requirements:
- tasks/itip-reply-ordering.md: goal, both acceptance criteria, ASSUMPTION notes (separate `calendar_itip_reply`
  table; strictly older is ignored, equal is applied; missing SEQUENCE = 0, missing DTSTAMP oldest; series vs
  occurrence ordering per CR-1/CR-2; only LOCAL stamps go to `data/calendar_itip_replies.json`)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md; reviews/itip-reply-ordering/review.md and fixes.md

## Test files changed

- None in this run. The test committed by the previous test-writer run in aeb402ed
  (`ItipReplyStampPersistenceTests` "each attendee has a stamp of its own, so an older REPLY of another attendee
  is still taken") was run first and passes (0.105 s); no fix was needed.

## Tests added or updated

Count: 0 (existing task tests already cover both criteria; coverage of the changed lines is complete, see below)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: a REPLY older than the last applied one for the same attendee/UID is ignored (lower SEQUENCE, same SEQUENCE + older DTSTAMP) | `ItipReplyProcessorTests`: "a REPLY older than the one taken from an earlier message is ignored", "a newer REPLY from a later message is applied and becomes the last one taken", "a REPLY that changes no status still counts as the last one taken"; `ItipReplyStampPersistenceTests`: "a REPLY equal to the last one taken is applied again", "the stamp of an address in other letter case is the same stamp..." | `cd server && ./gradlew test --rerun --tests '*Itip*' --tests '*CalendarBackup*' jacocoTestReport` | Pass |
| AC1: per attendee | `ItipReplyStampPersistenceTests` "each attendee has a stamp of its own, so an older REPLY of another attendee is still taken" | same | Pass |
| AC1: per RECURRENCE-ID (timed and all-day), series vs occurrence ordering (CR-1/CR-2) | `ItipReplyProcessorTests`: "REPLYs are ordered per occurrence and per event", "an occurrence REPLY older than the last series REPLY is ignored...", "a series REPLY leaves an override alone whose own answer is newer..."; `ItipReplyStampPersistenceTests` "occurrence stamps of timed and all-day series are found again in the database..." | same | Pass |
| AC1: missing SEQUENCE / DTSTAMP | `ItipMessagesTests` "answers are ordered by SEQUENCE (0 when missing), then DTSTAMP (missing first)"; `ItipReplyProcessorTests` "a REPLY without SEQUENCE counts as SEQUENCE 0 across messages"; `ItipReplyStampPersistenceTests` "a REPLY without SEQUENCE counts as SEQUENCE 0, and one without DTSTAMP as older..." | same | Pass |
| AC1: no stamp for an answer not taken; optimistic-lock retry stores stamp once; failed retries store none; stamps cascade with event/source | `ItipReplyStampPersistenceTests` (4 tests of these names) | same | Pass |
| AC1: end to end through real mail (SMTP/IMAP header sync) | `ItipGreenMailTests` "a REPLY older than one taken from an earlier message is ignored, also after the event was edited" | `cd server && ./gradlew integrationTest --rerun --tests '*ItipGreenMail*' ktlintCheck` | Pass (34/34 in class) |
| AC2: stamp not lost on event edits via the API | `ItipGreenMailTests` "older series and occurrence REPLYs stay ignored after the series and its override are edited via the API" (MockMvc PATCH of series and override); `ItipReplyStampPersistenceTests` "the stamp survives an edit of the event's attendees that keeps the attendee" (repository level) | integrationTest command above; focused test command | Pass |
| AC2: stamp survives backup/restore (LOCAL exported and restored unchanged, external not exported, no file when empty, still effective after restore) | `CalendarBackupContributorTests`: "iTIP REPLY stamps of local events are exported and restored unchanged, those of external events are not exported", "without iTIP REPLY stamps no stamp file is written", "after a restore an iTIP REPLY older than the restored stamp is still ignored, a newer one is taken" | focused test command | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests '*Itip*' --tests '*CalendarBackup*' jacocoTestReport` (Postgres
Testcontainer; Docker available)

Result:

Pass, exit 0, BUILD SUCCESSFUL in 2m 18s. 85 tests, 0 failures, 0 errors, 0 skipped:
ItipReplyProcessorTests 18, ItipMessagesTests 26, ItipReplyStampPersistenceTests 10, ItipApiTests 7,
CalendarBackupContributorTests 24.

Command:

`cd server && ./gradlew integrationTest --rerun --tests '*ItipGreenMail*' ktlintCheck`

Result:

Pass, exit 0, BUILD SUCCESSFUL in 1m 33s. ItipGreenMailTests 34 tests, 0 failures, 0 errors, 0 skipped.
ktlintCheck passed (all ktlint tasks UP-TO-DATE, i.e. unchanged inputs since a successful run).

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Pass, exit 0, BUILD SUCCESSFUL in 23m 14s (ktlint + `test`, which excludes tag `external`), finished about
19:48 +0100 on HEAD aeb402ed with a clean tree. 384 test classes, 5129 tests, 0 failures, 0 errors, 0 skipped.
The ~23 min run time matches the task note; this task's tests take about 2 min of it. This run rewrote
`server/build/jacoco/test.exec` but not the XML report; the coverage below is taken from the focused run.

## .taskcheck

Result:

Not run by the test-writer: `.taskcheck` (`sh scripts/check.sh`) takes about 23 min on this machine (task notes);
the server part of it is `./gradlew check`, reported above. The parent worker runs `.taskcheck` via `tasks.py done`.

## Bugs found

None.

## Coverage

Status: verified (focused selection; see test selection below)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: added lines from `git diff -U0 874bbd4d..HEAD -- server/src/main/kotlin`
intersected with JaCoCo `<line>` entries (lines with instructions) of the same report; branch counters (`mb`/`cb`)
summed over those lines. Result: 73/73 executable changed lines executed, 42/42 branches on changed lines covered.
Per file: CalendarBackupContributor.kt 34/34 lines, 12/12 branches; ItipReplyProcessor.kt 20/20 lines, 26/26
branches; ItipReplyStamp.kt 18/18 lines, 4/4 branches (line 36 partly: 3 of 6 instructions missed); ItipMessages.kt
1/1 line, 0/0 branches (N/A).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: itip-reply-ordering; base
  874bbd4d8a02391a0bd11b1c7b8037bb5fb8f304 (from the parent); HEAD aeb402ed32ada121189207305e392b9eb8adf589; task
  commits supplied by the parent, file scope from `git diff --name-only 874bbd4d..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD tree dcb3b9876208b78ca147ff941164e5c8f8734f11,
  working tree clean (no uncommitted source or test inputs) before and after the runs.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessor.kt`,
  `.../calendar/itip/ItipReplyStamp.kt`, `.../calendar/itip/ItipMessages.kt`,
  `.../calendar/backup/CalendarBackupContributor.kt`. Excluded: `V202610261600__calendar_itip_reply.sql` (SQL
  migration, not instrumentable by JaCoCo; exercised by every Postgres test above). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle
  9.8.1 default tool version; OpenJDK 25.0.4.1); `./gradlew test --rerun --tests '*Itip*' --tests '*CalendarBackup*'
  jacocoTestReport` in `server/`; selection = the 5 classes above (85 tests). ItipGreenMailTests (tag `external`,
  task `integrationTest`) are not part of the JaCoCo `test` report.
- Run start/completion time and exit status: 2026-10-26 ~19:19:50 to 19:22:09 +0100, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (written 19:22:09.00, sha256 aa583fddee5f9434329c5baf56b4dce9b626f6fd0f4812b25ecc3f08dd47f320) from
  `server/build/jacoco/test.exec` (written 19:22:04.39).
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun` forced the test task; exec and XML timestamps
  are from this run; working tree clean before and after.
- Final run after latest test additions and production fixes: yes (HEAD aeb402ed includes the CR-1/CR-2 fixes and the
  last test; no changes since).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `calendar/itip/ItipReplyProcessor.kt` | 734/814 (90.2%) | 74/92 (80.4%) | 18/18 (100%) | 96/108 (88.9%) |
| `calendar/itip/ItipReplyStamp.kt` | 93/96 (96.9%) | 4/4 (100%) | 12/13 (92.3%) | 18/18 (100%) |
| `calendar/itip/ItipMessages.kt` | 603/606 (99.5%) | 62/62 (100%) | 25/25 (100%) | 74/76 (97.4%) |
| `calendar/backup/CalendarBackupContributor.kt` | 2442/2465 (99.1%) | 114/114 (100%) | 105/111 (94.6%) | 369/370 (99.7%) |
| Aggregate (summed counters, 4 files; migration excluded) | 3872/3981 (97.3%) | 254/272 (93.4%) | 160/167 (95.8%) | 557/572 (97.4%) |

JaCoCo has no separate statement counter; Instructions is reported in its place.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ItipReplyProcessor.kt:77-78, 85-91` | Background executor rejection and `safely()` error logging (pre-existing, not changed by the task) | Not in task scope; exercised end to end by ItipGreenMailTests (integrationTest, outside this JaCoCo report) |
| `ItipReplyProcessor.kt:111-112, 145-146, 151` (partial), `208, 218-219, 225-237` (partial) | Pre-existing parse-failure, lookup, MIME and charset paths of itip-invitations | Not in task scope (unchanged lines); covered by ItipGreenMailTests in part |
| `ItipReplyStamp.kt:36` (3 of 6 instructions) | `id` default argument: the Kotlin synthetic default/no-arg constructor path used by JPA | Non-material: generated code; every stamp creation path in the processor runs this line |
| `ItipMessages.kt:141-142` | DateTimeException on a broken RECURRENCE-ID (pre-existing) | Not in task scope |
| `CalendarBackupContributor.kt:366-379` (1 line + 6 methods) | `EventRow` accessors not hit by the selected tests (pre-existing) | Not in task scope |

No uncovered line or branch lies in the task's added or modified lines.

## Unable to test

- Nothing required by the acceptance criteria is untested.
- `.taskcheck` not run by the test-writer (duration ~23 min); see above.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
