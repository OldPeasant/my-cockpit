# Test Report

Date: 2026-11-08

Task: and-mail-search-nav - Android mail search kept on return, previous/next, back to origin

Scope: base 7fcf993cfcc5ad9dba77130366e0b352403f767a .. HEAD 39e14ab44691121750edec799e6e446f31c967c8 on `main`
(task commits 5139d2ef0 and 39e14ab44, `git diff 7fcf993cf..HEAD -- android`). Task ownership comes from the parent
worker and matches the task's `touches`. Changed production files: `mail/read/MailReadScreen.kt`,
`mail/list/MailScreen.kt`, `ui/CockpitShell.kt`, `res/values/strings_mail.xml`, `res/values-de/strings_mail.xml`.
Test files the parent changed: `mail/list/MailReadNavigationInAppTest.kt`, `ui/MailBackToOriginInAppTest.kt`,
`ui/MailOriginShellTest.kt`. When this report was written the tree held only the test-writer's own uncommitted
changes (listed below), plus `reviews/and-mail-search-nav/` (untracked).

Requirements:
- `tasks/and-mail-search-nav.md` acceptance criteria and its recorded assumptions (previous/next only in the Mail tab;
  after archive/delete/spam show next, else back to the list; row below the toolbar; paging at the last loaded mail;
  entry points and the shell's single close)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- Conventions of the neighbouring tests `MailReadScreenTest.kt`, `MailReadImagesGermanTest.kt`, `MailReadSpamGermanTest.kt`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNavigationTest.kt` (new, sha256 8a1be54d8dddd25666b27b8581d78ab6aa906e89436d7a7af2567ead23d79796)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNavigationScreenTest.kt` (new, sha256 0e70acd5fe1ef27e15263f422907d10fef63455f21a2460fe057cf0e7a9793bb)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadNavigationGermanTest.kt` (new, sha256 342cebd925965f49b9fba22729c9ad70f0d6a9b7d6253365238569b1174fd598)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailReadNavigationInAppTest.kt` (3 tests added, sha256 6af86241a86a02a61220d4e139beb371b3640d42cb8e59bc837df6e0ca3e36ee)

No production code, configuration or existing test was changed, weakened or disabled.

## Tests added or updated

Count: 26 (7 + 14 + 2 new classes' tests, 3 added to the parent's in-app class)

- `MailReadNavigationTest` (plain JUnit, 7): middle mail has both neighbours; first has no previous; last has no next;
  single mail has none; mail not in the list gives no navigation; empty list gives no navigation; `open` passes the id.
- `MailReadNavigationScreenTest` (Robolectric Compose, 14). Content: "2 of 3" with previous/next opening the
  neighbours; previous disabled on the first (click ignored); next disabled on the last (click ignored); single mail
  "1 of 1" with both disabled; both disabled while busy (clicks ignored, position still shown); no row, position or
  buttons without a navigation; TalkBack labels "Previous mail"/"Next mail"; the row lies below the toolbar and above
  the headers. Route (`MailReadRoute` with a real `MailReadViewModel` and a stateful list like the Mail screen's):
  next loads the next mail without closing; delete in the middle reports removed, then opens the next ("2 of 2");
  spam on the first opens the next ("1 of 2", previous disabled); archive of the last mail reports removed and
  closes, without loading the previous mail; deleting the only mail closes; a failed delete keeps the mail, its place
  and shows the notice.
- `MailReadNavigationGermanTest` (Robolectric Compose, `qualifiers = "de"`, 2): position "2 von 3"; TalkBack labels
  "Vorherige Mail"/"Nächste Mail".
- `MailReadNavigationInAppTest` (Hilt in-app, 3 added): moving a mail to spam shows the next one and it leaves the
  list; deleting the last mailbox mail returns to the list (next disabled before); deleting the last search result
  returns to the same search with the rest of the results.

## Requirement coverage

The test-writer could not run Gradle in this environment (sub-agent Gradle runs are blocked), so every result for the
tests added here is "Not run". The parent reported that its own 13 tests pass. The parent must run the command below
and record the actual result.

Command for the parent (C1):
`cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.MailReadNavigationTest' --tests 'ch.sonensei.cockpit.mail.read.MailReadNavigationScreenTest' --tests 'ch.sonensei.cockpit.mail.read.MailReadNavigationGermanTest' --tests 'ch.sonensei.cockpit.mail.list.MailReadNavigationInAppTest' --tests 'ch.sonensei.cockpit.ui.MailBackToOriginInAppTest' --tests 'ch.sonensei.cockpit.ui.MailOriginShellTest'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 search query and results kept on return (toolbar, back gesture, rotation) | parent's `MailReadNavigationInAppTest` (search toolbar back, rotation), `MailSearchRouteInAppTest` (pre-existing); added: deleting the last search result returns to the same search | C1 | Not run by the test-writer: Gradle blocked for sub-agents (the parent reports its tests pass) |
| AC2 previous/next through the origin list, disabled at the ends | `MailReadNavigationTest` (all 7); `MailReadNavigationScreenTest` content tests (middle, first, last, single, busy, no list, labels, placement), route next; parent's in-app mailbox/search stepping and paging | C1 | Not run by the test-writer: Gradle blocked |
| AC2 archive/delete moves to the next one (at the end: back to the list) | `MailReadNavigationScreenTest` route tests (delete middle, spam, last archived closes, only mail closes, failed delete stays); in-app: spam shows next, delete last returns to the list, delete last result returns to the search; parent's delete/archive tests | C1 | Not run by the test-writer: Gradle blocked |
| AC3 back to origin per entry point | parent's `MailBackToOriginInAppTest` (New, global search x3, Dashboard), `MailOriginShellTest` (task links), Mail list and search tests | C1 | Not run by the test-writer; nothing added here (the entry points with own code are already covered) |
| AC4 manual check on emulator/device | none | none | Not testable by automated tests; the parent decides (verify or skip with reason) |
| AC5 strings in English and German | `MailReadNavigationScreenTest` (English position and labels), `MailReadNavigationGermanTest` (German) | C1 | Not run by the test-writer: Gradle blocked |
| AC6 tests cover the new behavior; ktlint, unit tests, lint and `scripts/check.sh` pass | all of the above | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`; `sh scripts/check.sh` | Not run by the test-writer: Gradle blocked |

## Focused test execution

Command:

C1 above (to be run by the parent).

Result:

Not run by the test-writer: the environment blocks Gradle for sub-agents. The tests were written against the
signatures read from the sources (`MailReadNavigation.of`, `MailReadContent(..., navigation = ...)`,
`MailReadRoute(..., navigation = ...)`, `readViewModel` in `MailReadTestSupport.kt`, `MailResult`,
`MailActionOutcome`), but compile and ktlint errors are possible until the parent runs them.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run (Gradle blocked for the test-writer).

## .taskcheck

Result:

Not run (`sh scripts/check.sh` runs Gradle, which is blocked for the test-writer).

## Bugs found

None found by reading the code and writing the tests. No added test was seen to fail, because none could be run.
If a test added here fails when the parent runs it, check first whether the test or the production code is wrong
before changing either.

## Coverage

Status: unavailable. Sub-agents cannot run Gradle here, and the project's Android coverage command is a Gradle task.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-search-nav; base
  7fcf993cfcc5ad9dba77130366e0b352403f767a; HEAD 39e14ab44691121750edec799e6e446f31c967c8; commits 5139d2ef0 and
  39e14ab44, as given by the parent and checked against the task's `touches` and `git diff --stat`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 39e14ab44 plus the four test files with the
  sha256 hashes listed under "Test files changed".
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
| `MailReadScreen.kt` `MailReadNavigation.of` (not-in-list, ends, single) | AC2 ends | Added `MailReadNavigationTest` |
| `MailReadScreen.kt` `NavigationRow` (disabled at ends, busy, no row without a list) | AC2 | Added content tests in `MailReadNavigationScreenTest` |
| `MailReadScreen.kt` `MailReadRoute` removed branch: next exists versus `close()` at the end, and spam | AC2 "archive/delete moves to the next one" | Added route tests (delete, spam, archive at end, only mail) and in-app spam/delete-last tests |
| `MailScreen.kt` `atLoadedEnd` / `loadMore()` | Assumption: paging at the last loaded mail | Covered by the parent's in-app paging test. Not added: opening the last mail with no further page should not call `loadMore` (low risk) |
| `CockpitShell.kt` `close(entry)` for `TaskRoutes.DETAIL` | Single close of shell item screens | The item route is covered by the parent's global-search delete test. Not added for the task detail: it needs a Hilt task detail that closes twice, and no current flow in the task scope does that |

The parent should run the coverage command above and record the numbers here, or ask the test-writer to run again
where Gradle is allowed.

## Unable to test

- Running the tests, lint and coverage myself: Gradle is blocked for sub-agents here. The parent needs to run C1, the
  full Android check and the coverage command.
- AC4 (manual check on an emulator or device): not automatable here. The parent decides whether to verify it or skip
  it with a reason.
- Contact entry point: by the task's assumption, contacts open mail in the phone's mail app, so there is no in-app
  navigation code to test.

## Summary

Tests added, but no validation could be run. The test-writer could not run Gradle, so the 26 new tests, the full
Android check and task-scoped coverage are not run and not measured. The parent must run them before using this
report as evidence. Reading the code found no production defects.
