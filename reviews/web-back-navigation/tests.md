# Test Report

Date: 2026-11-08

Task: web-back-navigation - Web back buttons return to where the user came from

Scope: final pass on the final code. Task range
`git diff 1a634a78e3ac169fd6fe817a129bb3bd39373630..3ff3a2c3f7bcd3a6756ce4c1aa5bc66f8e2a4591 -- web/`
(branch `main`), base and HEAD as supplied by the parent, task commits from `git log 1a634a78e..HEAD`.
Focus of this pass: the production changes since the previous test report (which covered the code
up to e9c908136), all in `web/src/app/shared/navigation/back-navigation.ts`:
- d1cabbdc0: a Back/Forward cancelled with `SupersededByNewNavigation` is followed like a redirect
  (`followTakenOver`, formerly `followRedirect`); `record` with `replaceUrl` (or the same URL) keeps
  the forward entries, like the browser's replaceState.
- 3ff3a2c3f: `followTakenOver` for a restored entry the mirror never saw clears the mirror instead of
  keeping the page that was left.

The only uncommitted change of this pass is the spec file below.

Requirements:
- `tasks/web-back-navigation.md` acceptance criteria and recorded assumptions
- `reviews/web-back-navigation/fixes.md` (TW-1, TW-2 fixes)
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Coverage"
- Parent instructions for this pass
- Existing test conventions in `back-navigation.spec.ts`

## Test files changed

- `web/src/app/shared/navigation/back-navigation.spec.ts`

## Tests added or updated

Count: 4 added (no test weakened, skipped or removed).

TW-2 status: fixed. `BackNavigation when a Back does not reach its page > leads to the page its back
button names after a Back another navigation took over` passes unchanged. The tests committed with
the fixes were reviewed and are meaningful: `keeps the page ahead after a Back a guard redirected, as
the browser does` (redirect `replaceUrl` keeps the forward entry), `follows the second Back when a
Back runs into a first one still running` (popstate superseded by a popstate), and `goes to the parent
route after a Back to an entry it never saw that another navigation took over` (3ff3a2c3f; now
active and passing, it was skipped in bed9bc2da).

Added (each checks behavior of d1cabbdc0 that no test reached before; each would fail on the code
before d1cabbdc0):
- `BackNavigation > keeps the pages ahead when the current entry is replaced, as the browser does`:
  after a Back to New, an imperative `replaceUrl` navigation (New changes its filter in place) keeps
  the message ahead; browser Forward then lands on the message, and its back button names
  `/new?filter=mail` ("New"). Before d1cabbdc0 the mirror dropped the forward entry and started
  afresh. Pass.
- `BackNavigation > keeps the pages ahead on a navigation to the same URL`: same for a same-URL
  reload navigation (the router uses replaceState there too). Pass.
- `BackNavigation when a Back does not reach its page > leads to the page its back button names after
  a Forward another navigation took over`: browser Forward onto `/waiting` whose guard is pending,
  the user opens Mail meanwhile; the browser history is `/today`, `/waiting`, `/mail` (the old
  `/new` is dropped). `previous()` is Waiting, back() lands on `/waiting` with Today behind. Pass.
- `BackNavigation when a Back does not reach its page > counts the page that took over a link still
  running as the page opened`: an imperative navigation superseded by another imperative one (the
  `!restoring` early return of `followTakenOver` for `SupersededByNewNavigation`); the mirror pushes
  only Mail, `previous()` is New, back() lands on New with Today behind. Pass.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 history back when the previous entry is in the app, parent route otherwise | `BackNavigation` describes (57 tests incl. the 4 new ones) | `cd web && npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts` | Pass (57/57) |
