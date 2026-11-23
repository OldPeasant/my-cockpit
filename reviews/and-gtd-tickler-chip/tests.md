# Test Report

Date: 2026-11-23

Task: and-gtd-tickler-chip - Android: Tickler as list choice, 'new today' marker in Next

Scope: task base c4b38a01604c4325040f5e24b58e651b8c797045 to HEAD 06a4d696c0435a1a274233a63b3ca314693bf5e2 (branch main), task commits 0d14ce0d7, fd5a765f3, 2dfad2f5c, 88bde0bbc, 06a4d696c as given by the parent worker; diff `git diff c4b38a016..HEAD -- android SPEC.md`. Uncommitted inputs at measurement: only the test files listed below (no production changes).

Requirements:
- `tasks/and-gtd-tickler-chip.md` acceptance criteria and its recorded assumption (tickler date field only while Tickler is chosen; scheduled date field always visible; an unchanged restored tickler moves nothing)
- `SPEC.md` §5.1/§5.3 (as changed by the task)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDraftListTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailListFieldsTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailContentTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailStringsTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/inbox/InboxScreenTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/next/NextViewModelNewTodayTest.kt` (new)

## Tests added or updated

Count: 21 added (no existing test changed, weakened or disabled)

- TaskDraftListTest (7): `a task with a tickler starts with Tickler chosen whatever its list, one without does not`; `choosing Tickler targets the Inbox, drops the scheduled date and keeps a date already picked`; `Tickler without a date is required, also without a move`; `picking a scheduled date while Tickler is chosen switches to Scheduled and drops the tickler`; `a changed tickler of a task stored on another list moves it to the Inbox, without its list fields`; `a tickled task's list-bound fields are not patched while Tickler is chosen`; `picking the Inbox for a tickled Inbox task clears the tickler with a move`
- TaskDetailListFieldsTest, Compose (5): `the tickler date field is labelled with the tickler name, not Comes back`; `a tickler moved to a new day on a task stored on Next is saved to the Inbox`; `a tickled Waiting task hides the waiting fields and saves unchanged without a move or patch`; `a scheduled date picked while Tickler is chosen switches to Scheduled and drops the tickler`; `choosing Tickler on a Scheduled task drops its scheduled date`
- TaskDetailContentTest, Compose (1): `the tickler property is named Tickler, not Comes back`
- TaskDetailStringsTest (2): `the tickler has one name in the form, the detail view and Inbox processing, in English and German`; `the New today marker and its description exist in English and German`
- InboxScreenTest, Compose (1): `the tickler prompt names the Tickler and explains it, the schedule prompt has no such hint`
- NextViewModelNewTodayTest (5): `tasks released today come first in their own order, the others follow in theirs`; `filters still apply to tasks released today`; `a preset still applies to tasks released today`; `from the next day on they keep their normal place`; `today is the user's local date, not the UTC date`

## Requirement coverage

