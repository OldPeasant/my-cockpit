# Test Report

Date: 2026-11-08

Task: and-projects-manage - Android: create, edit, complete and drop projects

Scope: `git diff origin/main...HEAD -- android`, base e25300f1c0566dffa66dd01bcf2bfcd51a806267, HEAD 2befbc9c0e97169528799e86e861199879de2359 (branch main). The task's implementation commits are part of this range (merged from `origin/wip/and-projects-manage` in 34bd162e5), plus the round-2 fix commit 2befbc9c0. Production files in scope: `ProjectEditing.kt`, `ProjectForm.kt`, `ProjectWorkRepository.kt`, `ProjectWriteApi.kt`, `ProjectsScreen.kt`, `ProjectsViewModel.kt` under `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/`, and `android/app/src/main/res/values{,-de}/strings_projects_lists.xml`. Uncommitted inputs at the time of writing: this test-writer's change to `ProjectsViewModelTest.kt` (sha256 7667ab4d95db7f6565a2c83181d5d5ca6883905167f4ac5def132d1a0c3cdd6a), and `reviews/and-projects-manage/review.md`, which the concurrent code-reviewer is editing (not a test input).

Requirements:
- `tasks/and-projects-manage.md`: acceptance criteria 1-7 and the four recorded assumptions (Add icon instead of FAB, new projects ACTIVE; confirm finishing when open tasks remain or are not known yet; delete = Trash, tasks keep the project, always confirmed; 412 on save rebases the form and keeps the user's changed fields, 412 on status change or delete reloads and shows a snackbar)
- `SPEC.md` §5.3 and §4.2 (optimistic locking), `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- `reviews/and-projects-manage/review.md` and `fixes.md` (round 2: CR-1, CR-2)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModelTest.kt`

## Tests added or updated

Count: 2 added, none changed or removed.

- `finishing in the form after the project left the cache is sent without asking, and the conflict closes the form`: pins the exact behaviour of the round-2 CR-2 fix. The existing test for CR-2 only asserts "sent or a visible confirmation". This one asserts that no confirmation is set, that exactly one PATCH with `status=DROPPED` goes out at version 2, that the CONFLICT closes the form (the project is gone, so there is nothing to rebase on), and that the SAVED/CONFLICT message is shown.
- `a delete runs once at a time, and one that returns after another project was opened leaves that project open`: the delete result is held back with a `CompletableDeferred`. A second confirmed delete while the first runs is not sent again (`run` busy guard). The user opens another project before the answer arrives. The DONE answer must not close that project's detail (the `selected.value == project.id` guard in `delete()`), and the DELETED message is still shown. No existing test covered either of these.

## Requirement coverage

Commands: (A) is `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*'` on HEAD 2befbc9c0, run by the parent, BUILD SUCCESSFUL. (B) is the same command on the working tree with the 2 tests added here. The parent still has to run it.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. New project action: title required, area from the known areas, due date, review date, description | ViewModel: `a new project needs a title, and typing one clears the error`, `the form's areas are the known areas in their order`, `a created project closes the form and opens the new project`, `a create that failed keeps the form to try again and says why`, `a save runs once at a time`; Screen: `the list header's plus opens a new project, offline it is disabled`, `the new project form has no status, asks for a missing title, and saves or cancels`, `an area can be picked from the known areas`, `a due date is picked in a date picker and confirmed with OK`, `a created project is confirmed in a snackbar`; Repository: `creating a project posts the trimmed draft, returns its id and reloads only the projects`, `a new project with a blank description sends none` | (A) | Pass |
| 2. Edit: title, status, area, due date, review date; complete/drop from the menu (or form status) asks when open tasks remain | ViewModel: `editing sends only the changed fields at the project's version, then closes the form`, `saving an unchanged edit closes the form without a request`, `completing in the form with open tasks asks first, confirming saves`, `finishing in the form without open tasks saves at once`, `other status changes in the form need no confirmation`, `completing from the menu with open tasks asks first, cancelling changes nothing`, `dropping from the menu without open tasks needs no confirmation`, `reactivating a finished project needs no confirmation, the current status is no change`, `finishing while the project's tasks are still loading asks first, without a count`, `finishing from the menu while the tasks load is sent once confirmed, and asks no more once none are open`, `finishing in the form after the project disappeared does not leave the save doing nothing`, the stale-confirmation tests; Editing: all 13 `ProjectEditingTest` tests (patch building, trimming, explicit nulls, finishing statuses); Screen: menu tests for active/completed/dropped/on-hold, the confirm dialog tests (count, singular, unknown count, other project), `the edit form offers the status, the area and clearing the dates`, `a review date already set is offered in the picker and kept by OK` | (A) | Pass |
| 2. (added) A finishing status saved after the project left the cache is sent and the conflict closes the form | `finishing in the form after the project left the cache is sent without asking, and the conflict closes the form` | (B) | Not run yet: added after (A), the parent runs it (sub-agents cannot run Gradle) |
| 3. Description rendered as Markdown, edit/preview | Screen: `the description is edited as text and previewed as rendered Markdown`, `an empty description previews a hint`, `the detail renders the description as Markdown`; ViewModel: `the preview toggles, and going back drops an edit but keeps a new project's form` | (A) | Pass |
| 4. Delete with confirmation; tasks keep the project (server rule, see assumption) | ViewModel: `deleting asks first with the open task count, then trashes the project and closes its detail`, `a refused delete keeps the detail open and says why`, `a confirmation asked about one project does not apply after another project was opened by a create`; Screen: `deleting asks first, says where the project goes and what happens to its open tasks`, `a deleted project says it moved to the Trash`; Repository: `deleting a project sends its version, takes the bodiless 204 as done and reloads projects and task views` | (A) | Pass |
| 4. (added) A delete runs once; its late answer does not close another project | `a delete runs once at a time, and one that returns after another project was opened leaves that project open` | (B) | Not run yet: added after (A), the parent runs it |
| 5. Optimistic locking: 412 reloads and tells the user | Repository: `an update patches only the given fields with the version in If-Match and reloads only the projects`, `an update refused with 412 is a conflict and the projects reload`, `an update of a project trashed elsewhere (404) or refused with 409 is a conflict, offline nothing reloads`, `a delete refused with 412 is a conflict and still reloads, offline it reloads nothing`; ViewModel: `a save refused because the project changed elsewhere keeps the user's edits on the reloaded project`, `a save refused because the project was trashed elsewhere closes the form`, the three late-answer session tests incl. `a conflict whose project reload finishes after its form was cancelled does not replace the next form` (round-2 CR-1); Editing: the three rebase tests; Screen: `a form reloaded after a conflict says so`, `a project change refused because it changed elsewhere says it was reloaded` | (A) | Pass |
| 6. New strings in English and German | No unit test compares the two resource files. Android lint (`MissingTranslation`) checks that every default string has a German one. The fixer's round-1 `lintDebug` run passed. Compose tests use the English strings. | `lintDebug` (fixer round 1, earlier revision); not part of (A) | Not run on HEAD by this helper: the parent's (A) did not include `lintDebug`. The parent must run `lintDebug` on the final code for criterion 7. |
| 7. Unit/Compose tests cover the new behaviour; `ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass | All of the above | (A) for ktlint and the projects tests | ktlint + projects tests: Pass. `lintDebug`, the full `testDebugUnitTest` and `sh scripts/check.sh`: Not run by this helper (Gradle unavailable to sub-agents), the parent runs them |

## Focused test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*'` (run by the parent on HEAD 2befbc9c0, before the 2 tests added here)

Result:

Pass (BUILD SUCCESSFUL: every `ch.sonensei.cockpit.gtd.projects` test and ktlint pass). This includes the round-2 CR-1 test and the CR-2 test that failed before the fix.

The 2 tests added in this report have not been run. Gradle cannot run in this sub-agent environment (known limitation). The parent will run the same command on the working tree. I checked by hand that they follow the file's existing patterns (the `test { vm -> }` harness, `CompletableDeferred` answers, the `status(...)` helper, the ktlint chain wrapping already used in the file, lines under 140 characters).

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run by this helper (Gradle unavailable to sub-agents). The parent runs it.

## .taskcheck

Result:

Not run by this helper (`sh scripts/check.sh` runs Gradle). The parent runs it before `done`.

## Bugs found

None. Both added tests should pass when I trace them through the production code at HEAD:
- For the CR-2 path, `detail` is null once the project leaves the cache. `saveForm` sends the PATCH, `currentProject` returns null, and `replaceForm(null)` closes the form.
- For the delete path, `run` returns early while the key is busy, and `delete()` only closes when `selected.value == project.id`.

If either fails, the failure is a real regression of the behaviour named in its title. Report it to the fixer, and don't weaken the test.

Observation (not a defect): `requestDelete()` takes `detail.tasks.size` even while the project's tasks are still loading, so `openTasks` is 0 and the dialog leaves out the "open tasks keep the project" line. No count is shown and nothing wrong is stated: the general Trash sentence and the confirmation still appear. The delete assumption does not require a count while loading, so no test or finding was added.

## Coverage

Status: unavailable. Sub-agents cannot run Gradle in this environment, so `cd android && ./gradlew createDebugUnitTestCoverageReport` (`docs/ARCHITECTURE.md` "Coverage", JaCoCo via AGP) could not run. There is no fresh `android/app/build/reports/coverage/test/debug/report.xml` for this revision. No older or repository-wide numbers are given in its place.

Metric: whole-file coverage of task-changed production files (not measured).
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-projects-manage, base e25300f1c0566dffa66dd01bcf2bfcd51a806267, HEAD 2befbc9c0e97169528799e86e861199879de2359. Scope from the parent's instructions and `git diff --stat e25300f1c..HEAD -- android`, which matches the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 2befbc9c0 plus uncommitted `ProjectsViewModelTest.kt` (sha256 7667ab4d95db7f6565a2c83181d5d5ca6883905167f4ac5def132d1a0c3cdd6a).
- Exact included files; renamed/deleted/excluded files and reasons: the six Kotlin files above. The two string XML files are excluded because they are resources, not executable code. Nothing was renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP, `./gradlew createDebugUnitTestCoverageReport` in `android/`. Not run.
- Run start/completion time and exit status: not applicable (not run).
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: not applicable.
- Final run after latest test additions and production fixes: no. Gradle is unavailable to this helper. The parent may run the Android coverage command if it wants numbers.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `ProjectEditing.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ProjectForm.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ProjectWorkRepository.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ProjectWriteApi.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ProjectsScreen.kt` | N/A (not measured) | N/A | N/A | N/A |
| `ProjectsViewModel.kt` | N/A (not measured) | N/A | N/A | N/A |

Estimate from reading the tests against the production code. This is not a measurement:
- `ProjectEditing.kt`: every branch of the patch building, rebase and `finishes` is tested directly (13 tests). Probably close to complete.
- `ProjectsViewModel.kt`: every public form, status, delete and confirm entry point is tested, including offline, read-only, the session race guards (CR-1 rounds 1 and 2), unknown task counts and stale confirmations. The two added tests close the remaining delete race and pin the exact CR-2 behaviour.
- `ProjectWorkRepository.kt` / `ProjectWriteApi.kt`: create, update and delete are tested for DONE, 412, 404, 409, offline and the reloads.
- `ProjectForm.kt` / `ProjectsScreen.kt`: the Compose tests cover the form (new and edit), the menu per status, the confirm dialog variants, the date pickers, the Markdown edit/preview and the snackbars.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ProjectsViewModel.kt` `delete()` late-answer guard, `run` busy guard for delete | Criterion 4: a delete's answer must not close a project opened meanwhile, and the delete must not be sent twice | Test added: `a delete runs once at a time, and one that returns after another project was opened leaves that project open` |
| `ProjectsViewModel.kt` `saveForm` CR-2 branch (detail is null, CONFLICT, `currentProject` returns null) | Criterion 2/5: saving a finishing status for a project that left the cache must reach the server and close the form | Test added: `finishing in the form after the project left the cache is sent without asking, and the conflict closes the form` |
| `strings_projects_lists.xml` en/de parity | Criterion 6 | No unit test. Android lint `MissingTranslation` checks it. The parent must run `lintDebug` on the final code |
| Line-level gaps in `ProjectForm.kt` and `ProjectsScreen.kt` (layout-only branches) | Presentation only | Can't be identified without the JaCoCo report. No test added just to raise coverage |

## Unable to test

- Running the tests and coverage: Gradle does not work for sub-agents here. The parent runs the focused command (B), the full `ktlintCheck testDebugUnitTest lintDebug`, and `sh scripts/check.sh`. This needs follow-up by the parent, not a new task.
- Criterion 6 (en/de) as a unit test: it is covered by Android lint instead. Follow-up: the parent runs `lintDebug`.

## Summary

Tests added, and no production defects found. Validation is still open: the parent ran the existing projects tests and ktlint on HEAD 2befbc9c0 (Pass). The 2 new tests, `lintDebug` and `sh scripts/check.sh` still have to be run by the parent. Task-scoped coverage could not be measured because Gradle is unavailable to this helper.
