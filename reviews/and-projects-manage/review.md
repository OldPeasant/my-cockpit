# Code Review

Date: 2026-11-08

Task: and-projects-manage - Android: create, edit, complete and drop projects

Scope: final revision of the task, `git diff 1f7a57199..2f5e046f5`. Production code reviewed in full: `ProjectEditing.kt`, `ProjectForm.kt`, `ProjectWorkRepository.kt`, `ProjectWriteApi.kt`, `ProjectsScreen.kt` and `ProjectsViewModel.kt` under `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/`, plus the EN/DE `strings_projects_lists.xml`. I re-checked the fixes for CR-1..CR-3 from the previous review in the fix diff `6afe42171..2f5e046f5`. The committed tests were only checked for coverage of the fixes. Uncommitted test changes by the concurrent test-writer were out of scope.

Requirements:
- tasks/and-projects-manage.md (acceptance criteria #1-#7 and the four recorded assumptions)
- SPEC.md §5.3, §4.2 (PATCH semantics, ETag/If-Match)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes
- Previous review and fix report: reviews/and-projects-manage/review.md (earlier revision), reviews/and-projects-manage/fixes.md

Git range:
- Branch: main
- HEAD: 2f5e046f51152f33466fb5c0b300b9aaeb6914d7 (the parent named b405e35eb, but that commit is not in this clone; the HEAD present when this review ran is recorded here)
- Base/start: 1f7a57199238adcf106c7c72531af6e8671bca2e
- Commits reviewed: 4 (ebc2d7788 heartbeat, 3655a0ce3 implementation, 6afe42171 tests and review, 2f5e046f5 fixes)
- Uncommitted changes: none at review time

Summary: 0 P0, 0 P1, 0 P2, 3 P3

Verification of the earlier findings:
- CR-1 (an old save overwrites a later form) is fixed. Every open, replace and close of the form goes through `replaceForm()`, which bumps `formSession`. `saveForm` and `create` capture the session and apply their result only if it has not changed. The project message is still shown. Three ViewModel tests cover this (`ProjectsViewModelTest.kt:700`, `:721`, `:742`). One narrow residual window remains in the CONFLICT path (CR-1 below).
- CR-2 (no confirmation while the tasks are loading) is fixed. Both `setStatus` and `saveForm` treat "not loaded" as unknown and ask. `openTasks` became `Int?`, and the dialog leaves out the count line when it is unknown. Tests: `ProjectsViewModelTest.kt:763` and a Compose test. The fix adds one edge case, where the project is no longer in the cache (CR-2 below).
- CR-3 (a stale confirmation appears for another project) is fixed. `ProjectConfirm` now carries `projectId`. `open()` clears `confirm`, `confirm()` drops a confirmation for a project that is not open, and the screen only shows a matching dialog. Tests: `ProjectsViewModelTest.kt:781`, `:795`, `ProjectsScreenTest.kt:471`.
- The German "abschließen" lint fix is correct. The EN and DE files both have 106 string/plurals entries with the same keys.

Other checks that passed:
- The PATCH body sends only changed fields, and emptied fields go as JSON `null`. `If-Match` is sent on update and delete. 404/409/412 map to CONFLICT. The project cache reloads before the form is rebased.
- Create sends status ACTIVE and the title trimmed (max 500 in the UI). Delete always confirms, matching the recorded assumption.
- The description renders through the notes `MarkdownView` both in the detail view and in the form preview.
- The new-project action is disabled offline. The detail menu is disabled while read-only or while a change on the project is running.
- No secrets, no logging of descriptions, and no dependency changes.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The CONFLICT path checks the session before a suspending read, so a narrow race remains**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:330-334`
    - Category: Correctness
    - Problem: The code checks `session == formSession` first. Only then does `replaceForm(currentProject(id)?.let { ... })` run, and `currentProject` suspends on `projects.observe().first()`, which is a Room-backed flow (`GtdRepositories.kt:113`). If the user cancels and opens another form during that DB read, the old draft's rebase replaces the new form anyway. This is the case the CR-1 fix was meant to close.
    - Why it matters: The window is short (one cache query), so the risk is low. When it happens, the new form's edits are lost without a message.
    - Suggested fix: Read `currentProject(id)` before the session check (only for CONFLICT), then check the session and apply the result with no suspension in between.

- [ ] **CR-2 | Saving a finishing status does nothing when the edited project has left the cache**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsViewModel.kt:311-318` (dialog gate at `ProjectsScreen.kt:211-213`)
    - Category: Correctness
    - Problem: After the CR-2 fix, `open` is `null` whenever `detail` is null or belongs to another project, not only while the tasks load. Suppose the edited project was trashed elsewhere and a refresh removed it from the cache while the form was open (the form still shows, because it takes precedence over the detail). If the user then sets Completed or Dropped and taps Save, `saveForm` sets `confirm`. The screen never shows that confirmation, because `detail` is null, and `confirm()` would drop it anyway. Save silently does nothing, every time. Before the fix, the PATCH went out, came back 404/CONFLICT, closed the form and told the user.
    - Why it matters: In a rare situation the screen gives no feedback, and the user can only cancel.
    - Suggested fix: Ask only when the open detail is this project (`detail?.project?.id == id`) and its tasks are not loaded or not empty. If the project is not in the cache, skip the confirmation and send the PATCH, so the existing CONFLICT handling closes the form and shows the message.

- [ ] **CR-3 | The fix report still contains an unfilled placeholder**
    - File: `reviews/and-projects-manage/fixes.md:93`
    - Category: Quality
    - Problem: The `.taskcheck` line reads `CHECK_RESULT_PLACEHOLDER`. It does not use the `<...>` form that docs/QUALITY.md line 25 rejects, so the gate may accept it. It is still unfinished evidence in a committed helper report.
    - Why it matters: A human reading the review evidence cannot tell whether `.taskcheck` ran for the fixes.
    - Suggested fix: Don't edit the helper's report by hand (AGENTS.md). If the fixer runs again for CR-1/CR-2, have it write the real result. Otherwise record the actual `sh scripts/check.sh` result in `quality.json` and say there that fixes.md left it open.

## Review conclusion

No material issues found. The earlier CR-1..CR-3 are fixed correctly, and each fix has a test. The three remaining P3 items are small robustness and evidence cleanups that the parent worker may fix or record. Acceptance criteria #1-#7 are implemented as the recorded assumptions describe. `python tasks.py verify and-projects-manage` still shows all seven criteria unverified, and the parent worker must record that evidence (including the full `sh scripts/check.sh` run for #7) before `done`.
