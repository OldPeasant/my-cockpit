# Test Report

Date: 2026-11-01

Task: web-mail-single-click-open - Mail list: a single click opens the message

Scope: final revision 6b447800ab4aa650119ec777a15f84d5bda25759 (re-run after the fixer). Task base d811be192b2dedccd79deef2e4abad95710908f1; task commits 460f785c1 (implementation, rebased form of the earlier measured 9bd7628ed), 39a008a96 (tests and first review evidence), 6b447800a (fixer: e2e locators, comment). Other commits between base and HEAD (mail-folder-manage-api, brought in by the rebase onto main) are not part of this task and were excluded. Task-owned paths since the earlier measurement were confirmed with `git diff --stat 9bd7628ed 6b447800a -- web/src/app/features/mail/list web/src/locale web/e2e`. Working tree clean.

Requirements:
- `tasks/web-mail-single-click-open.md` acceptance criteria (primary)
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"
- `reviews/web-mail-single-click-open/fixes.md` (CR-1, CR-2)
- Parent worker instructions (no Playwright run by this helper)

## Test files changed

- `web/src/app/features/mail/list/mail-list-page.spec.ts` (in the earlier run, committed in 39a008a96; unchanged in this re-run)
- No test files changed in this re-run. The fixer's e2e changes (`web/e2e/mail-read/read.spec.ts`, `web/e2e/mail-compose/compose.spec.ts`, `web/e2e/phase5/demo.spec.ts`) were reviewed and need no further change.

## Tests added or updated

Count: 0 in this re-run (earlier run: 4 added, 1 extended, see the "opening a message" describe block).

