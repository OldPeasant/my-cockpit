# Code Review

Date: 2026-11-08

Task: and-projects-manage - Android: create, edit, complete and drop projects

Scope: final revision of the task, `git diff origin/main...HEAD -- android` (base e25300f1c, HEAD 2befbc9c0). I read the production code in full: `ProjectEditing.kt`, `ProjectForm.kt`, `ProjectWorkRepository.kt`, `ProjectWriteApi.kt`, `ProjectsScreen.kt` and `ProjectsViewModel.kt` under `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/`, plus the EN/DE `strings_projects_lists.xml`. I re-checked the round-2 fix commit 2befbc9c0 line by line against the previous review's CR-1 and CR-2, and checked the tests that cover those fixes.

Requirements:
- tasks/and-projects-manage.md (acceptance criteria #1-#7 and the four recorded assumptions)
- SPEC.md §5.3, §4.2 (PATCH semantics, ETag/If-Match)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes
- Previous review (revision 2f5e046f5) and reviews/and-projects-manage/fixes.md "Round 2"

Git range:
- Branch: main
- HEAD: 2befbc9c0e97169528799e86e861199879de2359
- Base/start: e25300f1c0566dffa66dd01bcf2bfcd51a806267
- Commits reviewed: 6 (829eb7264 implementation, aac9daf5e tests and review, 2d7179eaf review fixes, 7c93ac77f more tests and re-review, 34bd162e5 merge of wip, 2befbc9c0 round-2 fixes)
- Uncommitted changes: none at review time

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Verification of the previous findings:
- CR-1 (race in the CONFLICT path) is fixed. `ProjectsViewModel.kt:331` reads `currentProject(id)` right after `work.update`, and only for CONFLICT. The session check at line 333 and `replaceForm` at line 337 now run with no suspension in between, so a form opened while the cache read runs can't be replaced by the old draft's rebase. The CONFLICT message is still set at line 341. The new test `a conflict whose project reload finishes after its form was cancelled does not replace the next form` (`ProjectsViewModelTest.kt:743`) holds the read on a `CompletableDeferred`, cancels the form, opens a new one, and then releases the read. On the old code the session check passed before the read was held, so this test exercises the race.
- CR-2 (Save did nothing when a finishing status was saved for a project that had left the cache) is fixed. `ProjectsViewModel.kt:312-316` now asks only when the open detail is the edited project. `open` stays `null` (count unknown) while that project's tasks load. When the detail is missing or belongs to another project, the PATCH goes out. A 404 or 412 maps to CONFLICT, `currentProject` returns `null`, and `replaceForm(null)` closes the form while the message tells the user. This matches the gate in `confirm()` (line 382) and the dialog gate in the screen, so a confirmation is now only set when it can be shown and applied. The committed test `finishing in the form after the project disappeared does not leave the save doing nothing` (`ProjectsViewModelTest.kt:1159`) covers it. The parent reports it passes on 2befbc9c0. The existing confirmation tests still edit the open project, so their behaviour is unchanged.
- CR-3 (placeholder in round-1 fixes.md) needs no code change. As agreed, the parent records it in quality.json. Round 2 of fixes.md leaves round 1 untouched, which follows the AGENTS.md rule against editing helper reports.

Other checks that passed on this revision:
- Criteria #1-#6 are still implemented as the recorded assumptions describe:
  - a "New project" (+) action, disabled offline;
  - an edit form with status, area, dates and a Markdown description with edit/preview through the notes `MarkdownView`;
  - a confirmation before finishing a project, also while its tasks are not known yet;
  - delete always asks first, and its tasks keep the project;
  - a 412 rebases the form on the current version or shows a snackbar;
  - matching EN/DE string keys.
- The round-2 change touches only `saveForm`. `setStatus`, `create`, `delete` and the session handling in `replaceForm` are unchanged.
- No secrets, no logging of descriptions, no dependency or build-file changes.
- Gradle could not run inside this review. The parent ran `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.projects.*'` on 2befbc9c0 -> BUILD SUCCESSFUL. The rest of criterion #7 still has to be recorded: `lintDebug`, the full Android unit run and `sh scripts/check.sh`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. The round-2 fixes for CR-1 and CR-2 are correct and covered by tests, and CR-3 is left to quality.json as agreed. Before `done`, the parent worker still has to record evidence for criteria #1-#7 with `tasks.py verify`, including `lintDebug` and a full `sh scripts/check.sh` run for #7.
