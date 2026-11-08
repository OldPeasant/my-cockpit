# Code Review

Date: 2026-11-08

Task: and-projects-manage - Android: create, edit, complete and drop projects

Scope: final revision of the task, `git diff origin/main...0a2ecdbf8 -- android` (base e25300f1c, HEAD 0a2ecdbf8). Production code was reviewed in full on revision 2befbc9c0: `ProjectEditing.kt`, `ProjectForm.kt`, `ProjectWorkRepository.kt`, `ProjectWriteApi.kt`, `ProjectsScreen.kt` and `ProjectsViewModel.kt` under `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/`, plus the EN/DE `strings_projects_lists.xml`. This pass checked the delta `git diff 2befbc9c0 0a2ecdbf8 -- android`: only `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModelTest.kt` changed (2 new tests, +41 lines). The rest of the commit touches only `reviews/`. Production code is the same as on 2befbc9c0.

Requirements:
- tasks/and-projects-manage.md (acceptance criteria #1-#7 and the four recorded assumptions)
- SPEC.md §5.3, §4.2 (PATCH semantics, ETag/If-Match)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes
- Earlier reviews (revisions 2f5e046f5 and 2befbc9c0) and reviews/and-projects-manage/fixes.md "Round 2"

Git range:
- Branch: main
- HEAD: 0a2ecdbf8d3d268e7297f345104a3f0d65033ef3
- Base/start: e25300f1c0566dffa66dd01bcf2bfcd51a806267
- Commits reviewed: 7 (829eb7264 implementation, aac9daf5e tests and review, 2d7179eaf review fixes, 7c93ac77f more tests and re-review, 34bd162e5 merge of wip, 2befbc9c0 round-2 fixes, 0a2ecdbf8 more ViewModel tests and reports)
- Uncommitted changes: none at review time

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Review of the delta (0a2ecdbf8, test code only):
- `finishing in the form after the project left the cache is sent without asking, and the conflict closes the form` (`ProjectsViewModelTest.kt:1182`). Holiday has two open tasks (`book`, `hotel` in the shared `taskFlow`), so the confirmation gate would fire if the detail were still holiday. The test removes holiday from `projectFlow` after `editProject()`, so `detail` becomes `null`. It then saves a DROPPED status and checks four things: no confirmation (`ProjectsViewModel.kt:316` needs `detail != null`), exactly one PATCH `work0.update(holiday.id, 2, {status: DROPPED})`, the form closed (`currentProject` returns `null`, so `replaceForm(null)` runs at line 337), and the SAVED/CONFLICT message (line 341). Each of these would fail on the pre-fix behaviour, where the confirmation was set and nothing was sent. The test is stricter than the existing `... does not leave the save doing nothing` test at line 1159, which accepts either outcome. The test does what its name says.
- `a delete runs once at a time, and one that returns after another project was opened leaves that project open` (`ProjectsViewModelTest.kt:1201`). `work0.delete` waits on a `CompletableDeferred`. The second `requestDelete()` + `confirm()` really reaches the busy guard: `readOnly` (`ProjectsViewModel.kt:147`) depends only on offline and the detail error, not on `busy`, so `requestDelete` sets the confirmation again and `confirm()` calls `delete()`. `run` then returns at line 524 because `holiday.id` is still in `busy`. `coVerify(exactly = 1)` therefore checks the guard and does not pass trivially. Opening garden and then finishing the delete with DONE checks `selected.value == project.id` at line 418. If `close()` ran unconditionally, `detail` would be `null` and the `garden` assertion would fail. The DELETED message is checked too.
- Flakiness: both tests run on `UnconfinedTestDispatcher` (Main and `runTest`). The only suspension is a `CompletableDeferred` that the test completes itself. There is no real time, no clock dependency and no shared mutable state across tests, because the fixtures are instance fields that JUnit rebuilds for each test. I saw no source of nondeterminism.
- Style matches the surrounding tests (mockk `coEvery`/`coVerify`, the `test { vm -> }` harness, fictitious data). The parent reports ktlint passed on 0a2ecdbf8.

Earlier findings (checked on 2befbc9c0, unchanged since):
- CR-1 (race in the CONFLICT path) is fixed. `ProjectsViewModel.kt:331` reads `currentProject(id)` before the session check, so no suspension lies between the check at line 333 and `replaceForm` at line 337. The test at `ProjectsViewModelTest.kt:743` covers it.
- CR-2 (Save did nothing for a finishing status on a project that had left the cache) is fixed. `ProjectsViewModel.kt:312-316` asks only when the open detail is the edited project. Otherwise the PATCH is sent, and the conflict closes the form with a message. The new test at line 1182 now pins this behaviour exactly.
- CR-3 (placeholder in round-1 fixes.md) needs no code change. The parent records it in quality.json.

Other checks that passed:
- Criteria #1-#6 are implemented as the recorded assumptions describe:
  - a "New project" (+) action, disabled offline;
  - an edit form with status, area, dates and a Markdown description with edit/preview;
  - a confirmation before finishing a project, also while its tasks are not known yet;
  - delete always asks first, and its tasks keep the project;
  - a 412 rebases the form on the current version or shows a snackbar;
  - matching EN/DE string keys.
- No secrets, no logging of descriptions, no dependency or build-file changes.
- Validation: Gradle cannot run inside this review. The parent reports two runs. On 0a2ecdbf8: `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*'` -> BUILD SUCCESSFUL, 65 ProjectsViewModelTest cases passed, both new ones included. On 2befbc9c0: the full `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug` -> exit 0. The delta is test-only, so that full run still covers the production code and lint. Criterion #7 also needs `sh scripts/check.sh`, and the parent has to record it.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The two new ViewModel tests are correct, exercise the behaviour their names describe (the CR-2 save path and the delete busy guard with a project switch) and are deterministic. Before `done`, the parent worker still has to record evidence for criteria #1-#7 with `tasks.py verify`, including a full `sh scripts/check.sh` run for #7, and set `reviewed_revision` to 0a2ecdbf8 in quality.json.
