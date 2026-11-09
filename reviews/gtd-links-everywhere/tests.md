# Test Report

Date: 2026-11-09

Task: gtd-links-everywhere - Linked items on projects and files; follow-up by mail (web + Android)

Scope: base c46d3f72e3a34da1322e3a9b0ac71be454a725e2 .. HEAD 3c08648c05a120d1b91312aba5ec7c268d20a6cb (task commits 241f7a769, fec367b90, bcbc79893, 3c08648c0), `web/` and `android/` only, plus the uncommitted test changes listed below.

Requirements:
- tasks/gtd-links-everywhere.md acceptance criteria and recorded assumptions
- SPEC.md §4.3 (linked items on every detail view), §5.3 (waiting follow-up)
- docs/ARCHITECTURE.md "Coverage"
- Parent agent instructions

## Test files changed

- `web/src/app/features/gtd/lists/list-views.spec.ts`
- `web/src/app/features/gtd/lists/list-pages.spec.ts`
- `web/src/app/features/files/preview/preview-dialog.spec.ts`
- `web/src/app/features/gtd/projects/projects-states.spec.ts`
- `web/src/app/features/mail/compose/mail-compose-page.spec.ts`

## Tests added or updated

Count: 17 added (emailIn: 3 incl. a 5-case `it.each`; followUpMail: 3; waiting Draft email: 4; preview links: 2; project linked items: 1; compose prefill: 2), 1 updated.

