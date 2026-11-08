# Test Report

Date: 2026-11-08

Task: web-back-navigation - Web back buttons return to where the user came from

Scope: final pass on the code after the fix of TW-1 (guard-redirected Back).
`git diff 8f36a3d9668cb4a1dffd21b73f42ae828f5bd53d..70358e2584e6cc3dddd162ff3883d2d801e10a6c -- web/`
(branch `main`). Base and HEAD as supplied by the parent; task commits from
`git log 8f36a3d..HEAD`: 3a11262b8, 5f3374c69, fbd730ace, 1cb7f53a7, 7655d5891, e8c58ec89,
e55caf3fc, 70358e258. Focus of this pass: commit 70358e258 (`back-navigation.ts`: new
`followRedirect`, `back()` waits past a redirecting NavigationCancel, plus its two new tests). The
only uncommitted change of this pass is the spec file below (`reviews/web-back-navigation/review.md`
is modified in the working tree by the code reviewer, not by this helper).

Requirements:
- `tasks/web-back-navigation.md` acceptance criteria and recorded assumptions
- `reviews/web-back-navigation/fixes.md` (TW-1 fix)
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Coverage"
- Parent instructions for this pass
- Existing test conventions in `back-navigation.spec.ts`

## Test files changed

- `web/src/app/shared/navigation/back-navigation.spec.ts`

## Tests added or updated

Count: 3 added (no test weakened, skipped or removed).

TW-1 status: fixed. `BackNavigation when a Back does not reach its page > leads to the page its back
button names after a Back a guard redirected` now passes unchanged. The fixer's two new tests
(`resolves back() only when the navigation a guard redirected the Back to has ended`, `goes to the
parent route after a redirected Back to an entry it never saw`) were reviewed and are meaningful: the
first asserts without settle() that the redirect's NavigationEnd to `/login` was seen when back()
resolves, the second covers the not-found branch of `followRedirect`.

