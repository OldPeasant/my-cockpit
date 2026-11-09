# Test Report

Date: 2026-11-09

Task: and-waiting-contact-mail - Android Draft email: To from the linked waiting-for contact

Scope: `git diff a95fa744f67f90916b6100a637151a8153e8ee80..20acec0b79fc9af27e7aa1f93c4c87e345a6cd08`
(task commit 20acec0b, given by the parent). The checkout's HEAD is f2a077e6a582fbd1490345705d623a92cca1c3cb
("and-waiting-contact-mail: Draft email To from the linked waiting-for contact"); `git diff 20acec0b f2a077e6a -- android`
is empty, so the measured code is identical to the task commit. Commits between base and HEAD otherwise only touch
`tasks/` (assume, heartbeat). Branch: main.

Requirements:
- tasks/and-waiting-contact-mail.md acceptance criteria and its recorded assumption (fallback to the waiting-for text
  when the contact cannot be loaded, has no address, or is deleted; contact fetched live)
- SPEC.md §5.3 (Draft email prefills To from the linked contact)
- docs/ARCHITECTURE.md "Coverage" (Android: JaCoCo via AGP)
- Parent instructions (Android only, tests under android/app/src/test, focused Gradle run only)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/GtdMappersTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/data/TaskRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/ListViewModelsTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/TaskListsRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/WaitingRouteDraftMailTest.kt` (new)

Already present from the implementation commit (not changed by me): two tests in `WaitingDraftEmailTest.kt`.

## Tests added or updated

Count: 13 (11 new, 2 existing tests strengthened with extra assertions; nothing removed or weakened)

- GtdMappersTest: `the linked waiting-for contact survives the cache row`, `a task without a linked contact keeps no contact id in the cache row`
- TaskRepositoryTest (updated): `refresh stores the loaded tasks with all fields` now sends and asserts `waitingForContactId` through the real Room in-memory cache; `a task the server did not release has no release day in the cache` also asserts a null contact id
- TaskListsRepositoryTest: `a loaded waiting task keeps its linked contact, an unlinked one has none` (API JSON -> `TaskResponse.toTask`)
- ListViewModelsTest (WaitingViewModel.mailRecipient):
  - internal constructor with a fake `contactEmail`: `the mail recipient is the linked contact's email address, asked for that contact`, `the mail recipient falls back to the waiting-for text when the contact has no address`, `without a linked contact no contact is asked`
  - Hilt `@Inject` constructor with a mocked `ContactsRepository` (exercises the private `firstEmail`): `the injected view model takes the contact's first email address`, `the injected view model falls back to the text for a contact without email addresses`, `the injected view model falls back to the text when the contact cannot be loaded` (Offline, Failed 404, and no text -> null)
