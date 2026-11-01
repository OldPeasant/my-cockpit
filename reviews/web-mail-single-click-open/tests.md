# Test Report

Date: 2026-11-01

Task: web-mail-single-click-open - Mail list: a single click opens the message

Scope: final revision de35fb9364638b443b0f43aac0d27dc6d78084ca (third run). Task base d811be192b2dedccd79deef2e4abad95710908f1; task commits 983259fb0 (implementation), 94e93b614 (tests, first review evidence), 4b6cee108 (fixer: e2e specs click the row link, comment), 3630ebe3b (review evidence), de35fb936 (e2e: mail folder trees located by email address). Since the previous report (at 6b447800a, rebased form 4b6cee108/3630ebe3b) only `web/e2e/mail-list/list.spec.ts` (line 63) and `web/e2e/phase5/demo.spec.ts` (`accountTree`, line 337) changed outside `tasks/` and `reviews/`, confirmed with `git diff --stat 6b447800a de35fb936`; `git diff --stat 6b447800a de35fb936 -- web/src` is empty. Commits of other tasks brought in by rebases (mail-folder-manage-api, mail-account-label-email) are excluded. Working tree clean before this report.

Requirements:
- `tasks/web-mail-single-click-open.md` acceptance criteria (primary)
- `tasks/mail-account-label-email.md` (why the e2e locators changed: folder-tree regions are labelled by email address)
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"
- Parent worker instructions (this helper does not run Playwright)

## Test files changed

- None in this run. `web/e2e/mail-list/list.spec.ts` and `web/e2e/phase5/demo.spec.ts` were changed by the parent in de35fb936 and reviewed here; they need no further change.
- `web/src/app/features/mail/list/mail-list-page.spec.ts` (first run, committed in 94e93b614; unchanged since).

## Tests added or updated

Count: 0 in this run (first run: 4 added, 1 extended, see the "opening a message" describe block).