| AC1/AC2 after a Back or Forward a guard redirected, the back button names and leads to the page behind (TW-1) | `leads to the page its back button names after a Back a guard redirected`, `... after a Forward a guard redirected`, `resolves back() only when the navigation a guard redirected the Back to has ended`, `goes to the parent route after a redirected Back to an entry it never saw`, `keeps the page ahead after a Back a guard redirected, as the browser does` | same | Pass |
| AC1/AC2 after a Back or Forward another navigation took over, the back button leads to the page it names (TW-2) | `leads to the page its back button names after a Back another navigation took over`, `... after a Forward another navigation took over` (new), `follows the second Back when a Back runs into a first one still running`, `goes to the parent route after a Back to an entry it never saw that another navigation took over`, `counts the page that took over a link still running as the page opened` (new) | same | Pass |
| AC1 a replaced current entry keeps the pages ahead (d1cabbdc0) | `keeps the pages ahead when the current entry is replaced, as the browser does`, `keeps the pages ahead on a navigation to the same URL` (both new), `does not count a replaced entry as a page behind`, `replaces the current entry on the parent route when asked` | same | Pass |
| AC2 label names the destination or reads "Back" | existing back button and page tests | `cd web && npm run coverage` (full suite) | Pass |
| AC3 used by mail read, task detail, project, note, contact, bookmark reader | existing page specs | `cd web && npm run coverage` | Pass |
| AC4 Archive/Spam/Delete return to the origin, once on a double action | mail-read-page.spec.ts `Archive, Spam and Delete` incl. `while going back` | `cd web && npm run coverage` | Pass |
| AC5 opened from New returns to New with its filter; deep link goes to parent route | existing back-navigation and mail-read tests | `cd web && npm run coverage` | Pass |
| AC6 new UI strings in `web/src/locale` (EN + DE) | not a unit-test concern | n/a | Not testable by unit tests (parent: `npm run check` i18n build) |
| AC7 unit tests cover the new behavior; `npm run check` and `sh scripts/check.sh` pass | all of the above | `cd web && npm run coverage` | Unit tests Pass; `npm run check` and `sh scripts/check.sh` Not run by this helper (parent runs them) |

## Focused test execution

Commands (in `web/`):

- `npx ng test --watch=false --include src/app/shared/navigation/back-navigation.spec.ts` -> 1 file,
  57 tests passed (12:10:40 +0100).
- `npx prettier --check src/app/shared/navigation/back-navigation.spec.ts` -> Pass
- `npx eslint src/app/shared/navigation/back-navigation.spec.ts` -> Pass (no output)

Result:

Pass. The "Not implemented: navigation to another Document" lines in the output are jsdom's message
for the deliberately unprevented modified clicks and are not failures.

## Full test execution

Command:

`cd web && npm run coverage`

Result:

