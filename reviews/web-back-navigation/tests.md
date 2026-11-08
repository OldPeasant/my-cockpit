# Test Report

Date: 2026-11-08

Task: web-back-navigation - Web back buttons return to where the user came from

Scope: second pass on the final code. `git diff bc4e115422981317f6d5bbcd2d46f5d8ec8ff56b..5963fd9a41eb93fdadcc26a09efae8241b91f207 -- web/`
(branch `main`). The parent supplied base 280e08163 and HEAD 3da86b3f5; while this pass ran, the
parent rebased the task onto the new main. The new base is the merge-base
bc4e115422981317f6d5bbcd2d46f5d8ec8ff56b (`git merge-base HEAD origin/main`), the task commits are
977f9b70a, 0baa50883, b060b7d80, a3545dfc9, 8b8bdd996, 5963fd9a4 (same subjects and file lists as
before the rebase), and `git diff 3da86b3f5 5963fd9a4 -- web` is empty, so the measured web code is
identical to the revision the parent named. Focus of this pass: the two commits that changed
`web/src/app/shared/navigation/back-navigation.ts` since the previous report (back() waits for the
Back navigation, double back() returns the pending Back, a cancelled or failed popstate rewrites the
mirror). The only uncommitted change of this pass is the spec file below (`reviews/.../review.md`
was modified by the code reviewer, not by this helper).

Requirements:
- `tasks/web-back-navigation.md` acceptance criteria and recorded assumptions
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Coverage"
- Parent instructions for this pass and code review item CR-2
- Existing test conventions in `back-navigation.spec.ts`

## Test files changed

- `web/src/app/shared/navigation/back-navigation.spec.ts`

## Tests added or updated

Count: 6 (5 added, 1 tightened; no test weakened, skipped or removed from the committed suite).

Tightened (code review CR-2): `BackNavigation > when the page refuses to be left > leads to the page
its back button names after a cancelled Back`. It used to accept `named?.url ?? '/mail'`, so it would
have passed if the mirror forgot the page behind (back() would then fall back to `/mail`). It now
asserts `navigation.previous()?.url` is `/today` before the second back() and `router.url` is
`/today` after it.

Added, in a new describe `BackNavigation when a Back does not reach its page` (own TestBed with
`rethrowApplicationErrors: false` and a collecting ErrorHandler, because the router reports a failed
popstate navigation to the application error handler):
- `stays on the page and leads to the right page after a Back that failed`: a guard of the restored
  page throws (NavigationError during popstate). The error reaches the ErrorHandler once, the message
  stays shown, `previous()` is Today (url and label), the next back() lands on `/today` and nothing
  is behind it.
- `goes to the parent route after a refused Back to an entry it never saw`: a refused Back to an
  entry with an unknown navigation id leaves nothing known behind, and back() takes the fallback.
- `leads to the page its back button names after a Back a guard redirected`: the restored page's
  guard redirects (UrlTree, e.g. expired session to the login). FAILS, see TW-1.
- `goes back only once when back() is called again while the Back runs`: two direct back() calls
  call `location.back()` once, never navigate to the fallback, and end on the page before.
- `starts a new Back once the previous one has finished`: two awaited back() calls go back twice
  (the pending-Back guard is released).

Reviewed without change: the existing tests of the two commits (back() resolves after the Back ends,
safety timeout with fake timers, resolves on a cancelled Back, double click on the back button, a
name set again stays recent) and the mail read `while going back` tests (second `#` / second Delete
click during the Back delete once). They cover the changed behavior; a first draft of a separate
"refused Back names Today" test was dropped as redundant with the tightened CR-2 test.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 history back when the previous entry is in the app, parent route otherwise | `BackNavigation` describe (existing), new: failed Back, refused Back to unknown entry, double back(), new Back after finished | `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts` | Fail: 44 passed, 1 failed (TW-1) |
| AC1/AC2 after a Back a guard redirected, the back button names and leads to the page behind | `leads to the page its back button names after a Back a guard redirected` | same | Fail (TW-1) |
| AC1/AC2 after a cancelled Back the back button leads to the page it names | `leads to the page its back button names after a cancelled Back` (tightened) | same | Pass |
| AC2 label names the destination or reads "Back" | existing back button and page tests | `cd web && npm run coverage` (full suite) | Pass |
| AC3 used by mail read, task detail, project, note, contact, bookmark reader | existing page specs | `cd web && npm run coverage` | Pass |
| AC4 Archive/Spam/Delete return to the origin, once on a double action | mail-read-page.spec.ts `Archive, Spam and Delete` incl. `while going back` | `cd web && npm run coverage` | Pass |
| AC5 opened from New returns to New with its filter; deep link goes to parent route | existing back-navigation and mail-read tests | `cd web && npm run coverage` | Pass |
| AC6 new UI strings in `web/src/locale` (EN + DE) | not a unit-test concern | n/a | Not testable by unit tests (parent: `npm run check` i18n build) |
| AC7 unit tests cover the new behavior; `npm run check` and `sh scripts/check.sh` pass | all of the above | `cd web && npm run coverage` | Fail: 1 failing test (TW-1); `npm run check` and `sh scripts/check.sh` Not run by this helper |