- WaitingRouteDraftMailTest (Robolectric + Compose, real WaitingRoute and WaitingViewModel): `draft email is addressed to the linked contact's email address`, `draft email falls back to the address in the waiting-for text when the contact has none` (asserts the started ACTION_SENDTO intent's EXTRA_EMAIL, mailto recipient and subject)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: GtdTask model, mapper and cache carry `waitingForContactId` | GtdMappersTest (2 new), TaskRepositoryTest (2 updated, Room in-memory), TaskListsRepositoryTest `a loaded waiting task keeps its linked contact...` | `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' createDebugUnitTestCoverageReport` (in android/) | Pass |
| AC2: Draft email To = linked contact's first email | ListViewModelsTest `the mail recipient is the linked contact's...`, `the injected view model takes the contact's first email address`; WaitingRouteDraftMailTest `draft email is addressed to the linked contact's email address`; WaitingDraftEmailTest `a linked contact's first email address is the recipient...` | same | Pass |
| AC2: fallback to an address in the waiting-for text (no contact, no address, offline, failed) | ListViewModelsTest fallback tests (4); WaitingRouteDraftMailTest fallback test; WaitingDraftEmailTest `without a contact address...` | same | Pass |
| AC3: unit tests cover both cases | all of the above | same | Pass |
| AC3: `sh scripts/check.sh` passes | none | not run | Not run: parent asked for focused Gradle runs only; the parent runs `.taskcheck` |
| Room cache schema v3 (destructive fallback, cache only) | indirectly via TaskRepositoryTest on the v3 in-memory DB | same | Pass (migration from v2 on a device not tested: destructive fallback by design) |

## Focused test execution

Command:

`./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' createDebugUnitTestCoverageReport --console=plain -q` (working directory `android/`)

Result:

Pass. Exit 0, ktlintCheck clean. 86 suites, 1210 tests, 0 failures, 0 errors, 0 skipped (all suites timestamped in this run).
A first run before adding WaitingRouteDraftMailTest also passed (85 suites, 1208 tests).

## Full test execution

Command:

`./gradlew ktlintCheck testDebugUnitTest lintDebug` (whole Android suite)

Result:

Not run: the parent asked for the focused `ch.sonensei.cockpit.gtd.*` run only (Gradle must not run in parallel with the parent).

## .taskcheck

Result:

Not run (parent runs `sh scripts/check.sh` before `done`).

## Bugs found

None.

## Coverage

Status: verified (whole-file and changed-line, from a fresh run with the final tests).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting the added/modified lines of `git diff -U0 a95fa744..HEAD -- android/app/src/main` with the executable lines of the JaCoCo XML (`line` elements, `ci`/`mi`/`cb`/`mb`); a line counts as covered when `ci` is above 0. Result: 25/26 changed executable lines (96.2%), 23/24 branches on changed lines (95.8%).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-waiting-contact-mail; base a95fa744f67f90916b6100a637151a8153e8ee80; HEAD f2a077e6a582fbd1490345705d623a92cca1c3cb (android tree identical to task commit 20acec0b given by the parent); scope from the parent and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): production code = HEAD (no uncommitted production changes). Uncommitted test files, sha256: GtdMappersTest e62d577e...e678, TaskRepositoryTest 582ab4a0...6263, ListViewModelsTest 5d53efa3...082c, TaskListsRepositoryTest 126ddb92...bb3d, WaitingRouteDraftMailTest 6d0f9469...8d71 (identical before and after the run).
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/{GtdCacheDatabase,GtdMappers,GtdModels}.kt`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/{ListViewModels,TaskLists,WaitingScreen}.kt`. Excluded: `android/app/schemas/.../GtdCacheDatabase/3.json` (generated Room schema, not executable), the test file. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (Gradle `jacoco` plugin, version as configured by the build); command above in `android/`; selection `--tests 'ch.sonensei.cockpit.gtd.*'` (so hits only come from GTD tests).
- Run start/completion time and exit status: 2026-11-09T09:29:33Z to about 09:31:00Z, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 13e851eb52ddd08ad538d8d9f1770f5ea8b9405e0547a4740d7c03ca8ec9bd9a, written 2026-11-09T09:30:57Z.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report mtime and all suite timestamps inside the run window; test hashes unchanged after the run).
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); JaCoCo has no separate statement counter, so the Statements column shows instructions.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `gtd/data/GtdCacheDatabase.kt` | 571/571 (100%) | N/A (0/0) | 54/54 (100%) | 67/67 (100%) |
| `gtd/data/GtdMappers.kt` | 609/609 (100%) | 50/50 (100%) | 6/6 (100%) | 82/82 (100%) |
| `gtd/data/GtdModels.kt` | 535/544 (98.3%) | N/A (0/0) | 50/53 (94.3%) | 55/55 (100%) |
| `gtd/lists/ListViewModels.kt` | 727/738 (98.5%) | 27/28 (96.4%) | 44/46 (95.7%) | 90/90 (100%) |
| `gtd/lists/TaskLists.kt` | 788/828 (95.2%) | 26/28 (92.9%) | 31/31 (100%) | 92/92 (100%) |
| `gtd/lists/WaitingScreen.kt` | 1073/1110 (96.7%) | 67/72 (93.1%) | 22/23 (95.7%) | 89/91 (97.8%) |
| Aggregate (summed counters, all 6 files measured, none missing) | 4303/4400 (97.8%) | 170/178 (95.5%) | 207/213 (97.2%) | 475/477 (99.6%) |

Before the new tests (first run, same scope): instructions 4247/4400, branches 169/178, lines 471/477; changed lines 23/26, changed-line branches 22/24 (the WaitingRoute draft-mail lambda was not executed).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `WaitingScreen.kt:62-63` (one branch, Toast line) | "no mail app" toast when no app handles ACTION_SENDTO | Left uncovered: Robolectric records `startActivity` instead of throwing ActivityNotFoundException, so the failure branch cannot be reached without changing test infrastructure; behavior is unchanged from before the task (only moved into a coroutine). |
| `WaitingScreen.kt:38-42,47,77,107` | Hilt-activity switch, default parameters | Pre-existing code outside this task; not touched by the diff. |
| `ListViewModels.kt:150` (partially) | default `contactEmail = { null }` body | Never invoked: both production and tests pass a lambda; trivial. |
| `ListViewModels.kt:50,55,119` | other list view models | Pre-existing, unchanged by the task. |
| `GtdModels.kt:65-67`, `TaskLists.kt:76,191-192` | unrelated model/enum mapping branches | Pre-existing, unchanged by the task (the changed line in each is covered). |

## Unable to test

- Room v2 to v3 upgrade on an installed app: the database uses a destructive fallback (cache only, per the task's assumption); verifying it needs an instrumented test on a device, which this run does not include. No follow-up needed beyond the recorded assumption.
- "No mail app" toast path in WaitingRoute (see coverage table): not reachable under Robolectric's default activity handling; unchanged behavior.
- `sh scripts/check.sh` (AC3): left to the parent as instructed.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
