# Test Report

Date: 2026-11-09

Task: gtd-links-everywhere - Linked items on projects and files; follow-up by mail (web + Android)

Scope: `git diff 4a3afeb14 HEAD` excluding `tasks/` and `reviews/`. Base 4a3afeb14a46d69971ca600e515840d50072bdfa (the claim commit and current merge-base with origin/main, given by the parent). Measured at HEAD 552099febf91af06cca83fba8b840e5968b5f8f8 (final code after the fixer's TW-4/TW-5/CR-1/CR-2/CR-4 fixes in 671a3cf49). During the run HEAD moved to 5bd3bb42a7697f85f95c0d08b14af05bf5702a4b; `git diff --stat 552099feb HEAD -- . ':!tasks' ':!reviews'` is empty (only a heartbeat, review.md and a new task file), so the measured code is the code at the new HEAD. This report replaces the previous tests.md, which described 3f534445d before TW-4/TW-5 were fixed.

Requirements:
- tasks/gtd-links-everywhere.md: acceptance criteria and the recorded assumptions (web Draft email opens `/mail/compose?to=&subject=`, To = the contact's first email, else an address in the waiting-for text, else empty; Android opens a mailto `ACTION_SENDTO` with an address from the waiting-for text; Android linked-contact lookup deferred to `and-waiting-contact-mail`)
- SPEC.md §4.3 (linked-items panel on every detail view) and §5.3 (waiting follow-up)
- docs/ARCHITECTURE.md "Coverage"
- reviews/gtd-links-everywhere/fixes.md (TW-4, TW-5, CR-1, CR-2, CR-4)

## Test files changed

This run (uncommitted, for the parent to commit):
- `web/src/app/features/gtd/lists/list-views.spec.ts` (emailIn trailing-dot edge cases)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/lists/WaitingDraftEmailTest.kt` (the same edge cases on Android)
- `android/app/src/test/java/ch/sonensei/cockpit/files/FilePreviewLinksRouteInAppTest.kt` (new)

Already in the task, re-checked against the final code and unchanged: `web/.../files/preview/preview-dialog.spec.ts` (links toggle, paging, close on NavigationStart, arrow keys), `web/.../gtd/lists/list-pages.spec.ts` (Waiting > Draft email), `web/.../gtd/projects/projects-states.spec.ts` (project panel), `web/.../mail/compose/mail-compose-page.spec.ts` (`?to`/`?subject` prefill), `android/.../files/FilePreviewLinksTest.kt`, `android/.../gtd/projects/ProjectLinksToggleTest.kt`, `android/.../gtd/lists/ListScreensTest.kt`.

## Tests added or updated

Count: 5 (3 web parameterized cases, 1 updated Android test, 2 new Android in-app tests). No existing test was weakened, removed or skipped.

- `list-views.spec.ts` > emailIn > "finds the bare address in {text}, without surrounding punctuation": new cases `Ask anna@mail.example.co.uk.` (multi-level domain plus full stop) and `Ping anna@example.test...` (several dots); "finds none in {text}": new case `alex@localhost.` (a full stop must not make a host without a domain into an address). These pin the TW-5 regex fix (`[^X]*[^X.]` with backtracking) against its edge cases.
- `WaitingDraftEmailTest` > "a sentence ending after the address does not add its full stop to it": the same three cases on Android, so both clients agree.
- `FilePreviewLinksRouteInAppTest` (new, Hilt activity with fake files, links and notes repositories):
  - "the preview's links toggle shows the real panel with the previewed file's linked items": the toggle shows the real `LinkedItemsSection`, loaded once for `FILE:{file id}`. Earlier Android tests only checked the toggle, because the panel needs Hilt.
  - "a linked note opens in place of the files, and Back returns to the preview with its links still shown": the linked note replaces the files screen, and system Back returns to the same preview with the panel still open (the earlier CR-2 fix).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Web project detail shows the linked-items panel | `projects-states.spec.ts` "shows the linked-items panel of the project (SPEC.md §4.3)", plus the panel in "shows an error when the tasks cannot be loaded, and retries" | `npm run check` and `npm run coverage` (web/) | Pass |
| Android project detail shows the linked-items panel | `ProjectLinksToggleTest` (5); layout regressions: `ProjectsScreenTest` (63), `ProjectTaskListTest` (22), `FabClearanceTest` (8), `ProjectsScreensInAppTest` (3) | focused Android command (below) | Pass. The toggle is tested, but the panel and linked-item navigation inside a project are not rendered in a test (see "Unable to test") |
| Web file preview shows the linked-items panel; following a link closes the preview (CR-2) | `preview-dialog.spec.ts` > linked items (4: toggle, paging, close on NavigationStart, arrow keys) | `npm run check` / `npm run coverage` (web/) | Pass |
| Android file preview shows the linked-items panel | `FilePreviewLinksTest` (5), `FilePreviewLinksRouteInAppTest` (2, new), `FilesRouteInAppTest` (24), `FilesScreenTest` (28), `FilePreviewScreenTest` (33) | focused Android command | Pass |
| Web waiting "Draft email": To from the contact, else the waiting-for text, else empty; subject from the task; no navigation after leaving or on a stale click | `list-pages.spec.ts` > Waiting For > Draft email, `list-views.spec.ts` followUpMail (3) and emailIn (incl. TW-4/TW-5 cases and 3 new edge cases) | `npm run check` / `npm run coverage` (web/) | Pass |
| Web compose `?to=`/`?subject=` prefill | `mail-compose-page.spec.ts` prefill (4) | `npm run check` / `npm run coverage` (web/) | Pass |
| Android waiting "Draft email" (mailto, address from the waiting-for text, subject from the task) | `ListScreensTest` (16), `WaitingDraftEmailTest` (8) | focused Android command | Pass |
| New strings in English and German | German tests in `ProjectLinksToggleTest`, `FilePreviewLinksTest`, `WaitingDraftEmailTest`; web `ng build` (part of `npm run check`) | `npm run check` (web/), focused Android command | Pass |
| Unit tests cover the new behavior; `sh scripts/check.sh` passes | all of the above | `npm run check` (web/), focused Android tests + `ktlintCheck` | Pass for the parts run. `sh scripts/check.sh` was not run as one command here: the parent runs it before `done` |

## Focused test execution

Commands:

1. `npx ng test --watch=false --include src/app/features/gtd/lists/list-views.spec.ts --include src/app/features/files/preview/preview-dialog.spec.ts --include src/app/features/mail/compose/mail-compose-page.spec.ts --include src/app/features/gtd/lists/list-pages.spec.ts --include src/app/features/gtd/projects/projects-states.spec.ts` (web/), after `npx prettier --check` on the changed spec
2. `./gradlew ktlintCheck testDebugUnitTest --tests '*FilePreviewLinksTest' --tests '*ProjectLinksToggleTest' --tests '*WaitingDraftEmailTest' --tests '*ListScreensTest' --tests '*ProjectsScreenTest' --continue` (android/, the parent's command), before the new in-app test was added
3. `./gradlew ktlintCheck testDebugUnitTest --tests '*FilePreviewLinksRouteInAppTest' --tests '*WaitingDraftEmailTest' --continue` (android/), after the new tests
4. Final: `./gradlew testDebugUnitTest --tests '*FilePreviewLinksTest' --tests '*ProjectLinksToggleTest' --tests '*WaitingDraftEmailTest' --tests '*ListScreensTest' --tests '*ProjectsScreenTest' --tests '*FilePreviewLinksRouteInAppTest' --tests '*FilesRouteInAppTest' --tests '*FilesScreenTest' --tests '*FilePreviewScreenTest' --tests '*ProjectsScreensInAppTest' --tests '*ProjectTaskListTest' --tests '*FabClearanceTest' createDebugUnitTestCoverageReport --continue` (android/)

Result:

Pass.
1. 5 files, 214 tests passed; Prettier clean.
2. BUILD SUCCESSFUL: ListScreensTest 16, FilePreviewLinksTest 5, WaitingDraftEmailTest 8, ProjectLinksToggleTest 5, ProjectsScreenTest 63, 0 failures. ktlint passed.
3. BUILD SUCCESSFUL: FilePreviewLinksRouteInAppTest 2/2, WaitingDraftEmailTest 8/8; ktlint passed (after joining one line that ktlint flagged in the new file).
4. BUILD SUCCESSFUL: 223 tests in 13 classes (the selection also matched `MailComposeRecipientsFilesScreenTest`, 6), 0 failures, 0 errors.

## Full test execution

Commands:

1. `npm run check` (web/: `api:check`, `lint`, `e2e:typecheck`, `test:ci`, `ng build`)
2. `npm run coverage` (web/, full Vitest suite with coverage)

Result:

Pass.
1. All steps passed, ending with "Application bundle generation complete" and the output location. There is one warning: the initial bundle exceeds its 500 kB budget. That warning existed before this task and does not fail the build.
2. 196 test files, 4810 tests passed (10:05:40 to 10:06:41).

The full Android unit suite (`testDebugUnitTest lintDebug`, about 9 minutes) was not rerun here. Android production code is unchanged since the fixer's full `sh scripts/check.sh` passed and since the previous test run of the full Android suite. This run only added tests, and those ran in the focused selection above.

## .taskcheck

Result:

Not run as one command. Its web part (`npm run check`) and the affected Android tests plus ktlint passed above. The server is not touched by this task. The parent runs `sh scripts/check.sh` before `done`.

## Bugs found

None. TW-1..TW-5 from earlier rounds are fixed. Their tests pass, and the new edge-case tests also pass on the final code.

Observation (not a finding, no failing test, for the parent to decide): on both clients, `emailIn` still keeps trailing `?`, `!` or `:` (e.g. "Did anna@example.org reply?" gives `anna@example.org?`). The requirement only says "an address found in waitingForText", and the fixer handled the full stop on purpose (TW-5). Other punctuation is not specified. If the parent sees it as the same class of defect, it is a follow-up for both regexes (`list-views.ts` `emailIn`, `WaitingScreen.kt` `EMAIL`). Severity would be Low: the web compose page drops an invalid To, and the Android mail app gets an invalid one.

## Coverage

Status: verified (web: full suite; Android: focused selection of the affected test classes)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: Android measured from JaCoCo line counters (`ci`/`mi`), intersected with the `+` lines of `git diff -U0 4a3afeb14 HEAD -- android/app/src/main/java`, counting only lines with instructions: 149/157 (94.9%). For web, a changed-line ratio was not measured. In `coverage-final.json`, no uncovered statement or branch location starts on a line the task added (list below).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: gtd-links-everywhere; base 4a3afeb14a46d69971ca600e515840d50072bdfa (given by the parent as the merge-base); measured HEAD 552099febf91af06cca83fba8b840e5968b5f8f8; file scope = `git diff --name-only 4a3afeb14 HEAD -- web/src android/app/src/main` without specs/tests.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 552099feb plus uncommitted tests, sha256 `list-views.spec.ts` 51a7655c..., `WaitingDraftEmailTest.kt` 699fbdd1..., `FilePreviewLinksRouteInAppTest.kt` 3b710608... (untracked). No production file is modified in the working tree.
- Exact included files: web `features/files/preview/preview-dialog.ts`, `features/gtd/lists/list-views.ts`, `features/gtd/lists/waiting-page.ts`, `features/gtd/projects/project-detail-page.ts`, `features/mail/compose/mail-compose-page.ts`; Android `files/FilePreviewView.kt`, `files/FilesScreen.kt`, `gtd/lists/WaitingScreen.kt`, `gtd/projects/ProjectsScreen.kt`. Excluded: `preview-dialog.html/.scss`, `waiting-page.html`, `project-detail-page.html` (templates and styles are not instrumented by v8; they are exercised by the component specs), `messages.xlf`/`messages.de.xlf` and the four Android `strings_*.xml` (resources, no executable code; checked by `ng build` de and German tests). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: web: Vitest 5.0.5 + `@vitest/coverage-v8`, `npm run coverage` in `web/`, full suite. Android: JaCoCo via AGP `enableUnitTestCoverage`, the final focused command 4 above in `android/` (13 test classes, 223 tests).
- Run start/completion time and exit status: web 10:05:40 to 10:06:41 +01:00, all tests passed, reports written. Android 10:11:3x to 10:12:14 +01:00, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 ebd9a780a63d1ebdf7daf097c1ace783755df3c06abd3986b226e60e9aa63947, written 10:06:41), `web/coverage/web/coverage-summary.json`; `android/app/build/reports/coverage/test/debug/report.xml` (sha256 eab55ff7d078b9ffaf0eb7a868b2a703e065a3c7a1d0f39ed5e5f4a44e898b24, written 10:12:14).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Both reports were rewritten by these runs, and the test-result timestamps (09:11-09:12Z) match the Android run. The web spec was edited before the web coverage run. No production file changed during the runs; the HEAD move only touched `tasks/` and `reviews/`.
- Final run after latest test additions and production fixes: yes. The fixer's fixes are in the measured HEAD, and both coverage runs happened after the last test edit of their part.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Android "Statements" = JaCoCo INSTRUCTION, "Functions" = METHOD. The Android numbers come from the focused selection, so for old code in these files they are a lower bound.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/files/preview/preview-dialog.ts` | 66/66 (100%) | 36/39 (92.3%) | 22/22 (100%) | 48/48 (100%) |
| `web/src/app/features/gtd/lists/list-views.ts` | 47/47 (100%) | 26/26 (100%) | 16/16 (100%) | 36/36 (100%) |
| `web/src/app/features/gtd/lists/waiting-page.ts` | 32/32 (100%) | 19/21 (90.5%) | 12/12 (100%) | 24/24 (100%) |
| `web/src/app/features/gtd/projects/project-detail-page.ts` | 188/190 (98.9%) | 88/97 (90.7%) | 48/48 (100%) | 164/164 (100%) |
| `web/src/app/features/mail/compose/mail-compose-page.ts` | 291/294 (99.0%) | 143/161 (88.8%) | 84/84 (100%) | 248/248 (100%) |
| Web aggregate (5 files) | 624/629 (99.2%) | 312/344 (90.7%) | 182/182 (100%) | 520/520 (100%) |
| `android/.../files/FilesScreen.kt` | 8229/8416 (97.8%) | 296/337 (87.8%) | 164/194 (84.5%) | 620/627 (98.9%) |
| `android/.../files/FilePreviewView.kt` | 3730/3762 (99.1%) | 100/106 (94.3%) | 73/73 (100%) | 287/289 (99.3%) |
| `android/.../gtd/projects/ProjectsScreen.kt` | 4201/4464 (94.1%) | 154/182 (84.6%) | 82/112 (73.2%) | 351/357 (98.3%) |
| `android/.../gtd/lists/WaitingScreen.kt` | 981/1042 (94.1%) | 62/78 (79.5%) | 18/20 (90.0%) | 80/84 (95.2%) |
| Android aggregate (4 files) | 17141/17684 (96.9%) | 612/703 (87.1%) | 337/399 (84.5%) | 1338/1357 (98.6%) |

Aggregates are summed counters over the files listed; the excluded templates, styles and resources are named above. No scoped file is missing from the reports.

Android changed-line coverage per file: FilesScreen.kt 51/51 (was 2/51 before the new in-app test), FilePreviewView.kt 49/50, ProjectsScreen.kt 20/24, WaitingScreen.kt 29/32.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `FilesScreen.kt:176-227` (linked item replaces the files, Back returns, `linksPanel` wiring) | AC 2 on Android; the earlier CR-2 fix (panel state survives a linked item) | Covered now by `FilePreviewLinksRouteInAppTest` (2 tests); 51/51 changed lines |
| `FilesScreen.kt:223` (1 of 2 branches: a non-`InApp` target) | A linked item without an Android screen opens the web/browser path inside `LinkedItemsSection` and never reaches this lambda | Justified gap: `linkedItemTarget` handles `Web` targets before `open` is called. The `else` branch is defensive |
| `ProjectsScreen.kt:383-385, 436-437` (linked item opens in place of the project; Back) | AC 1 on Android: opening a linked item from the project panel | Not tested: needs the Projects route in the Hilt activity with fake `ProjectRepository`/`TaskListsRepository`/`ProjectWorkRepository` (Room-backed cache flows), much more setup than the Files route. The same pattern is tested for Files (`FilePreviewLinksRouteInAppTest`) and notes. Follow-up candidate, see "Unable to test" |
| `WaitingScreen.kt:55-61` (`WaitingRoute` draftMail lambda: launch the intent, "no mail app" toast) | AC 3 on Android: the intent is actually started, toast without a mail app | Not tested in a route test (WaitingViewModel needs the Room-backed task cache). The intent itself (`followUpMailIntent`, `followUpMailUri`) and the button wiring are unit-tested in `WaitingDraftEmailTest`/`ListScreensTest`. `ContactIntents.launch` is shared code that is tested elsewhere |
| `FilePreviewView.kt:197`, branches `:143` (1 of 4), `:188` (1 of 2) | Line 197 is the closing brace of the exhaustive `when` (a JaCoCo artefact for moved code). Branch 143 is `linksPanel == null` while `showLinks` is true. That state cannot happen without the toggle, which `FilePreviewLinksTest` tests | Non-material |
| `preview-dialog.ts:65,69,77` (falsy `file` branches) | No file at the index: code that was there before this task | Non-material, outside the task's changes |
| `waiting-page.ts:33` (decorator metadata branch), `:61` (`?? 0` with no waitingSince) | Code that was there before this task | Non-material |
| `project-detail-page.ts` (9 branches, statements at 189 and 349), `mail-compose-page.ts` (18 branches, statements at 157, 357 and 518) | Code that was there before this task; the task added only the `LinkedItems` import and the prefill lines 383-386, which have no uncovered statement or branch | Outside the task's changes |

## Unable to test

- Android project detail: the linked-items panel actually rendered inside a project, and opening a linked item from it (Back returns with the panel open). Reason: rendering needs the Projects route in a Hilt activity with fakes for the Room-backed GTD repositories. That setup is substantial, and no in-app Projects test with fake data exists to copy. The toggle and its state are tested (`ProjectLinksToggleTest`), and the same code pattern is tested end to end for Files. Follow-up: recommended (a `ProjectsRouteInAppTest` with fake GTD repositories), not blocking.
- Android `WaitingRoute` starting the mail app or showing the "no mail app" toast: same reason (Hilt route with the task cache). The intent contents are unit-tested. Follow-up: optional.
- Android Draft email using the linked waiting-for contact: deferred by the parent to `and-waiting-contact-mail`. Not part of this task.
- Real browser and device behavior (Playwright, emulator) is not part of the acceptance criteria and was not run.

## Summary

Tests added and all validation passed. The final code passes `npm run check` and the full web suite with coverage (4810 tests), and 223 focused Android tests plus ktlint. I found no production defects. The new tests pin the TW-5 regex edge cases on both clients and add Android in-app tests for the file preview's real linked-items panel and its linked-item navigation, which raised FilesScreen.kt changed-line coverage from 2/51 to 51/51. Remaining gaps: the Android project panel's linked-item navigation and the WaitingRoute intent launch are not tested in a route test. They are documented above, and neither blocks completion.