Command A: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'` (1278 tests, 0 failures, 0 skipped).
Command B: `cd android && ./gradlew createDebugUnitTestCoverageReport` (full unit suite, 5743 tests, 0 failures, 0 skipped).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: SPEC.md §5.1/§5.3 describe the Tickler chip (list = INBOX + ticklerDate) and the "new today" rule | Documentation; read the SPEC.md diff: §5.1 table row "Released from Scheduled", §5.3 "Next Actions view" and "Task edit form" paragraphs match the criterion | Manual review of `git diff c4b38a016..HEAD -- SPEC.md` | Not testable (documentation; content checked by reading) |
| AC2: chips Inbox, Next, Waiting For, Scheduled, Tickler, Someday in this order; "Comes back" field gone | `the lists are offered in workflow order with Tickler before Someday, without a separate tickler field`; `the tickler date field is labelled with the tickler name, not Comes back` | A, B | Pass |
| AC2: Tickler requires a future date, saves `list = INBOX` + `ticklerDate` via the move API | `Tickler needs a day and saves the task to the Inbox with it`; `a tickler sent with a move must be after today`; `Tickler without a date is required, also without a move`; `choosing Tickler on a Scheduled task drops its scheduled date`; `a chosen tickler moves the task to the Inbox, another chip clears it`; `a scheduled task put on the tickler loses its scheduled date`; `a new list and title move the task first, then patch the moved version` (VM) | A, B | Pass |
| AC3: a task with a ticklerDate opens with Tickler chosen and its date shown, whatever its stored list | `a task with a tickler opens with Tickler chosen whatever its list, and saving unchanged moves nothing`; `a tickled Waiting task hides the waiting fields and saves unchanged without a move or patch`; `a task with a tickler starts with Tickler chosen whatever its list, one without does not` (NEXT, SCHEDULED, WAITING, SOMEDAY, INBOX) | A, B | Pass |
| AC3: picking another chip clears the tickler; Scheduled still asks for its date | `choosing another list clears the tickler, Scheduled still asks for its date`; `picking Someday for a tickled Someday task resends the move without the tickler`; `picking the Inbox for a tickled Inbox task clears the tickler with a move`; `a scheduled date picked while Tickler is chosen switches to Scheduled and drops the tickler` (Compose and unit) | A, B | Pass |
| AC3: saving without changes doesn't move or change the task | `a task with a tickler opens with Tickler chosen whatever its list, and saving unchanged moves nothing`; `a tickled Waiting task hides the waiting fields and saves unchanged without a move or patch`; `a tickled task's list-bound fields are not patched while Tickler is chosen`; `no move while list and tickler are unchanged` | A, B | Pass |
| AC3 (changed date on a task stored on another list goes to the Inbox) | `a tickler moved to a new day on a task stored on Next is saved to the Inbox`; `a changed tickler of a task stored on another list moves it to the Inbox, without its list fields` | A, B | Pass |
| AC4: one name "Tickler"/"Wiedervorlage" for chip, date label, detail property, Inbox action; the hint explains it (EN/DE) | `the tickler has one name in the form, the detail view and Inbox processing, in English and German`; `the tickler property is named Tickler, not Comes back`; `the tickler prompt names the Tickler and explains it, the schedule prompt has no such hint`; `Tickler needs a day and saves the task to the Inbox with it` (hint shown in the form) | A, B | Pass |
| AC5: open NEXT tasks with releasedOn == today show a visible "New today" text label with a content description | `a task released from Scheduled today shows the New today label, one from yesterday does not` (NextScreenTest); `only a Next task released today is new today` | A, B | Pass |
| AC5: listed first, in their normal order among themselves | `new today come first, both groups keep their order`; `tasks released today come first in their own order, the others follow in theirs` (through the view model) | A, B | Pass |
| AC5: from the next day on they show and sort like any other task | `from the next day on they sort like any other task`; `from the next day on they keep their normal place` (VM, clock one day later); NextScreenTest (yesterday's task has no marker) | A, B | Pass |
| AC5: "today" is the user's local date | `today is the user's local date, not the UTC date` (clock 2026-11-22T23:30Z in Europe/Zurich) | A, B | Pass |
| AC5: filters and presets still apply | `filters still apply to tasks released today`; `a preset still applies to tasks released today` | A, B | Pass |
| AC6: all new strings exist in English and German | `every string exists in German, is not blank and keeps its placeholders` (task detail file); `the New today marker and its description exist in English and German` (Next file, also key-set parity); tickler naming test (inbox file) | A, B | Pass |
| AC6: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | ktlintCheck and testDebugUnitTest ran (A, B) | A, B | ktlintCheck: Pass; testDebugUnitTest: Pass; lintDebug: Not run: left to the parent's final validation (`.taskcheck`) |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.TaskDraftListTest' --tests 'ch.sonensei.cockpit.gtd.task.TaskDetailStringsTest' --tests 'ch.sonensei.cockpit.gtd.task.TaskDetailListFieldsTest' --tests 'ch.sonensei.cockpit.gtd.task.TaskDetailContentTest' --tests 'ch.sonensei.cockpit.gtd.inbox.InboxScreenTest' --tests 'ch.sonensei.cockpit.gtd.next.NextViewModelNewTodayTest' --tests 'ch.sonensei.cockpit.gtd.next.NextNewTodayTest' --tests 'ch.sonensei.cockpit.gtd.next.NextScreenTest'`

then `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*'`

Result:

Pass. The first focused run had one failure in a new test of mine (it rejected "comes back" in any string, but the required hint legitimately says "it comes back to the Inbox"); the test was corrected to reject only the old labels "Comes back"/"Kommt zurück". After that: 166 focused tests pass; the gtd package run passes 1278 tests, 0 failures, and ktlintCheck passes.

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (runs the full Android `testDebugUnitTest`)

Result:

Pass: 5743 tests, 0 failures, 0 errors, 0 skipped (summed from `app/build/test-results/testDebugUnitTest/*.xml`).

## .taskcheck

Result:

Not run: `sh scripts/check.sh` covers server, web and Android lint/tests; the parent worker runs it as final validation. Android lintDebug is part of it.

## Bugs found

None. No production defect was found for the acceptance criteria.

## Coverage

