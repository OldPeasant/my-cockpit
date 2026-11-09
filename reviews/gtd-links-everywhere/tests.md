# Test Report

Date: 2026-11-09

Task: gtd-links-everywhere - Linked items on projects and files; follow-up by mail (web + Android)

Scope: `git diff 9453c6284581d85d7b0fab7c92fcf28e9931aff0..b2068fad9ef3cf06203ab2bc9e5abdf6ba259b14 -- web android` (task base = claim commit 9453c6284; implementation revision b2068fad9). Measured at HEAD e9cb94f33065437bd26b3eaabc951951dd7827fc; the commits after b2068fad9 are 50ccac4f886e80d0cd77d9e79c36e1e5eaa95226 (re-extracts `web/src/locale/messages.xlf` only, checked with `git diff --stat b2068fad9 50ccac4f8`) and e9cb94f33 (`reviews/gtd-links-everywhere/review.md` only). No production code changed after b2068fad9. Uncommitted inputs: the two new Android test files listed below.

Requirements:
- tasks/gtd-links-everywhere.md acceptance criteria and recorded assumptions
- SPEC.md §4.3 (linked items on every detail view), §5.3 (waiting follow-up)
- docs/ARCHITECTURE.md "Coverage" and "Code conventions" (Android lint must pass)
- reviews/gtd-links-everywhere/review.md CR-1 (missing Android tests for the project and file-preview toggles), as passed on by the parent

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/files/FilePreviewLinksTest.kt` (new, this run)
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/projects/ProjectLinksToggleTest.kt` (new, this run)
- Earlier test-writer run, already committed in the task: `web/src/app/features/gtd/lists/list-views.spec.ts`, `web/src/app/features/gtd/lists/list-pages.spec.ts`, `web/src/app/features/files/preview/preview-dialog.spec.ts`, `web/src/app/features/gtd/projects/projects-states.spec.ts`, `web/src/app/features/mail/compose/mail-compose-page.spec.ts`; the task also added cases to `android/.../gtd/lists/ListScreensTest.kt`.

## Tests added or updated

Count: 10 added in this run (5 in `FilePreviewLinksTest`, 5 in `ProjectLinksToggleTest`). No existing test changed.

- `FilePreviewLinksTest` (fake panel passed in the `linksPanel` slot of `FilesContent`):
  - "without a links panel the preview has no links toggle"
  - "the toggle shows the file's linked items instead of its content, and hides them again" (the panel is not composed until toggled; content description switches Show/Hide; name and neighbour bar stay; no callbacks fired)
  - "stepping to the next file while the links are shown shows that file's links"
  - "the links panel also replaces a failed preview, and hiding it brings the retry back"
  - "in German, the links toggle is German"
- `ProjectLinksToggleTest`:
  - "the project detail offers its linked items, and the toggle switches between show and hide" (the tasks stay visible)
  - "without a Hilt activity no linked-items panel is shown, even when toggled on"
  - "another project opens with its linked items hidden" (`rememberSaveable(project.id)`)
  - "the project list has no linked-items toggle"
  - "in German, the toggle is German"

Limitation: `ProjectDetail` calls `LinkedItemsSection` directly. It has no `linksPanel` slot, unlike the files preview. Outside a Hilt activity `LinkedItemsSection` composes nothing, and there is no Hilt test activity in the test sources (only `MainActivity`). So no Android unit test can show a fake or real panel on the project detail without changing production code. The tests check the toggle, its labels, that it resets per project, and that no panel appears without Hilt. The panel's own behavior is covered by the existing `core/links/LinkedItemsPanelTest`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Android project detail shows the linked-items panel | `ProjectLinksToggleTest` (5) | `./gradlew ktlintCheck testDebugUnitTest --tests '...FilePreviewLinksTest' --tests '...ProjectLinksToggleTest'` (android/) | Pass (toggle only. Showing the panel needs Hilt, see limitation) |
| Android project detail: existing layout tests still pass | `ProjectsScreenTest`, `ProjectTaskListTest`, `FabClearanceTest` | `./gradlew testDebugUnitTest` (full) and a rerun of those classes | **Fail**: 3 regressions, TW-2 |
| Android file preview shows the linked-items panel | `FilePreviewLinksTest` (5) | same focused command | Pass |
| Web project detail shows the panel | `projects-states.spec.ts` "shows the linked-items panel of the project (SPEC.md §4.3)" | `npm --prefix web run check` | Pass |
| Web file preview shows the panel | `preview-dialog.spec.ts` "linked items" (2) | same | Pass |
| Web waiting "Draft email" (To from contact / text / empty, subject from task) and compose `?to=`/`?subject=` prefill | `list-views.spec.ts`, `list-pages.spec.ts` "Waiting For > Draft email", `mail-compose-page.spec.ts` prefill (2) | same | Pass |
| Android waiting "Draft email" | `ListScreensTest` "waiting offers a draft email per item" | `./gradlew testDebugUnitTest` | Pass |
| Android "Draft email" takes the address from the waiting-for text | `ListScreensTest` "follow-up mail takes the address from the waiting-for text and the subject from the task" (added by the task) | `./gradlew testDebugUnitTest` | **Fail**: TW-1 |
| New strings EN/DE | German tests in both new Android classes; web `ng build` (de xlf) | focused Android command; `npm --prefix web run check` | Pass |
| Android lint passes (part of the `sh scripts/check.sh` contract) | `lintDebug` | `./gradlew ktlintCheck lintDebug` | **Fail**: TW-3 (ktlint passes) |
| `sh scripts/check.sh` passes | - | not run as one command. Its Android unit tests and lint fail, see above | Not run as a whole (would fail on TW-1..3) |