Added in `BackNavigation when a Back does not reach its page` (a `/waiting` route with a guard that
waits on a test-controlled promise was added to that describe's TestBed):
- `counts the page a guard redirected a link to as the page opened`: an imperative navigation that a
  guard redirects (link to a guarded page, session expired, lands on `/login`) does not move the
  mirror; `previous()` is New, back() lands on `/new` with Today behind. Covers the `!restoring`
  early return of `followRedirect`, which no test reached before (a redirect of a non-popstate
  navigation is the most common redirect in the app). Pass.
- `leads to the page its back button names after a Forward a guard redirected`: Forward onto an entry
  whose guard now redirects; the mirror moves forward to that entry, the redirect replaces it with
  `/login`, `previous()` is Today (url and label), back() lands on `/today`. Pass.
- `leads to the page its back button names after a Back another navigation took over`: the Back's
  guard is still pending when the user opens Mail; the popstate navigation is cancelled with
  `SupersededByNewNavigation` (asserted), back() resolves after Mail's NavigationEnd, then the back
  button should name and lead to `/waiting`. FAILS, see TW-2.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 history back when the previous entry is in the app, parent route otherwise | `BackNavigation` describes (all existing tests plus the 3 new ones) | `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts` | Fail: 49 passed, 1 failed (TW-2) |
| AC1/AC2 after a Back a guard redirected, the back button names and leads to the page behind (TW-1) | `leads to the page its back button names after a Back a guard redirected`, `resolves back() only when the navigation a guard redirected the Back to has ended`, `goes to the parent route after a redirected Back to an entry it never saw`, new Forward and imperative-redirect tests | same | Pass |
| AC1/AC2 after a Back another navigation superseded, the back button leads to the page it names | `leads to the page its back button names after a Back another navigation took over` | same | Fail (TW-2) |
| AC2 label names the destination or reads "Back" | existing back button and page tests | `cd web && npm run coverage` (full suite) | Pass |
| AC3 used by mail read, task detail, project, note, contact, bookmark reader | existing page specs | `cd web && npm run coverage` | Pass |
| AC4 Archive/Spam/Delete return to the origin, once on a double action | mail-read-page.spec.ts `Archive, Spam and Delete` incl. `while going back` | `cd web && npm run coverage` | Pass |
| AC5 opened from New returns to New with its filter; deep link goes to parent route | existing back-navigation and mail-read tests | `cd web && npm run coverage` | Pass |
| AC6 new UI strings in `web/src/locale` (EN + DE) | not a unit-test concern | n/a | Not testable by unit tests (parent: `npm run check` i18n build) |
| AC7 unit tests cover the new behavior; `npm run check` and `sh scripts/check.sh` pass | all of the above | `cd web && npm run coverage` | Fail: 1 failing test (TW-2); `npm run check` and `sh scripts/check.sh` Not run by this helper |

## Focused test execution

Commands (in `web/`):

- `npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts` -> 50 tests:
  49 passed, 1 failed (TW-2).
- `npx prettier --check src/app/shared/navigation/back-navigation.spec.ts` -> Pass
- `npx eslint src/app/shared/navigation/back-navigation.spec.ts` -> Pass (no output)

Result:

Fail (TW-2). The "Not implemented: navigation to another Document" lines in the output are jsdom's
message for the deliberately unprevented modified clicks and are not failures.

## Full test execution

Command:

`cd web && npm run coverage`

Result:

Fail: 187 files, 4132 tests: 4131 passed, 1 failed (`leads to the page its back button names after a
Back another navigation took over`, TW-2). Run 2026-11-08 11:58:04 to 11:59:07 +0100, exit 1. No
other test failed; in particular the TW-1 test and `mail-read-page.spec.ts` pass.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` includes the Gradle server and Android checks, which this helper does
not run, and it would fail on the web unit tests while TW-2 is open. The parent decides on TW-2 and
runs it.

## Bugs found

### A Back that another navigation takes over leaves the back button one page off

Finding ID:

`TW-2`

Test:

`BackNavigation when a Back does not reach its page > leads to the page its back button names after a Back another navigation took over`

Production source:

`web/src/app/shared/navigation/back-navigation.ts:83-88` (the constructor ignores a NavigationCancel
with code `SupersededByNewNavigation`, via `isRedirecting`, so the popstate is never followed) and
`:215-221` (`record` then pushes the new page after the entry the mirror is still on). Pre-existing in
the task since the cancel handling of commit e8c58ec89; not introduced by the TW-1 fix, which only
made back() wait for the navigation that takes over.

Requirement:

AC1/AC2: the back button goes back in history to the page it names. The class's own contract: Back
and Forward move the mirror to the entry the browser restores.

Expected behavior:

History `/today`, `/waiting`, `/mail/messages/m1`. Back; while the guard of `/waiting` is still
pending (a slow guard or resolver), the user opens Mail. The browser has already moved to the
`/waiting` entry and pushes `/mail` there, so its history is `/today`, `/waiting`, `/mail`.
`previous()` should be `/waiting`, and back() should land on `/waiting`.

Actual behavior:

The mirror never left the message entry and pushes `/mail` after it, so `previous()` is
`/mail/messages/m1` (the button reads "Message" and links to it) while `location.back()` lands on
`/waiting` (`expected '/mail/messages/m1' to be '/waiting'`). The mismatch stays until the mirror
starts afresh. It needs a navigation started while a Back is still running, so it is rare in
practice; the same happens for a Forward.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` (Or, if the parent decides it
is out of scope, the parent records that decision; the test is not to be weakened, skipped or
deleted.) A possible direction, not verified here: treat a superseded popstate like
`followRedirect` (move the index to the restored entry without changing entries), so the newer
navigation's `record` pushes after the right entry.

## Coverage

Status: partial-failed. The full coverage run fails on TW-2, and Vitest writes no coverage report
when a test fails (`web/coverage/web/` did not exist after the failing run). The numbers below come
from a fresh run of the same suite with only the TW-2 test filtered out. They describe the final
production code (HEAD 70358e258, `back-navigation.ts` unchanged by this helper) and are diagnostic
evidence, not passing validation; if TW-2 is fixed, `back-navigation.ts` changes and must be measured
again.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured in this pass. The two remaining uncovered locations of
`back-navigation.ts` (lines 167 and 187) are on lines added by the task.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-back-navigation;
  base 8f36a3d9668cb4a1dffd21b73f42ae828f5bd53d and HEAD 70358e2584e6cc3dddd162ff3883d2d801e10a6c
  as supplied by the parent; task commits from `git log 8f36a3d..HEAD`; file scope from
  `git diff --name-only 8f36a3d..HEAD -- web/src`. HEAD was unchanged after the runs.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 70358e258 plus uncommitted
  `web/src/app/shared/navigation/back-navigation.spec.ts` sha256
  9bf8d99f538242f6f1c102789b96d9609e8d3151526baebf5ec8442f2c88ded3; production
  `back-navigation.ts` sha256 6c959d79488577f09dee80093d8e2169dd5dc6d4f1dbe347eaf2b21cef9e3bcf
  (committed, unchanged). All other spec files are committed at HEAD.
- Exact included files: the 11 TypeScript production files in the table. Excluded: `.html`
  templates (exercised via component tests, v8 maps template code to the `.ts`), `.scss` (no
  behavior), `web/src/locale/messages*.xlf` (translations), `*.spec.ts`. No renames or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with
  `@vitest/coverage-v8` 5.0.5, in `web/`: (a) `npm run coverage` (whole suite), (b)
  `npm run coverage -- --filter '^(?!.*another navigation took over).*$'` (whole suite except the
  TW-2 test).
- Run start/completion time and exit status: (a) 11:58:04 to 11:59:07 +0100, exit 1, 4131 passed,
  1 failed, no coverage written; (b) 11:59:12 to 12:00:20 +0100, exit 0, 187 files, 4131 passed,
  1 skipped by the filter.
- Generated report paths and report hashes/run identifier (run b):
  `web/coverage/web/coverage-summary.json` sha256 e5a906ea2d231bc6b293c0b07294c5393d0bc27b02ed3863ff658df1578edcf6,
  `web/coverage/web/coverage-final.json` sha256 4c420d47198b8c346dcb7104fd0d87b51968856092e4c7e858b774e6519e781d,
  `web/coverage/web/lcov.info`; all written by run (b).
- Evidence outputs are fresh and inputs stayed unchanged: the coverage directory was absent after run
  (a), so run (b) produced the reports; the spec and production hashes were the same before run (a)
  and after run (b).
- Final run after latest test additions and production fixes: yes for the latest tests and the TW-1
  fix; a new run is needed if TW-2 is fixed.

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
| `web/src/app/shared/navigation/back-navigation.ts` | 111/113 (98.23%) | 75/77 (97.40%) | 24/24 (100.00%) | 93/93 (100.00%) |
| `web/src/app/shared/navigation/index.ts` | 2/2 (100.00%) | N/A (0/0) | 2/2 (100.00%) | 1/1 (100.00%) |
| Aggregate (summed counters, 11 files, none missing or excluded) | 1180/1199 (98.42%) | 659/719 (91.66%) | 253/255 (99.22%) | 990/991 (99.90%) |

The feature pages' whole-file numbers include older code and hits from older tests; they are
identical to the previous pass (only `back-navigation.ts` changed since). Before the new
imperative-redirect test, `back-navigation.ts` was at 110/113 statements and 73/77 branches.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/shared/navigation/back-navigation.ts:182-183` (`followRedirect` early return for a non-popstate redirect) | A guard redirecting a normal link must not move the mirror | Test added: `counts the page a guard redirected a link to as the page opened` (now covered) |
| `back-navigation.ts:167` (`if (!current)` in `restoreCancelled`) and `:187` (same guard in `followRedirect`) | A cancelled or redirected popstate before the mirror has recorded any page | Non-material: the first navigation is the router's imperative initial navigation, so a popstate cannot end before an entry exists; defensive guards, no test added |
| `back-navigation.ts:83-88`, `:215-221` (superseded popstate) | Covered by lines, but the behavior is wrong | Test added, fails: TW-2 |
| `web/src/app/features/bookmarks/bookmark-reader.ts:81` (`params.get('id') ?? ''`) | Pre-existing route-param default, only moved by the task | Non-material, left uncovered (unchanged) |
| `web/src/app/features/gtd/task-detail/task-detail-page.ts:20,25` | v8 branches mapped to the inline template's `@if` and the decorator | Non-material; the behaviors are tested in task-detail-page.spec.ts (unchanged) |
| Remaining uncovered branches in `contact-detail.ts`, `project-detail-page.ts`, `task-detail.ts`, `mail-read-page.ts`, `note-editor-page.ts` | Outside the task's changed lines (an earlier pass measured changed-line statements 158/158 for these files) | Pre-existing code; no test added |

## Unable to test

- AC6 locale units: not a unit-test concern; the parent's `npm run check` covers the i18n build.
- AC7 `npm run check` and `sh scripts/check.sh`: not run by this helper (Gradle parts); the parent
  runs them.
- Real browser history: only tested with Angular's location mocks. After a redirected Back the
  mirror drops the forward entries (as for any `replaceUrl`), while the browser keeps them; a later
  Forward onto such an entry makes the mirror start afresh (back button falls back to the parent
  route). This is a loss of knowledge, not a wrong target, so it is not reported as a defect.
  Review CR-1 (refused Back then browser Forward) is the parent's accepted known limitation.
- Playwright e2e was not run in this pass.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task
completion. TW-1 is fixed and its tests are sufficient (plus new Forward-redirect and
imperative-redirect tests, both passing). New TW-2 (Low): a Back that another navigation supersedes
while it is still running leaves the back button naming one page and going to another; the parent
decides whether to fix it or record it out of scope. Coverage was measured on the final production
code without the failing TW-2 test.