Status: verified (Android, fresh run after the latest test additions; no production changes since).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately by intersecting the JaCoCo `line` elements of report.xml (executable lines only) with the added/modified lines of `git diff -U0 c4b38a016..HEAD -- android/app/src/main/java`: 77/77 changed executable lines covered (100%); branches on those lines 88/90 (97.8%). The 2 missed branches: `TaskDetailListFields.kt:107` (a Kotlin-generated branch of the tickler date setter lambda; the setter itself is exercised by the quick-date chip tests) and `TaskDetailViewModel.kt:110` (the `tickler = ticklerDate != null` default argument when a caller passes a ticklerDate but no `tickler`; production builds drafts through `TaskDraft.of`, which sets `tickler` explicitly, so this default path is not reached by the app).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-gtd-tickler-chip; base c4b38a01604c4325040f5e24b58e651b8c797045; HEAD 06a4d696c0435a1a274233a63b3ca314693bf5e2; task commits 0d14ce0d7, fd5a765f3, 2dfad2f5c, 88bde0bbc, 06a4d696c as supplied by the parent worker; the diff matches the task's `touches` and criteria.
- Source/test snapshot identifier (including uncommitted inputs): `git stash create` tree 716125c85fe0a6a3a0c9e1e3520514c22f57d1d5 for the modified tracked test files (same tree before and after the run); untracked new test `NextViewModelNewTodayTest.kt` sha256 9b024ce6e564c1b31ad30a77331e5a5ee9edeb3c44a97be85efa3d7a3f182f7f (same before and after).
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt`, `.../gtd/next/NextScreen.kt`, `.../gtd/next/NextViewModel.kt`, `.../gtd/next/TaskListUi.kt`, `.../gtd/task/TaskDetailListFields.kt`, `.../gtd/task/TaskDetailScreen.kt`, `.../gtd/task/TaskDetailViewModel.kt`. Excluded: the six `res/values*/strings_*.xml` files (resources, no executable code; covered by the string tests above) and `SPEC.md` (documentation). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle 9.8.1); `./gradlew createDebugUnitTestCoverageReport` in `android/`; full debug unit test suite.
- Run start/completion time and exit status: 2026-11-23T18:28:01+01:00 to 2026-11-23T18:32:01+01:00, exit 0; `:app:testDebugUnitTest` and `:app:createDebugUnitTestCoverageReport` executed (not up-to-date).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, written 2026-11-23 18:32:01 +0100, sha256 d1bcfda72aed491bde053c5ff3cac03ad507c3a00ee11b34970aca7bb761184a.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report mtime equals run end; snapshot tree and hash identical before and after).
- Final run after latest test additions and production fixes: yes (all test additions were made before the run; no production fixes were made).

Values below use covered/total (percentage); JaCoCo has no separate statement counter, so the Statements column shows INSTRUCTION counters.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `gtd/inbox/InboxScreen.kt` | 5352/5441 (98.4%) | 161/186 (86.6%) | 110/129 (85.3%) | 438/438 (100.0%) |
| `gtd/next/NextScreen.kt` | 3060/3109 (98.4%) | 78/81 (96.3%) | 81/101 (80.2%) | 277/277 (100.0%) |
| `gtd/next/NextViewModel.kt` | 1961/1977 (99.2%) | 136/140 (97.1%) | 103/104 (99.0%) | 215/217 (99.1%) |
| `gtd/next/TaskListUi.kt` | 1623/1635 (99.3%) | 75/85 (88.2%) | 24/24 (100.0%) | 123/123 (100.0%) |
| `gtd/task/TaskDetailListFields.kt` | 3167/3201 (98.9%) | 113/126 (89.7%) | 75/75 (100.0%) | 243/243 (100.0%) |
| `gtd/task/TaskDetailScreen.kt` | 10129/12084 (83.8%) | 317/429 (73.9%) | 222/306 (72.5%) | 789/886 (89.1%) |
| `gtd/task/TaskDetailViewModel.kt` | 5416/5564 (97.3%) | 457/523 (87.4%) | 132/136 (97.1%) | 504/511 (98.6%) |
| Aggregate (summed counters, 7 files; no file missing from the report) | 30708/33011 (93.0%) | 1337/1570 (85.2%) | 747/875 (85.4%) | 2589/2695 (96.1%) |

(Paths are under `android/app/src/main/java/ch/sonensei/cockpit/`.)

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `gtd/task/TaskDetailListFields.kt:107` (1 branch) | Tickler date setter lambda | Generated branch; the setter is exercised by the quick-date chip tests. Justified gap |
| `gtd/task/TaskDetailViewModel.kt:110` (1 branch) | `tickler` default argument | Not reached by production (`TaskDraft.of` sets it); opening a tickled task is covered via `TaskDraft.of`. Justified gap |
| `gtd/task/TaskDetailScreen.kt` (whole file: e.g. 373-390, 923-945, 1109-1159, 1288-1292 uncovered) | Pre-existing code of the detail screen outside this task (the task changed one line, 1201, which is covered) | Outside task scope; no test added |
| `gtd/task/TaskDetailViewModel.kt:863-870` | Pre-existing code, not changed by this task | Outside task scope |
| `gtd/next/NextViewModel.kt:414-415` | Pre-existing `catching` helper's cancellation path, not changed by this task | Outside task scope |
| Other partial branches in `InboxScreen.kt`, `NextScreen.kt`, `TaskListUi.kt` | Pre-existing lines; all changed lines and their branches in these files are covered | Outside task scope |

## Unable to test

- AC1 (SPEC.md wording) is documentation; checked by reading the diff, not by a test.
- `lintDebug` (part of AC6) was not run by the test-writer; it belongs to the parent's final `.taskcheck` / Android check run.
- The Next view recomputes "today" only when one of its flows emits (`LocalDate.now(clock)` inside `combine`). If the app stays open across midnight with no data change, the marker and order of yesterday's released tasks stay until the next refresh or emission. The criterion ("from the next day on") is met for every fresh state; this midnight-rollover behavior is not tested and is noted as an observation for the reviewer, not as a defect.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found. (`lintDebug` and `.taskcheck` remain for the parent worker's final validation.)