## Focused test execution

Command:

`./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.files.FilePreviewLinksTest' --tests 'ch.sonensei.cockpit.gtd.projects.ProjectLinksToggleTest'` (in `android/`)

Result:

Pass: ktlint clean, 5/5 + 5/5 tests (from the JUnit XML results: 0 failures, 0 errors, 0 skipped).

## Full test execution

Command:

1. `npm --prefix web run check` (runs in `web/`: api:check, lint, e2e typecheck, `ng test --watch=false`, `ng build`)
2. `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (in `android/`, started 05:13:17, ended 05:20:31, exit 1)
3. Rerun of the failing classes: `./gradlew testDebugUnitTest --tests '...ProjectTaskListTest' --tests '...ProjectsScreenTest' --tests '*FabClearanceTest' --tests '...ListScreensTest'` (fails again the same way, so the failures are deterministic)
4. `./gradlew ktlintCheck lintDebug` (exit 1, because step 2 stopped before lint)

Result:

- Web: Pass (exit 0). 196 test files, 4787 tests passed. Lint and build OK.
- Android unit tests: **Fail**. 5261 tests, 4 failed:
  - `ListScreensTest` > follow-up mail takes the address from the waiting-for text and the subject from the task (TW-1)
  - `ProjectTaskListTest` > a drag the system cancels is not saved, and the next drag starts from the state's order (TW-2)
  - `ProjectsScreenTest` > project tasks on other lists say which list they are on (TW-2)
  - `FabClearanceTest` > on a project's detail in the shell, the last task's make-next-step button lies above the FAB (TW-2)
- Android ktlint: Pass. Android lint: **Fail**, 1 error (TW-3).

## .taskcheck

Result:

Not run as a whole (`sh scripts/check.sh`). Its Android parts were run separately above and fail (unit tests and lintDebug), so it would fail until TW-1..TW-3 are fixed. The web part passes.

## Bugs found

### Draft email address keeps the parentheses around it

Finding ID:

`TW-1`

Test:

`ListScreensTest` > "follow-up mail takes the address from the waiting-for text and the subject from the task"

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/WaitingScreen.kt` (`EMAIL` regex / `emailIn`, about lines 114-117)

Requirement:

AC 3: Waiting "Follow up" offers "Draft email" with the To field prefilled from the waiting-for text. The function's own KDoc says it returns `anna@example.org` for "Anna (anna@example.org)".

Expected behavior:

`emailIn("Anna (anna@example.org)") == "anna@example.org"`, and the mailto/EXTRA_EMAIL use that address.

Actual behavior:

It returns `(anna@example.org)`. The regex character class `[^\s<>"',;]` does not exclude `(` and `)`, so the To address is invalid (`expected:<[anna@example.org]> but was:<[(anna@example.org)]>`).

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### The project detail's links toggle breaks three existing layout tests

Finding ID:

`TW-2`

Test:

