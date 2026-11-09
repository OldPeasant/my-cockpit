# Test Report

Date: 2026-11-09

Task: and-waiting-contact-mail - Android Draft email: To from the linked waiting-for contact

Scope: `git diff a95fa744f67f90916b6100a637151a8153e8ee80..c48d73bd01a569d40eb6927ee2a8b4b437c93f70` (final revision
given by the parent; HEAD of the checkout, branch main). Task-owned commits: f2a077e6a (implementation), 6c900fb6b
(tests and review reports), c48d73bd0 (fixer: CR-1 draft-tap guard plus test); fae4609b3 and 0d725793d only touch
`tasks/`. This is a re-run on the final code after the fixer commit; the previous report described f2a077e6a.

Requirements:
- tasks/and-waiting-contact-mail.md acceptance criteria and its recorded assumption (fallback to the waiting-for text
  when the contact cannot be loaded, has no address, or is deleted; contact fetched live)
- SPEC.md §5.3 (Draft email prefills To from the linked contact)
- reviews/and-waiting-contact-mail/review.md CR-1 and reviews/and-waiting-contact-mail/fixes.md (guard against
  repeated Draft email taps while the contact lookup runs)
- docs/ARCHITECTURE.md "Coverage" (Android: JaCoCo via AGP)
- Parent instructions (single focused Gradle run, no commit, no TFP state)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/WaitingRouteDraftMailTest.kt` (this run, uncommitted)

Already committed in the task (6c900fb6b, c48d73bd0) and re-run here: GtdMappersTest, TaskRepositoryTest,
ListViewModelsTest, TaskListsRepositoryTest, WaitingDraftEmailTest, WaitingRouteDraftMailTest.

## Tests added or updated

Count: 1 (new; nothing removed or weakened)

- WaitingRouteDraftMailTest `a contact still loading for one task does not hold back another task's draft`: two
  waiting tasks; the contact lookup of the first ("Contract", linked to a contact) is suspended on a
  CompletableDeferred; tapping Draft email on the second ("Offer", no contact, address in the text) opens its mail
  immediately (To ben@example.net, subject Offer) and only one mail is started; completing the lookup then opens the
  first task's mail (To anna@example.com, subject Contract). This pins that the CR-1 guard is per task, as described
  in fixes.md: the fixer's test used a single task, so a guard that blocked every draft while any lookup runs would
  have passed it.

Review of the fixer's test `taps while the contact is still loading open a single mail`: it controls ordering with a
CompletableDeferred (no real delays), asserts no mail before the lookup completes, exactly one SENDTO after, one
lookup call, and that a later tap drafts again (guard released in `finally`). Adequate for CR-1.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: GtdTask model, mapper and cache carry `waitingForContactId` | GtdMappersTest (2), TaskRepositoryTest (2, Room in-memory v3 DB), TaskListsRepositoryTest `a loaded waiting task keeps its linked contact, an unlinked one has none` | `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' createDebugUnitTestCoverageReport` (in android/) | Pass |
| AC2: Draft email To = linked contact's first email | ListViewModelsTest `the mail recipient is the linked contact's email address, asked for that contact`, `the injected view model takes the contact's first email address`; WaitingRouteDraftMailTest `draft email is addressed to the linked contact's email address`; WaitingDraftEmailTest | same | Pass |
| AC2: fallback to an address in the waiting-for text (no contact, no address, offline, failed) | ListViewModelsTest fallback tests (4); WaitingRouteDraftMailTest `draft email falls back to the address in the waiting-for text when the contact has none`; WaitingDraftEmailTest | same | Pass |
| CR-1 fix: repeated taps during a pending lookup open one mail; tapping again later drafts again | WaitingRouteDraftMailTest `taps while the contact is still loading open a single mail` | same | Pass |
| CR-1 fix: the guard is per task (another task's draft is not blocked) | WaitingRouteDraftMailTest `a contact still loading for one task does not hold back another task's draft` | same | Pass |
| AC3: unit tests cover both cases | all of the above | same | Pass |
| AC3: `sh scripts/check.sh` passes | none | not run | Not run: parent asked for the focused Gradle run only; the parent runs `.taskcheck` |

## Focused test execution

Command:

`./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.*' createDebugUnitTestCoverageReport --console=plain -q` (working directory `android/`)

Result:

Pass. Exit 0, ktlintCheck clean. 86 suites, 1212 tests, 0 failures, 0 errors, 0 skipped; every suite result file
was written inside this run. WaitingRouteDraftMailTest 4/4, ListViewModelsTest 19/19.

## Full test execution

Command:

`./gradlew ktlintCheck testDebugUnitTest lintDebug` (whole Android suite)

Result:

Not run: the parent asked for the focused `ch.sonensei.cockpit.gtd.*` run only (Gradle must not run in parallel with
the parent).

## .taskcheck

Result:

Not run (parent runs `sh scripts/check.sh` before `done`).

## Bugs found

None.

## Coverage