Updated: `projects-states.spec.ts` "an emptied description is cleared on the server" used the selector `.block .empty` and took the last match; the new `<app-linked-items class="block">` now renders its own `.empty` text after the description section, so the test read the panel's text. Selector narrowed to `section.block .empty` (the description/tasks sections); the assertion itself ('Note the outcome') is unchanged. This was a fragile test selector, not a production defect.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Web project detail shows the linked-items panel | `projects-states.spec.ts` "shows the linked-items panel of the project (SPEC.md §4.3)" (listLinks called for PROJECT p1, empty state shown); existing assertion in "shows an error when the tasks cannot be loaded" | `npx ng test --watch=false --include <focused dirs>` | Pass |
| Android project detail shows the linked-items panel | none added | - | Not run: Android Gradle commands were denied in this sub-agent session |
| Web file preview shows the linked-items panel | `preview-dialog.spec.ts` "linked items > shows the file's linked items beside it on demand and hides them again", "leaves arrow keys typed in the linked-items panel to the panel" | same | Pass |
| Android file preview shows the linked-items panel | none added | - | Not run: Android Gradle commands were denied |
| Waiting "Draft email" on web: To from linked contact, else address in waiting-for text, else empty; subject from task | `list-views.spec.ts` emailIn (3), followUpMail (3); `list-pages.spec.ts` "Waiting For > Draft email" (contact's first address wins; contact without email falls back to text; contact lookup failure falls back to text; plain name leaves To out and makes no contact call) | same | Pass |
| Compose accepts `?to=` / `?subject=` prefill | `mail-compose-page.spec.ts` "starts a new mail with To and subject from ?to= and ?subject=" (trimmed To reaches the created draft), "leaves To empty for a blank ?to= and keeps ?subject=" | same | Pass |
| Waiting "Draft email" on Android | existing `ListScreensTest` test added by the task | - | Not run here (Gradle denied) |
| New strings EN/DE | not unit-testable beyond the build; xlf extraction is checked by `npm run check` / `ng build` | - | Not run by test-writer |
| `sh scripts/check.sh` passes | - | - | Not run by test-writer (parent runs `.taskcheck`) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/gtd/lists --include src/app/features/files/preview --include src/app/features/mail/compose/mail-compose-page.spec.ts --include src/app/features/gtd/projects`

Result:

Pass - 14 files, 318 tests (first run: 1 failure in the pre-existing description test, fixed by the selector scoping described above).

## Full test execution

Command:

`cd web && npm run coverage` (full Vitest suite with coverage)

Result:

Pass - 196 files, 4753 tests. Android: Not run (`./gradlew testDebugUnitTest ...` was denied by the sandbox permissions of this sub-agent).

## .taskcheck

Result:

Not run (left to the parent worker).

## Bugs found

None.

## Coverage

Status: partial - web verified; Android unavailable (Gradle commands denied in this session).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: gtd-links-everywhere; base c46d3f72e3a34da1322e3a9b0ac71be454a725e2; HEAD 3c08648c05a120d1b91312aba5ec7c268d20a6cb; commits and scope supplied by the parent and checked with `git diff --stat c46d3f72e..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 3c08648c0 + uncommitted test diff (`git diff | sha256sum` prefix e3243791f37f5811, the five spec files above).
- Exact included files: web `features/files/preview/preview-dialog.ts`, `features/gtd/lists/list-views.ts`, `features/gtd/lists/waiting-page.ts`, `features/gtd/projects/project-detail-page.ts`, `features/mail/compose/mail-compose-page.ts`. Excluded: `.html`/`.scss` templates (covered via the components, no separate counters), xlf locale files (not executable), Android sources `files/FilePreviewView.kt`, `files/FilesScreen.kt`, `gtd/lists/WaitingScreen.kt`, `gtd/projects/ProjectsScreen.kt` - unmeasured, not excluded by choice (Gradle denied).
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8, `npm run coverage`, `web/`, full suite.
- Run start/completion time and exit status: started 2026-11-09 02:25:13 local, report written 02:29:18; all tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 prefix 7bbae1b3d74afe03).
- Evidence outputs are fresh and inputs stayed unchanged: yes, run after all test edits; no edits during the run.
- Final run after latest test additions and production fixes: yes (no production fixes made).

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/files/preview/preview-dialog.ts` | 63/63 (100%) | 36/39 (92.3%) | 20/20 (100%) | 45/45 (100%) |
| `web/src/app/features/gtd/lists/list-views.ts` | 47/47 (100%) | 26/26 (100%) | 16/16 (100%) | 36/36 (100%) |
| `web/src/app/features/gtd/lists/waiting-page.ts` | 24/24 (100%) | 17/19 (89.47%) | 10/10 (100%) | 18/18 (100%) |
| `web/src/app/features/gtd/projects/project-detail-page.ts` | 188/190 (98.94%) | 88/97 (90.72%) | 48/48 (100%) | 164/164 (100%) |
| `web/src/app/features/mail/compose/mail-compose-page.ts` | 252/254 (99.21%) | 120/134 (89.55%) | 68/68 (100%) | 216/216 (100%) |
| Web aggregate (summed) | 574/578 (99.31%) | 287/315 (91.11%) | 162/162 (100%) | 479/479 (100%) |
| Android (4 files listed above) | N/A - not measured | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| web files above, remaining branches | All lines are covered; remaining branch gaps are in pre-existing code of these files or template-generated/defensive branches (e.g. `?.` on the event target) | Not chased; the new behavior (toggle, keys inside `.links`, contact lookup and fallbacks, prefill) is covered by the tests above |
| Android `ProjectsScreen.kt` LinkedItemsSection, `FilePreviewView.kt`/`FilesScreen.kt` links toggle, `WaitingScreen.kt` Draft email | Acceptance criteria 1-3 on Android | No tests added and no coverage: Android Gradle commands denied in this session. Follow-up: parent runs `cd android && ./gradlew testDebugUnitTest createDebugUnitTestCoverageReport` and adds Compose tests for the projects section and the preview toggle (PreviewTags.LINKS_TOGGLE / LINKS) |

## Unable to test

- Android project linked-items section and file preview links toggle: no tests added, Android focused tests and coverage not run (permission denied for `./gradlew`). Needs follow-up by the parent.
- Android waiting Draft email: has a task-added test in `ListScreensTest`, not executed by me.
- `sh scripts/check.sh` (.taskcheck): left to the parent.

## Summary

Meaningful task behavior remains untested and requires follow-up: web tests added and the full web suite passes with task files at 100% line coverage; no production defects found; Android tests and coverage could not be run in this session.