Review of the fixer's changes:
- The three e2e specs now use `locator('a.open').click()`. The template renders the row as `a class="open"` with `[routerLink]="['/mail/messages', item.id]"` (mail-list-page.html lines 154-158), so the locator matches and a single click is what the task requires. read.spec.ts and demo.spec.ts still assert the URL `/mail/messages/{uuid}` and the heading, which is a real-browser check of AC1 once the parent runs Playwright.
- `grep -rnE "button\.open|dblclick|double-click|Doppelklick"` over `web/e2e`, `web/src/app/features/mail` and `web/src/locale` finds only the unit test that deliberately dispatches a `dblclick` to show it opens nothing extra. No stale references remain.
- CR-2 changes a doc comment only; no test impact.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: single click opens `/mail/messages/:id`; row is a real link (href), Ctrl/Cmd/middle-click left to the browser | `makes each row a link to its message`, `opens a clicked row and moves the cursor to it without selecting it`, `leaves Ctrl/Cmd-click and middle-click to the browser (new tab)` | `npx ng test --watch=false --include src/app/features/mail/list/mail-list-page.spec.ts` | Pass |
| AC1: single click opens in a real browser | e2e `Mail read view`, `Mail compose`, phase5 demo `open` helper (single click on `a.open`) | `npm run e2e:typecheck` | Typecheck Pass; Playwright Not run by this helper: the parent is running it (a second compose stack would collide) |
| AC1: browser shows the target on hover | covered indirectly by the href assertion | focused unit command | Pass (href); hover status bar Not testable in jsdom |
| AC2: checkbox only toggles selection, does not open | `only selects on a checkbox click, without opening` | focused unit command | Pass |
| AC2: shortcuts work as before (j/k, x, e, #, ...) | existing keyboard specs, `keeps the keyboard shortcuts working on a clicked row` | focused unit command | Pass |
| AC2: o and Enter on the focused row open it | `opens the cursor message with o`, `opens the clicked row with o afterwards`, `opens the focused row with Enter, once`, `opens a row focused with Tab on Enter, moving the cursor to it`, `leaves Ctrl/Cmd/Shift+Enter on a row to the browser` | focused unit command | Pass |
| AC3: double-click handler removed | `opens nothing on a double-click beyond what its clicks open` | focused unit command | Pass |
| AC3: key hint says "o or click open" (localized) | `names o and click in the key hint`; German target "o oder Klick öffnen" in `messages.de.xlf` inspected | focused unit command | Pass |
| AC4: unit tests updated; `cd web && npm run check` passes | whole spec file | `cd web && npm run check` | Pass in the earlier run on identical spec/template and code-equivalent .ts; not re-run here (only e2e locators and a comment changed; `e2e:typecheck` and prettier, the parts of the check those files affect, were re-run and pass). The parent runs `.taskcheck` on the final revision |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/mail/list/mail-list-page.spec.ts`

Result:

Pass - 1 file, 59 tests passed (2026-11-01 07:20:30 +01:00).

Also run:
- `cd web && npm run e2e:typecheck` (`tsc -p e2e/tsconfig.json`) - Pass, no errors.
- `cd web && npx prettier --check e2e/mail-read/read.spec.ts e2e/mail-compose/compose.spec.ts e2e/phase5/demo.spec.ts src/app/features/mail/list` - Pass.

## Full test execution

Command:

`cd web && npm run coverage` and `cd web && npm run check` (earlier run)

Result:

Pass in the earlier run (184 files, 3890 tests; `npm run check` completed). Not re-run in this re-run, see AC4 row. Playwright e2e: Not run by this helper (parent runs it).

## .taskcheck

Result:

Not run - the parent worker runs `scripts/check.sh` (it includes the server and Android Gradle builds).

## Bugs found

None.

## Coverage

Status: verified (reused measurement; production code lines unchanged since it, only a doc comment changed)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured in the earlier run by inspecting per-statement counters in `coverage-final.json` for the changed ranges: `openFromKey` (all statements hit 2 times, function hit 2 times) and `open` (hit 4 times); the changed template lines (row link with routerLink, click, keydown.enter bindings, key hint) have no zero-count statements in the source-mapped template coverage. Changed-branch coverage not separately measured.

Why reuse is valid: since the measured snapshot, `mail-list-page.html` and `mail-list-page.spec.ts` are byte-identical (sha256 below equals the earlier snapshot), and `mail-list-page.ts` differs only in one doc comment line (fixer CR-2, "row's button" to "row's link", `git diff 39a008a96 6b447800a`). Comments carry no statements, branches or functions and the line count is unchanged, so the counters below still describe the final revision. The e2e files are tests, not in the production scope.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-mail-single-click-open; base d811be192b2dedccd79deef2e4abad95710908f1; measured at 9bd7628ed plus the (then uncommitted, now committed in 39a008a96) spec additions; final revision 6b447800ab4aa650119ec777a15f84d5bda25759; scope from the parent worker, confirmed with git diff as above.
- Source/test snapshot identifier (including uncommitted inputs): measured snapshot sha256 mail-list-page.ts 8ad570c90c5141a5e30dda575c9f838a7a356a76ad27101aaf63f2938beceafc, mail-list-page.html b0822bd08dd4ee739aa51881283f707d96d0745090a2d68da9d5461b7fbadc8e, mail-list-page.spec.ts e9a4a86042d86fe72affa3a82f36b19c8faa5943f55563dd64b88925f2f7fad1. Final revision: mail-list-page.ts fbd4cea1869225c28977adec6d24e896a4432581761d8cef18e0576a31a2cba1 (comment-only change), html and spec hashes identical to the measured snapshot.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/list/mail-list-page.ts`, `web/src/app/features/mail/list/mail-list-page.html`. Excluded: `mail-list-page.scss` (style only), `web/src/locale/messages.xlf` and `messages.de.xlf` (translation data, checked by the key hint test and inspection), spec and e2e files (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with v8 coverage (`@vitest/coverage-v8`), `npm run coverage` in `web/`, whole web suite.
- Run start/completion time and exit status: started 2026-11-01T07:16:19+01:00, reports written 07:17:10+01:00; 3890 tests passed (exit 0).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 98376ab52742f1578822b3600f48eb5980b41d42f9394e854e4b28f7e676ffb0; `web/coverage/web/coverage-summary.json` sha256 5dfb1caa1a5972f4d5faba7160323ce5e11f77c48b3977c7f478408ec5d69d81.
- Evidence outputs are fresh and inputs stayed unchanged: yes for that run (input hashes identical before and after). The report describes the pre-fix revision 9bd7628ed; it is code-equivalent to the final revision as explained above.
- Final run after latest test additions and production fixes: no fresh coverage run after the fixer, because the only production change is a comment; the focused unit spec was re-run on the final revision and passes.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/list/mail-list-page.ts` | 140/141 (99.29%) | 87/95 (91.57%) | 40/41 (97.56%) | 116/116 (100%) |
| `web/src/app/features/mail/list/mail-list-page.html` | 186/186 (100%) | 26/26 (100%) | 15/15 (100%) | 126/126 (100%) |
| Aggregate (both files, summed counters) | 326/327 (99.69%) | 113/121 (93.39%) | 55/56 (98.21%) | 242/242 (100%) |

No scoped files missing from the report; exclusions listed above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-list-page.ts:88` (anonymous function in constructor), branches at lines 39, 66, 70, 77, 183, 243, 255, 276 (nullish fallbacks, one unhit `if` arm) | Pre-existing code from web-mail-list and other tasks; none of it is in the lines this task changed | Left uncovered: outside this task's changed behavior |

## Unable to test

- Real-browser behaviour (single click navigating, hover status bar, Ctrl/Cmd/middle-click new tab) cannot be observed in jsdom. The fixed e2e specs cover the single-click navigation in a browser, but Playwright was deliberately not run by this helper because the parent is running it on the same compose stack; the parent must record that result. New-tab behaviour and the hover status bar are not covered by e2e; the unit tests assert the `href` and that those clicks are not cancelled. No further follow-up needed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No test changes were needed for the fixer's revision; the focused unit spec (59 passed), `e2e:typecheck` and prettier pass on 6b447800a. Playwright results are pending from the parent.