Status: verified (fresh run on the final revision plus the new test).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting the added/modified lines of
`git diff -U0 a95fa744..c48d73bd -- android/app/src/main` with the JaCoCo XML `line` elements (`ci`/`mi`/`cb`/`mb`);
a line counts as covered when `ci` is above 0, branches are summed `cb` over `cb+mb` on those lines. Result: 30/31
changed executable lines (96.8%), 29/32 branches on changed lines (90.6%).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-waiting-contact-mail; base
  a95fa744f67f90916b6100a637151a8153e8ee80; HEAD c48d73bd01a569d40eb6927ee2a8b4b437c93f70; commits and base given by
  the parent, cross-checked with `git log` and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): production code = HEAD c48d73bd (no uncommitted
  production changes; `git status` shows only the test file below). Uncommitted test file sha256:
  WaitingRouteDraftMailTest.kt 76678edee447b0cc9f8bdae19b8b588fe474779a5d72c63074d833054f466305; WaitingScreen.kt
  9bbe31cda7683d72bad9e49d2e32efd91ef082a936a47bcc42aacf5f699cce17 (both identical before and after the run).
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/gtd/data/{GtdCacheDatabase,GtdMappers,GtdModels}.kt`,
  `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/{ListViewModels,TaskLists,WaitingScreen}.kt`. Excluded:
  `android/app/schemas/.../GtdCacheDatabase/3.json` (generated Room schema, not executable), test files, `reviews/`,
  `tasks/`. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (version
  as configured by the build); command above in `android/`; selection `--tests 'ch.sonensei.cockpit.gtd.*'` (hits
  come only from GTD tests).
- Run start/completion time and exit status: 2026-11-09T09:37:21Z to 2026-11-09T09:38:53Z, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`,
  sha256 62c590e3cc2a36173b2d241ec2956aa50251145e7f071c501cf82da82fd31d7d, written 2026-11-09T09:38:51Z.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report mtime and all 86 suite result files inside the
  run window; input hashes unchanged).
- Final run after latest test additions and production fixes: yes (covers the fixer commit c48d73bd and the test
  added in this run).

Values below use covered/total (percentage); JaCoCo has no separate statement counter, so the Statements column
shows instructions.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `gtd/data/GtdCacheDatabase.kt` | 571/571 (100%) | N/A (0/0) | 54/54 (100%) | 67/67 (100%) |
| `gtd/data/GtdMappers.kt` | 609/609 (100%) | 50/50 (100%) | 6/6 (100%) | 82/82 (100%) |
| `gtd/data/GtdModels.kt` | 535/544 (98.3%) | N/A (0/0) | 50/53 (94.3%) | 55/55 (100%) |
| `gtd/lists/ListViewModels.kt` | 727/738 (98.5%) | 27/28 (96.4%) | 44/46 (95.7%) | 90/90 (100%) |
| `gtd/lists/TaskLists.kt` | 788/828 (95.2%) | 26/28 (92.9%) | 31/31 (100%) | 92/92 (100%) |
| `gtd/lists/WaitingScreen.kt` | 1151/1188 (96.9%) | 77/86 (89.5%) | 22/23 (95.7%) | 93/95 (97.9%) |
| Aggregate (summed counters, all 6 files measured, none missing) | 4381/4478 (97.8%) | 180/192 (93.8%) | 207/213 (97.2%) | 479/481 (99.6%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `WaitingScreen.kt:67` (one branch), `:68` (Toast line) | "no mail app" toast when no app handles ACTION_SENDTO | Left uncovered: Robolectric records `startActivity` instead of throwing ActivityNotFoundException, so the failure branch is not reachable without changing test infrastructure; behavior unchanged by the task (only wrapped in the guard). |
| `WaitingScreen.kt:63` (2 of 6 branches), `:61` (2 of 6) | Compose compiler-generated lambda memoization branches for `draftMail` / callbacks | Not task behavior; the guard's own `if (drafting.add(task.id))` at line 64 has both branches covered (first tap and repeated tap). |
| `WaitingScreen.kt:39-43,48,86,116` | Hilt-activity switch, default parameters, pre-existing row code | Pre-existing code outside this task; not touched by the diff. |
| `ListViewModels.kt:150` (partially) | default `contactEmail = { null }` body | Never invoked: production and tests always pass a lambda; trivial. |
| `ListViewModels.kt:50,55,119` | other list view models | Pre-existing, unchanged by the task. |
| `GtdModels.kt:65-67`, `TaskLists.kt:76,191-192` | unrelated model/enum mapping branches | Pre-existing, unchanged by the task (the changed line in each is covered). |

## Unable to test

- Room v2 to v3 upgrade on an installed app: the database uses a destructive fallback (cache only, per the task's
  assumption); verifying it needs an instrumented test on a device, not part of this run. No follow-up needed beyond
  the recorded assumption.
- "No mail app" toast path in WaitingRoute (see coverage table): not reachable under Robolectric's default activity
  handling; unchanged behavior.
- `sh scripts/check.sh` (AC3): left to the parent as instructed.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient for the final revision
c48d73bd; no production defects found.