## Focused test execution

Commands (in `web/`):

- `npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts` -> 1 failed
  (TW-1), all others passed. Last run before the redundant draft test was dropped: 45 passed, 1 failed.
- `npx prettier --check src/app/shared/navigation/back-navigation.spec.ts` -> Pass
- `npx eslint src/app/shared/navigation/back-navigation.spec.ts` -> Pass (no output)

Result:

Fail (TW-1). The "Not implemented: navigation to another Document" lines in the output are jsdom's
message for the deliberately unprevented modified clicks and are not failures.

## Full test execution

Command:

`cd web && npm run coverage`

Result:

Fail: 187 files, 4127 tests: 4126 passed, 1 failed (`leads to the page its back button names after a
Back a guard redirected`, TW-1). Run 2026-11-08 11:44:58 to 11:46:49 +0100, exit 1. No other test
failed.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` includes the Gradle server and Android checks, which this helper does
not run. It would fail anyway while TW-1 is open. The parent runs it after the fix.

## Bugs found

### A Back that a guard redirects leaves the back button one page off

Finding ID:

`TW-1`

Test:

`BackNavigation when a Back does not reach its page > leads to the page its back button names after a Back a guard redirected`

Production source:

`web/src/app/shared/navigation/back-navigation.ts:78-83` (a redirecting NavigationCancel is ignored)
and `:177-196` (`record`: the redirect runs as an imperative `replaceUrl` navigation, so it replaces
the entry the mirror was on, not the restored one)

Requirement:

AC1/AC2: the back button goes back in history to the page it names. The code's own contract for
cancelled/failed popstates: the mirror follows the entry the browser is on.

Expected behavior:

History `/today`, `/guarded`, `/mail/messages/m1`; the guard of `/guarded` now redirects to `/login`;
Back. The router replaces the browser entry of `/guarded` with `/login` (redirect from a browser
navigation uses `replaceUrl`), so the browser is on entry 2 of 3 and `/today` lies behind.
`previous()` should be `/today`, and back() should land on `/today`.

Actual behavior:

The mirror replaces the message entry with `/login` and keeps its index, so `previous()` is
`/guarded` (button reads "Guarded", href `/guarded`) while `location.back()` lands on `/today`
(`expected '/guarded' to be '/today'`). The mismatch stays until the mirror starts afresh. In the app
the auth guards redirect this way (`authGuard` sends an expired session to the login), so a Back after
the session expired can leave every later back button naming one page and opening another.
Side note: back() resolves on that redirecting NavigationCancel, before the redirect ends.

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. The full coverage run fails on TW-1, and Vitest writes no coverage report
when a test fails (`web/coverage/web/` stayed empty after the failing run). The numbers below come
from a fresh run of the same suite with only the TW-1 test filtered out. Use them as diagnostic
evidence, not as passing validation. They describe the pre-fix revision and must be measured again
after TW-1 is fixed.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured in this pass. In the previous pass, statements starting on
changed lines were 158/158 for the same files minus the two later commits. The only uncovered
location now (`back-navigation.ts:160`) is on a line added by commit 5963fd9a4.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-back-navigation;
  base bc4e115422981317f6d5bbcd2d46f5d8ec8ff56b (after the parent's rebase; before it
  280e081634b7d39812586181296017b38efb90b9), HEAD 5963fd9a41eb93fdadcc26a09efae8241b91f207, task commits
  from `git log --stat bc4e1154..HEAD` matched against the parent's list (same subjects, files);
  `git diff 3da86b3f5 5963fd9a4 -- web` empty.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 5963fd9a4 plus uncommitted
  `web/src/app/shared/navigation/back-navigation.spec.ts` sha256
  6cd2eb8494fee0ee5827611df4cfb07ddf65c3d078b08dda5af34a741de8f112; production
  `back-navigation.ts` sha256 edc591e752941a350001c713e2bf6fe6b42e993fcb143f76aeffd1ddc27daee9 (unchanged).
  The other spec files are committed at HEAD.
- Exact included files: the 11 TypeScript production files in the table. Excluded: `.html`
  templates (exercised via component tests, v8 maps template code to the `.ts`), `.scss` (no
  behavior), `web/src/locale/messages*.xlf` (translations), `web/e2e/*` (Playwright tests). No renames
  or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with
  `@vitest/coverage-v8` 5.0.5, in `web/`: (a) `npm run coverage` (whole suite), (b)
  `npm run coverage -- --filter '^(?!.*guard redirected).*$'` (whole suite except the TW-1 test).
- Run start/completion time and exit status: (a) 11:44:58 to 11:46:49 +0100, exit 1, 4126 passed,
  1 failed, no coverage written; (b) 11:47:05 to 11:48:07 +0100, exit 0, 187 files, 4126 passed,
  1 skipped by the filter.
- Generated report paths and report hashes/run identifier (run b):
  `web/coverage/web/coverage-summary.json` sha256 eeeb2bddf796871ed20e60321a567687522b0b38d0d0d694d2186b1f055ec033,
  `web/coverage/web/coverage-final.json` sha256 0cb2a370fec3e5543a6c41b005eff635f774ef77c99d91ecd5f4bf2e3de27c31,
  `web/coverage/web/lcov.info`; all written 11:48:07 +0100.
- Evidence outputs are fresh and inputs stayed unchanged: the directory was empty after run (a), so
  run (b) produced the reports; the spec hash was the same before run (a) and after run (b).
- Final run after latest test additions and production fixes: latest tests yes; TW-1 is not fixed yet,
  so a new run is needed after the fix.

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
| `web/src/app/shared/navigation/back-navigation.ts` | 97/98 (98.98%) | 63/64 (98.44%) | 21/21 (100.00%) | 82/82 (100.00%) |
| `web/src/app/shared/navigation/index.ts` | 2/2 (100.00%) | N/A (0/0) | 2/2 (100.00%) | 1/1 (100.00%) |
| Aggregate (summed counters, 11 files, none missing or excluded) | 1166/1184 (98.48%) | 647/706 (91.64%) | 250/252 (99.21%) | 979/980 (99.90%) |

The feature pages' whole-file numbers include older code and hits from older tests. Only
`back-navigation.ts` changed since the previous pass.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/shared/navigation/back-navigation.ts:160` (`if (!current) return { entries, index }` in `restoreCancelled`) | A cancelled popstate before the mirror has recorded any page | Non-material: the first navigation is the router's imperative initial navigation, so a popstate cannot be cancelled before an entry exists; defensive guard, no test added |
| `back-navigation.ts:78-83`, `:177-196` (redirecting popstate) | Covered by lines, but the behavior is wrong | Test added, fails: TW-1 |
| `web/src/app/features/bookmarks/bookmark-reader.ts:81` (`params.get('id') ?? ''`) | Pre-existing route-param default, only moved by the task | Non-material, left uncovered (unchanged from the previous pass) |
| `web/src/app/features/gtd/task-detail/task-detail-page.ts:20,25` | v8 branches mapped to the inline template's `@if` and the decorator | Non-material; the behaviors are tested in task-detail-page.spec.ts (unchanged) |
| Remaining uncovered branches in `contact-detail.ts`, `project-detail-page.ts`, `task-detail.ts`, `mail-read-page.ts`, `note-editor-page.ts` | Outside the task's changed lines (previous pass: changed-line statements 158/158) | Pre-existing code; no test added |

## Unable to test

- AC6 locale units: not a unit-test concern; the parent's `npm run check` covers the i18n build.
- AC7 `npm run check` and `sh scripts/check.sh`: not run by this helper (Gradle parts); the parent
  runs them after TW-1 is fixed.
- Real browser history (popstate across a reload, Forward onto the duplicate entry that 'replace'
  leaves after a cancelled or failed Back): only tested with Angular's location mocks. Forward after a
  cancelled Back lands on a second entry of the same page, which the router skips (NavigationSkipped),
  so the mirror keeps its index. Not tested and not claimed as a defect: this is how the router
  handles duplicate entries. Follow-up only if the owner wants it.
- Playwright e2e was not run in this pass.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task
completion (TW-1, Medium: a Back that a guard redirects leaves the back button naming one page and
going to another). CR-2 is done: the cancelled-Back test now asserts `/today` exactly. Coverage was
measured on the pre-fix revision without the failing test and must be measured again after the fix.
