# Test Report

Date: 2026-11-01

Task: web-mail-single-click-open - Mail list: a single click opens the message

Scope: base d811be192b2dedccd79deef2e4abad95710908f1 .. implementation 9bd7628ed (single task commit on main, supplied by the parent worker; `git diff d811be192 9bd7628ed`). Working tree had no other changes besides the test additions below.

Requirements:
- `tasks/web-mail-single-click-open.md` acceptance criteria (primary)
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"
- Parent worker instructions

## Test files changed

- `web/src/app/features/mail/list/mail-list-page.spec.ts`

## Tests added or updated

Count: 4 added, 1 extended (on top of the implementation commit's own spec changes, which were reviewed and kept).

Added in the "opening a message" describe block:
- `opens a row focused with Tab on Enter, moving the cursor to it` - Enter on a row that is focused but not the cursor opens that row and moves the cursor there (the existing Enter test only covered a row reached with j, where the cursor was already on it, so it did not exercise `setCursor` in `openFromKey`).
- `keeps the keyboard shortcuts working on a clicked row` - after a click, x toggles that row's selection and e archives exactly that row.
- `opens the clicked row with o afterwards` - o after a click opens the clicked row.
- `leaves Ctrl/Cmd/Shift+Enter on a row to the browser` - replaces/extends the Ctrl-only test with Cmd and Shift (browser new tab / new window).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: single click opens `/mail/messages/:id`; row is a real link (href), Ctrl/Cmd/middle-click left to the browser | `makes each row a link to its message`, `opens a clicked row and moves the cursor to it without selecting it`, `leaves Ctrl/Cmd-click and middle-click to the browser (new tab)` | `npx ng test --watch=false --include src/app/features/mail/list/mail-list-page.spec.ts` | Pass |
| AC1: browser shows the target on hover | covered indirectly by the href assertion; the hover status bar itself is browser UI | same | Pass (href); hover itself Not testable in jsdom |
| AC2: checkbox only toggles selection, does not open | `only selects on a checkbox click, without opening` | same | Pass |
| AC2: shortcuts work as before (j/k, x, e, #, ...) | existing keyboard specs in the file, plus `keeps the keyboard shortcuts working on a clicked row` | same | Pass |
| AC2: o and Enter on the focused row open it | `opens the cursor message with o`, `opens the clicked row with o afterwards`, `opens the focused row with Enter, once`, `opens a row focused with Tab on Enter, moving the cursor to it`, `leaves Ctrl/Cmd/Shift+Enter on a row to the browser` | same | Pass |
| AC3: double-click handler removed | `opens nothing on a double-click beyond what its clicks open` | same | Pass |
| AC3: keys hint says "o or click open" (localized) | `names o and click in the key hint`; German target in `messages.de.xlf` inspected in the diff ("o oder Klick öffnen") | same | Pass |
| AC4: unit tests updated; `cd web && npm run check` passes | whole file; full web check | `cd web && npm run check` | Pass (reached and completed `ng build`; only the pre-existing initial bundle budget warning) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/mail/list/mail-list-page.spec.ts`

Result:

Pass - 59 tests passed (1 file). One draft of the new shortcut test first failed because it asserted `mail.applies` before the async apply had run; fixed in the test by awaiting `flush()` (test error, not a production defect).

Also: `npx eslint` and `npx prettier --check` on the spec file - clean.

## Full test execution

Command:

`cd web && npm run coverage` (full web Vitest suite with coverage) and `cd web && npm run check`

Result:

Pass - 184 files, 3890 tests passed; `npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build) completed.

## .taskcheck

Result:

Not run - `scripts/check.sh` also runs the server and Android Gradle builds, which this helper does not run (the parent worker runs `.taskcheck`). The task touches only `web/`, whose part of the check (`npm run check`) passed.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by inspecting per-statement counters in `coverage-final.json` for the changed ranges: `openFromKey` (mail-list-page.ts lines 224-228, all statements hit 2 times, function hit 2 times) and `open` (hit 4 times); the changed template lines (row link with routerLink, click, keydown.enter bindings, key hint) have no zero-count statements in the source-mapped template coverage. Changed-branch coverage not separately measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-mail-single-click-open; base d811be192b2dedccd79deef2e4abad95710908f1; HEAD 9bd7628ed (task commit) plus uncommitted spec additions; scope given by the parent worker and confirmed with `git diff --stat d811be192 9bd7628ed`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 mail-list-page.ts 8ad570c90c5141a5e30dda575c9f838a7a356a76ad27101aaf63f2938beceafc, mail-list-page.html b0822bd08dd4ee739aa51881283f707d96d0745090a2d68da9d5461b7fbadc8e, mail-list-page.spec.ts e9a4a86042d86fe72affa3a82f36b19c8faa5943f55563dd64b88925f2f7fad1 (identical before and after the run).
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/list/mail-list-page.ts`, `web/src/app/features/mail/list/mail-list-page.html`. Excluded: `mail-list-page.scss` (style only, one `text-decoration` line, not executable), `web/src/locale/messages.xlf` and `messages.de.xlf` (translation data, checked by the key hint test and by inspection), the spec file (test). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with v8 coverage (`@vitest/coverage-v8`), `npm run coverage` in `web/`, whole web suite.
- Run start/completion time and exit status: started 2026-11-01T07:16:19+01:00, reports written 07:17:10+01:00; all 3890 tests passed, summary printed (exit 0).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 98376ab52742f1578822b3600f48eb5980b41d42f9394e854e4b28f7e676ffb0; `web/coverage/web/coverage-summary.json` sha256 5dfb1caa1a5972f4d5faba7160323ce5e11f77c48b3977c7f478408ec5d69d81.
- Evidence outputs are fresh and inputs stayed unchanged: yes - report files timestamped within the run, input hashes identical before and after.
- Final run after latest test additions and production fixes: yes (no production fixes were made; run includes all new tests).

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

- The browser's hover status bar and the real new-tab behaviour of Ctrl/Cmd/middle-click cannot be observed in jsdom; the unit tests assert the `href` and that the page does not cancel those clicks (so the browser's default applies). A real-browser check would need Playwright; no e2e was added or run here (not required by the criteria). No follow-up needed unless the parent wants e2e evidence.

## Summary

Tests added and all validation passed.