Review of the e2e locator change:
- Both changed specs now find the folder-tree region by the account's email address (`address` in list.spec.ts, `ADDRESS` in demo.spec.ts). `mail-folder-tree.ts` sets the account region's heading text and `aria-label` from `emailAddress` (mail-account-label-email AC1), so the locators match the current UI.
- Search for other e2e specs that still locate mail regions by display name: `grep -rn "getByRole('region'" web/e2e` lists only list.spec.ts:63 and phase5/demo.spec.ts:337 under the `Mail folders` navigation; every other region lookup is a non-mail region (Linked items, Preview, restore report, GTD project group). `grep -rnE "displayName|ACCOUNT_NAME|E2E (list|read|compose|new) |Kim Example|Robin Example" web/e2e` shows the remaining display-name uses are API fixture fields (`displayName:` in POST bodies), phase5/demo.spec.ts:100 (Settings account list, which still shows the name per mail-account-label-email AC3), phase6 contacts (not mail accounts), and compose.spec.ts:79, which is a Files folder named `E2E compose {run}` in the attachment picker, not a mail account. No stale mail-region locator remains.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: single click opens `/mail/messages/:id`; row is a real link (href), Ctrl/Cmd/middle-click left to the browser | `makes each row a link to its message`, `opens a clicked row and moves the cursor to it without selecting it`, `leaves Ctrl/Cmd-click and middle-click to the browser (new tab)` | `npx ng test --watch=false --include src/app/features/mail/list/mail-list-page.spec.ts` | Pass |
| AC1: single click opens in a real browser | e2e `Mail read view`, `Mail compose`, `Mail list`, phase 5 demo (single click on `a.open`) | `npm run e2e -- e2e/mail-read e2e/mail-compose e2e/mail-list e2e/phase5/demo.spec.ts` against the compose stack (run by the parent) | Pass as reported by the parent: 38 passed (read.spec, compose.spec, list.spec, all 6 phase 5 demo steps); before de35fb936 phase 5 demo step 1 failed. This helper ran `npm run e2e:typecheck` (Pass) but not Playwright |
| AC1: browser shows the target on hover | covered indirectly by the href assertion | focused unit command | Pass (href); hover status bar Not testable in jsdom |
| AC2: checkbox only toggles selection, does not open | `only selects on a checkbox click, without opening` | focused unit command | Pass |
| AC2: shortcuts work as before (j/k, x, e, #, ...) | existing keyboard specs, `keeps the keyboard shortcuts working on a clicked row` | focused unit command | Pass |
| AC2: o and Enter on the focused row open it | `opens the cursor message with o`, `opens the clicked row with o afterwards`, `opens the focused row with Enter, once`, `opens a row focused with Tab on Enter, moving the cursor to it`, `leaves Ctrl/Cmd/Shift+Enter on a row to the browser` | focused unit command | Pass |
| AC3: double-click handler removed | `opens nothing on a double-click beyond what its clicks open` | focused unit command | Pass |
| AC3: key hint says "o or click open" (localized) | `names o and click in the key hint`; German target "o oder Klick öffnen" in `messages.de.xlf` inspected | focused unit command | Pass |
| AC4: unit tests updated; `cd web && npm run check` passes | whole spec file | `cd web && npm run check` | Pass in the first run on identical spec/template and code-equivalent .ts; not re-run here (only e2e specs changed since; `e2e:typecheck` and prettier on the changed specs, the parts of the check those files affect, pass). The parent runs `.taskcheck` on the final revision |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/mail/list/mail-list-page.spec.ts`

Result:

Pass - 1 file, 59 tests passed (2026-11-01 07:28:30 to 07:28:35 +01:00, Vitest v5.0.5).

Also run:
- `cd web && npm run e2e:typecheck` (`tsc -p e2e/tsconfig.json`) - Pass, exit 0, no errors.
- `cd web && npx prettier --check e2e/mail-list/list.spec.ts e2e/phase5/demo.spec.ts` - Pass.

## Full test execution

Command:

`cd web && npm run coverage` and `cd web && npm run check` (first run); `npm run e2e -- e2e/mail-read e2e/mail-compose e2e/mail-list e2e/phase5/demo.spec.ts` (parent, this revision)

Result:

Unit suite: Pass in the first run (184 files, 3890 tests); not re-run, see AC4 row. Playwright: Pass as reported by the parent (38 passed); not run by this helper.

## .taskcheck

Result:

Not run - the parent worker runs `scripts/check.sh` (it includes the server and Android Gradle builds).

## Bugs found

None.

## Coverage

Status: verified (reused measurement; production code lines unchanged since it, only one doc comment changed)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured in the first run by inspecting per-statement counters in `coverage-final.json` for the changed ranges: `openFromKey` (all statements hit 2 times, function hit 2 times) and `open` (hit 4 times); the changed template lines (row link with routerLink, click, keydown.enter bindings, key hint) have no zero-count statements in the source-mapped template coverage. Changed-branch coverage not separately measured.

Why reuse is valid: `git diff --stat 9bd7628ed de35fb936` over `mail-list-page.ts` and `mail-list-page.html` shows one changed line, the doc comment from fixer CR-2 ("row's button" to "row's link"). The html and spec files are byte-identical to the measured snapshot, and the .ts file hash equals the previous report's final-revision hash. Comments carry no statements, branches or functions and the line count is unchanged, so the counters below describe the final revision. The e2e files changed since are tests, not in the production scope.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-mail-single-click-open; base d811be192b2dedccd79deef2e4abad95710908f1; measured at 9bd7628ed plus the (then uncommitted, now committed) spec additions; final revision de35fb9364638b443b0f43aac0d27dc6d78084ca; scope from the parent worker, confirmed with git diff as above.
- Source/test snapshot identifier (including uncommitted inputs): measured snapshot sha256 mail-list-page.ts 8ad570c90c5141a5e30dda575c9f838a7a356a76ad27101aaf63f2938beceafc, mail-list-page.html b0822bd08dd4ee739aa51881283f707d96d0745090a2d68da9d5461b7fbadc8e, mail-list-page.spec.ts e9a4a86042d86fe72affa3a82f36b19c8faa5943f55563dd64b88925f2f7fad1. Final revision (hashed in this run): mail-list-page.ts fbd4cea1869225c28977adec6d24e896a4432581761d8cef18e0576a31a2cba1 (comment-only change), html and spec hashes identical to the measured snapshot.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/list/mail-list-page.ts`, `web/src/app/features/mail/list/mail-list-page.html`. Excluded: `mail-list-page.scss` (style only), `web/src/locale/messages.xlf` and `messages.de.xlf` (translation data, checked by the key hint test and inspection), spec and e2e files (tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with v8 coverage (`@vitest/coverage-v8`), `npm run coverage` in `web/`, whole web suite.
- Run start/completion time and exit status: started 2026-11-01T07:16:19+01:00, reports written 07:17:10+01:00; 3890 tests passed (exit 0).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 98376ab52742f1578822b3600f48eb5980b41d42f9394e854e4b28f7e676ffb0; `web/coverage/web/coverage-summary.json` sha256 5dfb1caa1a5972f4d5faba7160323ce5e11f77c48b3977c7f478408ec5d69d81.
- Evidence outputs are fresh and inputs stayed unchanged: yes for that run (input hashes identical before and after). The report describes the pre-fix revision 9bd7628ed, which is code-equivalent to the final revision as explained above.
- Final run after latest test additions and production fixes: no fresh coverage run after the fixer and the e2e locator change, because the only production change is a comment and the later change touched only e2e specs; the focused unit spec was re-run on de35fb936 and passes.

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

- Real-browser behaviour cannot be observed in jsdom. Single-click navigation in a browser is covered by the e2e specs, which the parent ran (38 passed, as reported by the parent); this helper did not run Playwright. New-tab behaviour (Ctrl/Cmd/middle-click) and the hover status bar are not covered by e2e; the unit tests assert the `href` and that those clicks are not cancelled. No further follow-up needed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No other e2e spec locates mail regions by display name. On de35fb936 the focused unit spec (59 passed), `e2e:typecheck` and prettier on the changed specs pass; the parent reports the e2e run as 38 passed.
