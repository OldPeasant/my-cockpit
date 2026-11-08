# Test Report

Date: 2026-11-08

Task: and-mail-search-nav - Android mail search kept on return, previous/next, back to origin

Scope: base 7fcf993cfcc5ad9dba77130366e0b352403f767a .. final reviewed revision
a35993cf68dfc6b6e3e4c5c28f11b2440edec083 on `main` (task commits 5139d2ef0, 39e14ab44, a43a49ca9, a35993cf6;
`git diff 7fcf993cf..a35993cf6 -- android`). Task ownership comes from the parent worker and matches the task's
`touches`. Changed production files: `mail/read/MailReadScreen.kt`, `mail/list/MailScreen.kt`, `ui/CockpitShell.kt`,
`res/values/strings_mail.xml`, `res/values-de/strings_mail.xml`. This is the second (final) test-writer pass: since
the first pass the fixer changed `mail/list/MailScreen.kt` for CR-1 (the read view asks for the next page again after a
running refresh; helpers `pageFailed` and `canLoadAfter`) and added two tests to `MailReadNavigationInAppTest`
(`git diff 39e14ab44..a35993cf6`, `reviews/and-mail-search-nav/fixes.md`). The working tree was clean when this
report was written; this pass added no tests.

Requirements:
- `tasks/and-mail-search-nav.md` acceptance criteria and its recorded assumptions (previous/next only in the Mail tab;
  after archive/delete/spam show next, else back to the list; row below the toolbar; paging at the last loaded mail;
  entry points and the shell's single close)
- `reviews/and-mail-search-nav/review.md` (CR-1, CR-2) and `reviews/and-mail-search-nav/fixes.md`
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

All committed by a35993cf6 (sha256 of the file at that revision):

- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNavigationTest.kt` (new, 8a1be54d8dddd25666b27b8581d78ab6aa906e89436d7a7af2567ead23d79796)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNavigationScreenTest.kt` (new, 0e70acd5fe1ef27e15263f422907d10fef63455f21a2460fe057cf0e7a9793bb)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNavigationGermanTest.kt` (new, 342cebd925965f49b9fba22729c9ad70f0d6a9b7d6253365238569b1174fd598)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailReadNavigationInAppTest.kt` (parent, test-writer and fixer tests, 95bdb31e790961df09619229bcbedc9210d80ed93b8d3a01b57dd73437a6194d)
- `android/app/src/test/java/ch/sonensei/cockpit/ui/MailBackToOriginInAppTest.kt` (parent, fe8c775139785bb50aed24d2570bfb478bf7707bbccf5f786f104f2201f919e4)
- `android/app/src/test/java/ch/sonensei/cockpit/ui/MailOriginShellTest.kt` (parent, c2b5d8e28bf409c12491ca48b5025e32223f334f9fe7d8125a6d810c73b08f21)

No production code, configuration or existing test was changed, weakened or disabled by the test-writer.

## Tests added or updated

Count: 0 in this pass (26 in the first pass; the fixer added 2 for CR-1).

Task tests at a35993cf6 (41 in total):
- `MailReadNavigationTest` (plain JUnit, 7): neighbours for middle, first, last and single mail; mail not in the list
  and empty list give no navigation; `open` passes the id.
- `MailReadNavigationScreenTest` (Robolectric Compose, 14): position "2 of 3", previous/next open the neighbours,
  disabled at the ends and while busy (clicks ignored), no row without a list, TalkBack labels, row placement below
  the toolbar; route tests with a real `MailReadViewModel`: next, delete in the middle opens the next, spam on the
  first opens the next, archive of the last closes, deleting the only mail closes, a failed delete stays.
- `MailReadNavigationGermanTest` (2): "2 von 3", "Vorherige Mail"/"Nächste Mail".
- `MailReadNavigationInAppTest` (Hilt in-app, 11): mailbox and search stepping, search back, delete/spam/archive show
  the next, deleting the last mail or last result returns to the list or the same search, rotation, paging at the last
  loaded mail, and the fixer's CR-1 tests: the last loaded mail opened during a refresh fetches the next page once the
  refresh ends; a failed next page from the read view is not retried by itself.
- `MailBackToOriginInAppTest` (6) and `MailOriginShellTest` (1): back to origin from New, global search, Dashboard and
  task links.

Review of the CR-1 change against these tests: `canLoadAfter` combines search mode, last loaded mail, next cursor,
`loading`, `loadingMore` and `pageFailed`. The refresh test drives `loading` true then false with a failed earlier
page load cleared by the refresh's success, and fails without the fix (the "2 of 3" wait times out). The offline test
drives `pageFailed` and checks that exactly one extra request is made (no loop). The first-try-on-open path is kept by
`the last loaded mail fetches the next page so next can go on`. The `pageFailed` extraction in `Messages` keeps the
list's own near-end condition unchanged and is exercised by the list paging tests (the list's page-2 Offline load in
the refresh test). No real gap remains that needs a new test; see the gap table below for the minor branches left out.

## Requirement coverage