`ProjectsScreenTest` > "project tasks on other lists say which list they are on"; `ProjectTaskListTest` > "a drag the system cancels is not saved, and the next drag starts from the state's order"; `FabClearanceTest` > "on a project's detail in the shell, the last task's make-next-step button lies above the FAB"

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/gtd/projects/ProjectsScreen.kt:417-422`. The new full-width `TextButton` row (LINKS_TOGGLE) sits between the next-step line and the task list.

Requirement:

AC 5 (unit tests and `sh scripts/check.sh` pass), plus the existing behavior those tests protect: the task rows are visible, drag-to-reorder still saves after a cancelled drag, and the last row clears the FAB.

Expected behavior:

The existing tests still pass after the toggle is added.

Actual behavior:

All three fail deterministically at HEAD (confirmed by a rerun). Since base 9453c6284 the only Android production change on the project detail is the toggle and its panel, so these failures come from that change. The extra row makes the task list shorter on the Robolectric screen: "Someday" is no longer displayed; the second drag saves nothing (`expected:<[reorder [...]]> but was:<[]>`); the last row ends at y=357 below the FAB top y=318. The FAB case may also be a real UX problem on small screens. The fixer should decide whether to move the toggle so it no longer takes height from the list (for example an icon in the header row, or inside the scrolling list) or to adjust the layout another way. Don't weaken the tests.

Severity:

Medium (blocks `.taskcheck`)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

### Android lint error in the Draft email intent

Finding ID:

`TW-3`

Test:

`./gradlew lintDebug` (part of `.taskcheck`)

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/WaitingScreen.kt:125`

Requirement:

AGENTS.md / ARCHITECTURE.md: lint is part of the contract (`ktlintCheck testDebugUnitTest lintDebug` must pass); AC 5.

Expected behavior:

lintDebug reports 0 errors.

Actual behavior:

`Error: Use the KTX extension function String.toUri instead? [UseKtx]` on `Uri.parse(followUpMailUri(task))`. Lint reports 1 error, 0 warnings.

Severity:

Medium (blocks `.taskcheck`)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial. Web verified. Android partial-failed: the full Android coverage run stopped on the failing tests (TW-1/TW-2), so the numbers below come from a diagnostic run over a passing subset of tests.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: gtd-links-everywhere; base 9453c6284581d85d7b0fab7c92fcf28e9931aff0; implementation b2068fad9ef3cf06203ab2bc9e5abdf6ba259b14; measured HEAD e9cb94f33065437bd26b3eaabc951951dd7827fc (on top of b2068fad9: 50ccac4f8 xlf only and e9cb94f33 review only). Scope given by the parent and checked with `git diff --stat 9453c6284 HEAD -- android/app/src/main android/app/src/test` and `-- web`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD e9cb94f33 + untracked `FilePreviewLinksTest.kt` (sha256 prefix c684f62ad292d86f) and `ProjectLinksToggleTest.kt` (sha256 prefix d61867063147a3a7). Hashes taken before and after the runs and unchanged.
- Exact included files: web `src/app/features/files/preview/preview-dialog.ts`, `features/gtd/lists/list-views.ts`, `features/gtd/lists/waiting-page.ts`, `features/gtd/projects/project-detail-page.ts`, `features/mail/compose/mail-compose-page.ts`; Android `files/FilePreviewView.kt`, `files/FilesScreen.kt`, `gtd/lists/WaitingScreen.kt`, `gtd/projects/ProjectsScreen.kt`. Excluded: web `.html`/`.scss` (counted through their components), xlf and Android `res/values*` strings (not executable).
- Provider/version, command, working directory, and test selection:
  - Web: Vitest + @vitest/coverage-v8, `npm --prefix web run coverage` (runs in `web/`), full suite.
  - Android: JaCoCo via AGP `enableUnitTestCoverage`, run in `android/`. The full-suite attempt `./gradlew ... testDebugUnitTest ... createDebugUnitTestCoverageReport` failed (exit 1, no report). The diagnostic run was `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.files.*' --tests '...gtd.projects.ProjectLinksToggleTest' --tests '...gtd.projects.ProjectsRouteTest' --tests '...gtd.projects.ProjectsScreensInAppTest' --tests '...gtd.projects.ProjectEditingTest' --tests '...gtd.lists.ListScreensTest.waiting*' --tests '...gtd.lists.ListRoutesTest' createDebugUnitTestCoverageReport`. It leaves out `ProjectsScreenTest`, `ProjectTaskListTest`, `FabClearanceTest` and the failing `ListScreensTest` case, so it understates `ProjectsScreen.kt` and `WaitingScreen.kt` coverage.
