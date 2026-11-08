# Test Report

Date: 2026-11-08

Task: web-back-navigation - Web back buttons return to where the user came from

Scope: `git diff 9a29669abe86beff6ba450f28a4435ba4087f0f3..e5322d8c24844b6b68a3f8beae84122e48bcf1f0 -- web/`
(branch `main`, two task commits: a63203aff "web: BackNavigation service and back button; mail read
view goes back to its origin", e5322d8c2 "web: back buttons of task, project, note, contact and
bookmark pages return to their origin"). Base and commit list supplied by the parent worker and
confirmed with `git log 9a29669a..HEAD`. No uncommitted production changes; the only uncommitted
changes are the test files listed below.

Requirements:
- `tasks/web-back-navigation.md` acceptance criteria and recorded assumptions (summarized by the parent)
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Coverage"
- Existing test conventions in the changed spec files

## Test files changed

- `web/src/app/shared/navigation/back-navigation.spec.ts`
- `web/src/app/features/gtd/task-detail/task-detail.spec.ts`
- `web/src/app/features/contacts/contact-detail.spec.ts`
- `web/src/app/features/bookmarks/bookmark-reader.spec.ts`
- `web/src/app/features/notes/editor/note-editor-page.spec.ts`
- `web/src/app/features/gtd/projects/projects-pages.spec.ts`

## Tests added or updated

Count: 27 added (no existing test changed, weakened or skipped; the test-only `Host` component in
`task-detail.spec.ts` gained a `backLabel` signal bound to the new input, default `null`, so the
existing tests behave as before).

- `back-navigation.spec.ts` (14): deepest route title wins (nested routes); a guard-cancelled
  navigation is not recorded; a popstate entry without router state starts afresh; a page that sets
  its name after being left renames `previous()` reactively (query/fragment ignored); a renamed path
  moves to the most recent names; exactly 100 names are kept; several `back()` calls walk the whole
  way back, then go to the parent route; back button updates its name/aria/href when the previous
  page sets its name; back button names the previous page again after Back and Forward; meta, shift,
  alt and middle-button clicks are left to the browser (4 cases); a plain click is taken over
  (`defaultPrevented`) and goes to the parent route.
- `task-detail.spec.ts` (3): with `backLabel` the close button shows the label, has aria-label
  "Back to Today", class `back` instead of `icon`, and still emits `closed`; without it, it is the
  icon button with aria-label "Close" and no label span; dropping the label returns to "Close".
- `contact-detail.spec.ts` (2): deep link shows "Contacts" / "Back to Contacts" and the click keeps
  `?q=`; a page opened from the contact (`/contacts/new`) has `previous()` labelled with the contact's
  display name and its back button reads it and links to `/contacts/{id}`.
- `bookmark-reader.spec.ts` (3): deep link names "Bookmarks"; a bookmark opened from another bookmark
  shows the first one's title on its back button and links to it; a bookmark with an empty
  `displayTitle` sets no name, so the route title "Bookmarks" stays.
- `note-editor-page.spec.ts` (3): deep link names "Notes" and links to `/notes`; leaving the note for
  another page leaves `previous()` labelled with the note title; an untitled note sets no name.
- `projects-pages.spec.ts` (2): deep link names "Projects" and links to `/projects`; the page calls
  `BackNavigation.setLabel('/projects/p1', 'Tax return')` (this spec mocks `ActivatedRoute` and does
  not navigate, so a spy on the real service was used).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 shared helper: history back when the previous entry is in the app, parent route otherwise | `BackNavigation` describe (existing + new: guard-cancelled, no router state, whole way back, plain click) | `npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts` | Pass |
| AC2 label names the destination or reads "Back" | existing back button tests; new: deepest title, set-name updates, name after Back/Forward, TaskDetail `backLabel` tests, contact/bookmark/note/project deep-link and set-name tests | focused commands below and `npm run coverage` | Pass |
| AC3 used by mail read, task detail, project, note, contact (and bookmark reader) | mail-read-page.spec.ts (existing), task-detail-page.spec.ts (existing), new tests in projects-pages, note-editor-page, contact-detail, bookmark-reader specs | `npm run coverage` (full suite) | Pass |
| AC4 Archive/Spam/Delete return to the origin | existing `MailReadPage Archive, Spam and Delete: back to where it was opened from` | `npm run coverage` (full suite) | Pass |
| AC5 opened from New returns to New with its filter; deep link goes to parent route | existing `goes back in the history...`, `names the previous page and links to it`, mail-read `names New and returns to it with its filter when opened from New`, `reads Mail and goes to the mail list when opened directly` | `npm run coverage` (full suite) | Pass |
| AC6 new UI strings in `web/src/locale` (EN + DE) | Not a unit-test concern; texts "Back", "Back to {destination}" asserted in English in the tests above. Locale files not inspected by tests here | n/a | Not testable by unit tests (parent: `npm run check` covers i18n build) |
| AC7 unit tests cover the new behavior; `npm run check` and `sh scripts/check.sh` pass | all of the above | `npm run coverage`, `npm run lint`, prettier | Pass for web tests and lint; `npm run check` and `sh scripts/check.sh` Not run: see below |

## Focused test execution

Commands (run in `web/`):

- `npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts --include src/app/features/bookmarks/bookmark-reader.spec.ts` -> Pass (2 files, 50 tests)
- `npx ng test --watch=false --include src/app/features/gtd/task-detail/task-detail.spec.ts` -> Pass (98 tests)
- `npx ng test --watch=false --include src/app/features/contacts/contact-detail.spec.ts` -> Pass (45 tests)
- `npx ng test --watch=false --include src/app/features/notes/editor/note-editor-page.spec.ts` -> Pass (79 tests)
- `npx ng test --watch=false --include src/app/features/gtd/projects/projects-pages.spec.ts` -> Pass (16 tests)
- `npx prettier --check` on the six spec files -> Pass ("All matched files use Prettier code style!")
- `npm run lint` -> Pass ("All files pass linting.")

Result:

Pass

The output shows "Not implemented: navigation to another Document" five times: jsdom's message
when a modified click is deliberately not prevented and the anchor's default action runs. It is
expected (the tests assert `defaultPrevented === false`) and is not a failure.

## Full test execution

Command:

`cd web && npm run coverage` (the full Vitest suite with coverage, see Coverage)

Result:

Pass: 187 test files, 4114 tests passed (final run, 2026-11-08 11:22:25 +0100).

## .taskcheck

Result:

Not run. `sh scripts/check.sh` runs the Gradle server and Android checks too, which this helper does
not run (slow and Gradle is not available to sub-agents). The parent should run
`sh scripts/check.sh` and `cd web && npm run check`.

## Bugs found

None. No test exposed behavior that violates the acceptance criteria.

Observation (not a defect against the criteria, no test added, disposition: for the parent to
decide): `BackNavigation.setLabel` returns early when the path already has the same name, so setting
the same name again does not move it to the most recent end of the 100-name list. A page revisited
often keeps the position of its first naming and could be evicted after 100 other names, after which
its back label falls back to the route title (e.g. "Project"). A different name does move it
(covered by `keeps a renamed page among the most recent names`). Severity: Low.

## Coverage

Status: verified (fresh full-suite run on the final test snapshot).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured separately for statements by intersecting the provider's
source-mapped statement start lines in `coverage-final.json` with added lines of
`git diff -U0 9a29669a..HEAD -- web/src/app`: 158/158 statements on changed lines covered. Branches
whose location starts on a changed line: all covered except `bookmark-reader.ts:81` (see gaps).
This is approximate for branches; changed-branch coverage is not claimed beyond that.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-back-navigation;
  base 9a29669abe86beff6ba450f28a4435ba4087f0f3; HEAD e5322d8c24844b6b68a3f8beae84122e48bcf1f0; task
  commits a63203aff and e5322d8c2, supplied by the parent and checked with `git log 9a29669a..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD e5322d8c2 plus uncommitted
  spec files with sha256:
  back-navigation.spec.ts 64969fcaebdc7a51ca4846d1c4c6bfe7bd863388f663ebebe4915d573a3e8706,
  task-detail.spec.ts 64754961af92df36b5beee6c2832a870d99bba20347b7a7ebaffaa1e4336a6ec,
  contact-detail.spec.ts f23d4bd56e877e181399bbd5d23024ef48afdb1668166c38eec1ebb382cf5d82,
  bookmark-reader.spec.ts ba4b265f93670e58c87558b86d416db6e1ea8bdb33b529df2f0a3e1f7cdd1ad5,
  note-editor-page.spec.ts f33b108855f6075015467b21345d5c9633dc29594e59e5321d2715b1f581aef4,
  projects-pages.spec.ts f59f57acf5ba926e42a4965a6707a4db9012204979720acd88f3b7e7ec058655.
- Exact included files: the 11 TypeScript production files in the table. Excluded: `.html`
  templates (exercised through the component tests, v8 maps template code to the `.ts` file), `.scss`
  styles (no executable behavior), `web/src/locale/messages*.xlf` (translations, no executable
  behavior), `web/e2e/*` (Playwright tests, not production code). No renames or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with
  `@vitest/coverage-v8` 5.0.5; `npm run coverage` (`ng test --watch=false --coverage` with
  text-summary, json-summary, json and lcov reporters) in `web/`; whole web suite.
- Run start/completion time and exit status: started 2026-11-08 11:22:25 +0100, reports written
  11:23:11-11:23:12 +0100; all 187 files / 4114 tests passed (output piped through `tail`, so the
  numeric exit code was not captured; the run reported no failures).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json`
  (sha256 a1e9133a2bd98af3c6375e45aefbf1fdf534112853a57e2047824ccc40d3bb4e),
  `web/coverage/web/coverage-final.json` (sha256 b99ded6e85c6af26ab34e09d210b3743d4ad9ba3e1df221b961a82f69d0c4991),
  `web/coverage/web/lcov.info`.
- Evidence outputs are fresh and inputs stayed unchanged: report timestamps are after the run
  start; Vitest rewrites `coverage/` per run; the spec hashes above were taken before the run and no
  file was edited during it. An earlier run (11:20:21, 4112 tests) preceded the last two tests and is
  superseded.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/app.config.ts` | 1/1 (100.00%) | N/A (0/0) | N/A (0/0) | 1/1 (100.00%) |
| `web/src/app/features/bookmarks/bookmark-reader.ts` | 64/65 (98.46%) | 33/38 (86.84%) | 9/9 (100.00%) | 55/55 (100.00%) |
| `web/src/app/features/contacts/contact-detail.ts` | 163/165 (98.79%) | 80/86 (93.02%) | 25/25 (100.00%) | 141/141 (100.00%) |
| `web/src/app/features/gtd/projects/project-detail-page.ts` | 141/143 (98.60%) | 61/70 (87.14%) | 40/40 (100.00%) | 125/125 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail-page.ts` | 18/18 (100.00%) | 13/16 (81.25%) | 5/5 (100.00%) | 11/11 (100.00%) |
| `web/src/app/features/gtd/task-detail/task-detail.ts` | 219/222 (98.65%) | 145/154 (94.16%) | 51/51 (100.00%) | 179/179 (100.00%) |
| `web/src/app/features/mail/read/mail-read-page.ts` | 190/196 (96.94%) | 115/131 (87.79%) | 40/41 (97.56%) | 160/160 (100.00%) |
| `web/src/app/features/notes/editor/note-editor-page.ts` | 238/241 (98.76%) | 114/124 (91.94%) | 50/51 (98.04%) | 201/202 (99.50%) |
| `web/src/app/shared/navigation/back-button.ts` | 33/33 (100.00%) | 23/23 (100.00%) | 7/7 (100.00%) | 23/23 (100.00%) |
| `web/src/app/shared/navigation/back-navigation.ts` | 68/68 (100.00%) | 41/41 (100.00%) | 14/14 (100.00%) | 57/57 (100.00%) |
| `web/src/app/shared/navigation/index.ts` | 2/2 (100.00%) | N/A (0/0) | 2/2 (100.00%) | 1/1 (100.00%) |
| Aggregate (summed counters, 11 files, none missing) | 1137/1154 (98.53%) | 625/683 (91.51%) | 243/245 (99.18%) | 954/955 (99.90%) |

The whole-file numbers of the feature pages include older code in those files and hits from
pre-existing tests. Before the tests of this report, `back-navigation.ts` had 40/41 branches (the
popstate-without-state branch at line 65 was uncovered); it is now covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/shared/navigation/back-navigation.ts:65` (`restoredState?.navigationId ?? null`) | popstate to an entry without router state must count as unknown | Added `starts afresh on a history entry without router state`; now covered |
| `web/src/app/features/bookmarks/bookmark-reader.ts:81` (`params.get('id') ?? ''`) | Pre-existing route-param default, only moved by the task; route always has `:id` | Non-material, left uncovered |
| `web/src/app/features/gtd/task-detail/task-detail-page.ts:20,25` | v8 branches mapped to the inline template's `@if` and the class decorator (compiler output) | Non-material; the real behaviors (`@if` with/without id, label with/without previous) are tested in task-detail-page.spec.ts |
| Remaining uncovered branches in `contact-detail.ts`, `project-detail-page.ts`, `task-detail.ts`, `mail-read-page.ts`, `note-editor-page.ts` | All outside the task's changed lines (changed-line statements 158/158, changed-line branches covered except the line above) | Pre-existing code outside the task's responsibility; no test added |

## Unable to test

- AC6 (locale units in `messages.xlf` / `messages.de.xlf`): not a unit-test concern; the parent's
  `npm run check` (i18n build) and review cover it.
- AC7 `npm run check` and `sh scripts/check.sh`: not run by this helper (Gradle parts, slow);
  the parent must run them.
- Real browser behavior (actual `history.back()` across a reload, new-tab middle click): covered only
  with Angular's location mocks; e2e locators were updated by the task but Playwright was not run here.

## Summary

Tests added and all validation passed (web unit suite, lint, prettier). No production defects found;
one low-severity observation about `setLabel` recency is left for the parent to decide.
`.taskcheck` was not run by this helper.