Pass: 187 files, 4139 tests, all passed. Run 2026-11-08 12:11:05 to about 12:11:55 +0100.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` includes the Gradle server and Android checks, which this helper does
not run; the parent runs it.

## Bugs found

None in this pass. TW-1 and TW-2 from earlier passes are fixed (their tests pass unchanged).

## Coverage

Status: verified (fresh run on HEAD 3ff3a2c3f plus this pass's spec changes, all tests passing).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured in this pass. The two uncovered locations of
`back-navigation.ts` (lines 161 and 183) are on lines added by the task.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-back-navigation;
  base 1a634a78e3ac169fd6fe817a129bb3bd39373630 and HEAD 3ff3a2c3f7bcd3a6756ce4c1aa5bc66f8e2a4591 as
  supplied by the parent; task commits from `git log 1a634a78e..HEAD`; file scope from
  `git diff --name-only 1a634a78e..HEAD -- web/src server/src/main android/app/src/main` (web only).
  HEAD was unchanged after the run.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 3ff3a2c3f plus uncommitted
  `web/src/app/shared/navigation/back-navigation.spec.ts` sha256
  3f8af32a58848a26840d12967ce0679f9c4bcd5d9fdfa6ec23509f1381c413c0; production
  `back-navigation.ts` sha256 a86d782e2d25914bdaf49b00fc742c3bbb2227c29ef5c9c36904edbe479ea471
  (committed, unchanged). All other files are committed at HEAD.
- Exact included files: the 11 TypeScript production files in the table. Excluded: `.html`
  templates (exercised via component tests, v8 maps template code to the `.ts`), `.scss` (no
  behavior), `web/src/locale/messages*.xlf` (translations), `*.spec.ts`. No renames or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with
  `@vitest/coverage-v8` 5.0.5, `npm run coverage` in `web/`, whole suite, no filter.
- Run start/completion time and exit status: 12:11:05 to about 12:11:55 +0100; 187/187 files and
  4139/4139 tests passed. The shell exit code was not captured separately (output was piped through
  `tail`); Vitest reported no failure and wrote the coverage reports, which it does not do on a
  failing run.
- Generated report paths and report hashes/run identifier:
  `web/coverage/web/coverage-summary.json` sha256 e2d6254321a6d8aa7fb69dca023f84d5bae2a2a2fca2d3b43877122623c42a81,
  `web/coverage/web/coverage-final.json` sha256 b1b27cc78cdba2a1917fbc18ef1de1f4e4f5fac6290ec7b7b6c34f687948c1e2,
  `web/coverage/web/lcov.info`; all written 12:11:54-12:11:55, after the run started.
- Evidence outputs are fresh and inputs stayed unchanged: report timestamps fall within the run; the
  spec and production hashes and HEAD were the same before and after the run.
- Final run after latest test additions and production fixes: yes (includes 3ff3a2c3f and the 4 new
  tests).

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
| `web/src/app/shared/navigation/back-navigation.ts` | 111/113 (98.23%) | 74/76 (97.37%) | 24/24 (100.00%) | 93/93 (100.00%) |
| `web/src/app/shared/navigation/index.ts` | 2/2 (100.00%) | N/A (0/0) | 2/2 (100.00%) | 1/1 (100.00%) |
| Aggregate (summed counters, 11 files, none missing or excluded) | 1180/1199 (98.42%) | 658/718 (91.64%) | 253/255 (99.22%) | 990/991 (99.90%) |

The feature pages' numbers are identical to the previous pass (only `back-navigation.ts` changed
since). `back-navigation.ts` has one branch fewer than before (76 instead of 77) because d1cabbdc0
merged the two NavigationCancel conditions of the constructor.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/shared/navigation/back-navigation.ts:161` (`if (!current)` in `restoreCancelled`) and `:183` (same guard in `followTakenOver`) | A cancelled or taken-over popstate before the mirror has recorded any page | Non-material: the first navigation is the router's imperative initial navigation, so a popstate cannot be cancelled before an entry exists; defensive guards, no test added |
| `back-navigation.ts:213-217` (`replaceUrl`/same URL keeps forward entries, d1cabbdc0) | Covered by lines before, but the forward-keeping behavior had no test | Tests added: `keeps the pages ahead when the current entry is replaced, as the browser does`, `keeps the pages ahead on a navigation to the same URL` |
| `back-navigation.ts:78-79`, `:177-188` for a superseded Forward and a superseded link | Covered by lines before (Back cases), Forward and imperative supersede not asserted | Tests added: `leads to the page its back button names after a Forward another navigation took over`, `counts the page that took over a link still running as the page opened` |
| `web/src/app/features/bookmarks/bookmark-reader.ts:81` (`params.get('id') ?? ''`) | Pre-existing route-param default, only moved by the task | Non-material, left uncovered (unchanged) |
| `web/src/app/features/gtd/task-detail/task-detail-page.ts:20,25` | v8 branches mapped to the inline template's `@if` and the decorator | Non-material; the behaviors are tested in task-detail-page.spec.ts (unchanged) |
| Remaining uncovered branches in `contact-detail.ts`, `project-detail-page.ts`, `task-detail.ts`, `mail-read-page.ts`, `note-editor-page.ts` | Outside the task's changed lines (an earlier pass measured changed-line statements 158/158 for these files) | Pre-existing code; no test added |

## Unable to test

- AC6 locale units: not a unit-test concern; the parent's `npm run check` covers the i18n build.
- AC7 `npm run check` and `sh scripts/check.sh`: not run by this helper (Gradle parts); the parent
  runs them.
- Real browser history: only tested with Angular's location mocks (`SpyLocation`, whose
  replaceState keeps forward entries like a browser). Review CR-1 (refused Back then browser
  Forward) remains the parent's accepted known limitation.
- Playwright e2e was not run in this pass.

## Summary

Tests added and all validation passed. TW-2 is fixed; 4 tests added for the behavior of d1cabbdc0
that was still unasserted (replaced entry keeps the pages ahead, superseded Forward, superseded
link). Focused spec 57/57 and the full web suite 4139/4139 pass; task-scoped coverage was measured
fresh on the final code. No production defects found in this pass.