- Run start/completion time and exit status: web 05:15:24-05:17:11, exit 0 (4787 passed). Android diagnostic 05:22:09-05:22:42, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 prefix 66fa1b2b8efc0215, written 05:17:10); `android/app/build/reports/coverage/test/debug/report.xml` (sha256 prefix 95fecfad5265b32b, written 05:22:42).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Both reports were written by these runs, after all test additions, and the snapshot did not change.
- Final run after latest test additions and production fixes: yes for this revision. No production fixes yet: after the fixer resolves TW-1..TW-3, this coverage is stale.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/.../files/preview/preview-dialog.ts` | 63/63 (100%) | 36/39 (92.3%) | 20/20 (100%) | 45/45 (100%) |
| `web/.../gtd/lists/list-views.ts` | 47/47 (100%) | 26/26 (100%) | 16/16 (100%) | 36/36 (100%) |
| `web/.../gtd/lists/waiting-page.ts` | 24/24 (100%) | 17/19 (89.5%) | 10/10 (100%) | 18/18 (100%) |
| `web/.../gtd/projects/project-detail-page.ts` | 188/190 (98.9%) | 88/97 (90.7%) | 48/48 (100%) | 164/164 (100%) |
| `web/.../mail/compose/mail-compose-page.ts` | 291/294 (99.0%) | 143/161 (88.8%) | 84/84 (100%) | 248/248 (100%) |
| Web aggregate (summed) | 613/618 (99.2%) | 310/342 (90.6%) | 178/178 (100%) | 511/511 (100%) |
| `android/.../files/FilePreviewView.kt` (diagnostic subset) | instr. 3730/3762 (99.1%) | 100/106 (94.3%) | 73/73 (100%) | 287/289 (99.3%) |
| `android/.../files/FilesScreen.kt` (diagnostic subset) | instr. 8056/8415 (95.7%) | 297/339 (87.6%) | 159/193 (82.4%) | 611/624 (97.9%) |
| `android/.../gtd/projects/ProjectsScreen.kt` (diagnostic subset, understated) | instr. 3710/4489 (82.6%) | 119/184 (64.7%) | 69/112 (61.6%) | 319/354 (90.1%) |
| `android/.../gtd/lists/WaitingScreen.kt` (diagnostic subset, understated) | instr. 923/1040 (88.8%) | 61/78 (78.2%) | 15/20 (75.0%) | 75/84 (89.3%) |
| Android aggregate (summed, diagnostic subset only) | instr. 16419/17706 (92.7%) | 577/707 (81.6%) | 316/398 (79.4%) | 1292/1351 (95.6%) |

Android "Statements" are JaCoCo INSTRUCTION counters and "Functions" are METHOD counters. No Android file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ProjectsScreen.kt:380-381, 427-428` | Opening a linked item from the project panel (SearchItemScreen replaces the detail, Back returns) | Not unit-testable without a Hilt activity: `LinkedItemsSection` composes nothing outside Hilt and the detail has no panel slot. Justified gap. A follow-up could add a slot like the files preview has |
| `FilesScreen.kt:175-176, 218-221` | FilesRoute: the linked item opened from the preview replaces the files; the real `LinkedItemsSection(FILE)` wiring | Hilt route only. The toggle and slot are covered by `FilePreviewLinksTest`. Justified gap |
| `WaitingScreen.kt:57-60` | WaitingRoute's draftMail: launch the intent, or toast "no mail app" (CR-3) | Hilt route only, not reached by `WaitingContent` tests. Gap left; the intent itself is covered by `ListScreensTest` (currently failing on TW-1) |
| `WaitingScreen.kt:117-127` | `emailIn` / `followUpMailUri` / `followUpMailIntent` | Covered by the full-suite `ListScreensTest` case, which fails on TW-1. That case was left out of the diagnostic run |
| `FilePreviewView.kt:197, 438` | Pre-existing preview code (not the links toggle) | Outside the task's change |
| Web files, remaining branches | Pre-existing or template/defensive branches | Not chased. All lines covered |

## Unable to test

- Project detail linked-items panel content and opening a linked item on Android: blocked by the hard-wired Hilt-only `LinkedItemsSection` (no slot, no Hilt test activity). Follow-up is optional: add a `linksPanel` slot to `ProjectsContent`, as `FilesContent` has.
- Android `WaitingRoute` "no mail app" toast (CR-3): Hilt route only; not covered.
- `sh scripts/check.sh` as one command: not run; it would fail on TW-1..TW-3.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. The new Android toggle tests pass (10/10) and the full web check passes. The full Android suite has 4 failures (TW-1: email regex keeps parentheses; TW-2: the project links toggle breaks three existing project layout tests) and lintDebug has 1 error (TW-3). Once these are fixed, rerun the test-writer and code-reviewer and take fresh coverage.