Command C1 (run by the parent on a35993cf6, from `android/`):
`./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.*' --tests 'ch.sonensei.cockpit.mail.read.*' --tests 'ch.sonensei.cockpit.ui.*' ktlintCheck lintDebug`
-> 414 tests, 0 failures, 0 errors; ktlint and lint clean.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 search query and results kept on return (toolbar, back gesture, rotation) | `MailReadNavigationInAppTest` search back, rotation, delete last result returns to the same search; `MailSearchRouteInAppTest` (pre-existing) | C1 (parent) | Pass |
| AC2 previous/next through the origin list, disabled at the ends | `MailReadNavigationTest` (7); `MailReadNavigationScreenTest` content tests; in-app mailbox/search stepping; paging at the last loaded mail incl. the CR-1 refresh and offline tests | C1 (parent) | Pass (MailReadNavigationInAppTest 11/11, MailReadNavigationScreenTest 14/14, MailReadNavigationTest 7/7) |
| AC2 archive/delete moves to the next one (at the end: back to the list) | `MailReadNavigationScreenTest` route tests; in-app delete/spam/archive next, delete last returns to list/search | C1 (parent) | Pass |
| AC3 back to origin per entry point | `MailBackToOriginInAppTest` (6), `MailOriginShellTest` (1) | C1 (parent) | Pass (6/6, 1/1) |
| AC4 manual check on emulator/device | none | none | Not testable by automated tests; the parent decides (verify or skip with reason) |
| AC5 strings in English and German | `MailReadNavigationScreenTest` (English), `MailReadNavigationGermanTest` (German) | C1 (parent) | Pass (German 2/2) |
| AC6 tests cover the new behavior; ktlint, unit tests, lint and `scripts/check.sh` pass | all of the above | C1 (parent); full `./gradlew testDebugUnitTest` (parent, before the CR-1 fix); `sh scripts/check.sh` | C1 Pass; full Android unit suite Pass on the pre-fix revision only; `scripts/check.sh` Not run by the test-writer (parent must run it on the final revision) |

## Focused test execution

Command:

C1 above, run by the parent on a35993cf6 (Gradle is blocked for the test-writer).

Result:

Pass (414 tests, 0 failures, 0 errors; ktlint and lint clean), as reported by the parent.

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest` (parent)

Result:

Pass on the revision before the CR-1 fix, as reported by the parent. Not run on a35993cf6; the fix touches only
`MailScreen.kt`, whose test packages (`mail.list`, `mail.read`, `ui`) C1 ran on a35993cf6.

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` runs Gradle, which is blocked for sub-agents). The parent must run
it on the final revision.

## Bugs found

None. No test failed on a35993cf6 per the parent's run, and reading the CR-1 change found no defect.

## Coverage

Status: unavailable. Sub-agents cannot run Gradle here, and the project's Android coverage command is a Gradle task;
the parent did not run it either.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-search-nav; base
  7fcf993cfcc5ad9dba77130366e0b352403f767a; final revision a35993cf68dfc6b6e3e4c5c28f11b2440edec083; commits
  5139d2ef0, 39e14ab44, a43a49ca9, a35993cf6, as given by the parent and checked against the task's `touches` and
  `git diff --stat`.
- Source/test snapshot identifier (including uncommitted inputs): a35993cf6, clean working tree; test file hashes
  listed under "Test files changed".
- Exact included files; renamed/deleted/excluded files and reasons: in scope
  `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt`,
  `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt`,
  `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt`. The two `strings_mail.xml` files are excluded
  because they are resources, not executable code. Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage`,
  `cd android && ./gradlew createDebugUnitTestCoverageReport` (docs/ARCHITECTURE.md "Coverage"). Not run.
- Run start/completion time and exit status: no run.
- Generated report paths and report hashes/run identifier: none. The expected path is
  `android/app/build/reports/coverage/test/debug/report.xml`.
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, nothing was measured.
- Final run after latest test additions and production fixes: no. Coverage was never measured.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt` | not measured | not measured | not measured | not measured |
| `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt` | not measured | not measured | not measured | not measured |
| `android/app/src/main/java/ch/sonensei/cockpit/ui/CockpitShell.kt` | not measured | not measured | not measured | not measured |

No aggregate: none of the three files was measured.

Gaps found by reading the code (no coverage report):

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailScreen.kt` `MailRoute` read-view paging effect (first try on open, retry after a refresh, no retry after a failure) | Assumption: paging at the last loaded mail; CR-1 | Covered by the in-app paging test and the fixer's two CR-1 tests |
| `MailScreen.kt` `canLoadAfter` with `loadingMore` (a list page load, not a refresh, running when the last mail opens) | CR-1, same path as `loading` | Not added: identical handling to the tested refresh case; driving a pending list page load while opening the mail adds little |
| `MailScreen.kt` `atLoadedEnd`/`canLoadAfter` in search mode and at the last mail without a next cursor (no request) | Paging only for the mailbox | Not added: low risk; the search stepping tests verify search is asked once and results stay; no test asserts zero page requests there |
| `CockpitShell.kt` `close(entry)` for `TaskRoutes.DETAIL` | Single close of shell item screens | Item route covered by the global-search delete test; task detail not added (needs a Hilt task detail that closes twice; no flow in scope does that) |

The parent should run the coverage command above if numbers are required for the gate.

## Unable to test

- Running tests, lint, coverage and `scripts/check.sh` myself: Gradle is blocked for sub-agents. Results above are the
  parent's runs.
- AC4 (manual check on an emulator or device): not automatable here; the parent verifies or skips it with a reason.
- Contact entry point: by the task's assumption, contacts open mail in the phone's mail app, so there is no in-app
  navigation code to test.

## Summary

No additional tests were necessary for the reviewed scope; no production defects found. The CR-1 change is covered by the fixer's
two regression tests plus the existing paging test, and no further test was needed in this pass. All task tests pass
on a35993cf6 per the parent's run (414 tests, lint clean). Coverage numbers are not measured because Gradle cannot run
here, and `scripts/check.sh` still has to be run by the parent on the final revision.
