# Test Report

Date: 2026-10-21

Task: web-mail-read - Mail reading view (round 2, on the final code after fixes)

Scope: task commits of `web-mail-read`, as given by the parent. Base `32667af6` (`git merge-base HEAD origin/main`). Task commits `8e16c085`, `73b39b38`, `5c45934c`, `8b328019`, `08edb6f2`. HEAD `08edb6f2`. Measured diff: `git diff 32667af6..HEAD`, plus my uncommitted spec changes. This round focuses on the production changes the fixes made in `web/src/app/features/mail/read/` (`git diff 5c45934c..HEAD`):
- frame resize via `body.scrollHeight`;
- text preview identity (`this.preview() === pending`);
- failed proxied reload for an allowlisted sender;
- per-message saving keys (`savingKey`, `isSaving`);
- a local `format-bytes.ts`.

I also checked that the earlier tests still cover every acceptance criterion. `reviews/web-mail-read/review.md` shows as modified in the working tree. I did not make that change, and it is not part of what I measured.

Requirements:
- `tasks/web-mail-read.md`: acceptance criteria and recorded assumptions
- SPEC.md §7.3, as the task cites it
- `reviews/web-mail-read/review.md` (CR-1..CR-6) and `fixes.md`, for the intent of the fixes
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md conventions
- Parent instructions: do not run Playwright/e2e; run `npm run check` and task-scoped coverage

## Test files changed

- `web/src/app/features/mail/read/mail-read-page.spec.ts`

## Tests added or updated

Count: 5 new tests in this round. The focused mail suite went from 271 to 276 tests, and the full web suite from 2353 to 2358. I weakened or removed no existing test.

The fixer's tests are already committed: `shrinks again when the content gets shorter`, the CR-2 late preview success/failure tests, the CR-3 blocked-copy and late-failure tests, the per-message save test, and `format-bytes.spec.ts`. I reviewed them and they check the right behavior. On top of them I added:

- `navigating to another message`:
  - `ignores a proxied load for an allowed sender that answers after navigating`: a late *successful* proxied reload must not replace the next message. This covers the `seq` guard inside the new CR-3 try block (line 261). Coverage showed that guard was not executed before this test.
  - `ignores a failed proxied load for an allowed sender after the page is gone`: covers the `destroyed` guard of the new CR-3 catch. Without that guard, a notification would fire after the page was destroyed.
  - `still blocks a second save when coming back to a message whose save runs`: with per-message keys, coming back to message A while A's save is still running keeps A's button disabled, and a click does not start a second save.
- `attachment edge cases`:
  - `does not reopen a text preview that was closed while it loaded`: preview identity after "Close preview".
  - `takes only the latest answer when the same text part is previewed again`: same message, same part, opened twice. The first request fails late, and the second preview still shows its own result. The old check, which compared only `partId`, would have shown the first error.

Request order is controlled with deferred promises. No test uses real delays.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/mail/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: sanitized HTML in a sandboxed iframe (no scripts, CSP) | `shows headers and renders the HTML body in a sandboxed frame without scripts`, `sandboxes the frame without allow-scripts or allow-forms`, `puts the body into a document with the CSP`, `MAIL_FRAME_CSP forbids scripts...`; frame fit tests incl. `shrinks again when the content gets shorter (measures the body, not the root)` | F | Pass |
| AC1: 'load images' toggle with per-sender allowlist | remote-images tests, `image-allowlist.spec.ts`, `shows the blocked copy when the proxied load for an allowed sender fails`, the new stale/destroyed proxied-load tests, earlier navigation tests | F | Pass |
| AC1: attachments (download, preview, save to Files) | attachment tests, `mail-read.service.spec.ts`, `format-bytes.spec.ts`, CR-2 preview tests, new preview-identity tests, per-message save tests (fixer's and new) | F | Pass |
| AC2: → Inbox task, → Waiting For, Create note; linked items panel | GTD tests (create, linked items reload, `EMAIL` ref), `disables the GTD buttons while one runs...`, `reports an unexpected GTD failure...` | F | Pass |
| AC3: Vitest tests cover iframe sandbox attributes and image toggle; `npm run check` passes | the tests above | F; `cd web && npm run check` | Pass (both exit 0) |
| AC4: Playwright `e2e/mail-read/read.spec.ts` against the e2e stack | not run by me | - | Not run: the parent asked me not to run Playwright/e2e. The parent runs it. |
| Assumption: route `/mail/messages/:id` opens the read view | `mail.routes.spec.ts` tests | F | Pass |
| Assumption: the list opens a message with `o` or double-click | `mail-list-page.spec.ts` open tests, `mail-shortcuts.spec.ts` | F | Pass |
| Assumption: opening an unread message marks it read (best effort) | `marks an unread message read, not a read one`, `keeps the message shown when marking it read fails` | F | Pass |
| CR-1 fix: frame shrinks when content gets shorter | `shrinks again when the content gets shorter (measures the body, not the root)` | F | Pass |
| CR-2 fix: preview identity | the fixer's two late-preview tests; new `does not reopen a text preview that was closed while it loaded`, `takes only the latest answer when the same text part is previewed again` | F | Pass |
| CR-3 fix: a failed proxied reload keeps the blocked copy | `shows the blocked copy when the proxied load...`, `ignores a failed proxied load ... after navigating`; new `ignores a proxied load ... after navigating`, `... after the page is gone` | F | Pass |
| Observation 2 fix: per-message saving keys | `lets the next message save a part with the same id while an older save runs`, new `still blocks a second save when coming back...`, `saves a part once while saving...` | F | Pass |
| CR-6 fix: local formatBytes | `format-bytes.spec.ts` (9 cases) | F | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/mail/**/*.spec.ts'`

Result:

Pass: 13 files, 276 tests. `npx prettier --write` and `npx eslint src/app/features/mail/read` reported no problems.

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e typecheck, test:ci, ng build)

Result:

Pass (exit 0): 119 files, 2358 tests. `ng lint` reported "All files pass linting." The build prints the initial-bundle budget warning (657.71 kB against a 500 kB budget). That warning existed before this task, which only adds lazy-loaded code.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ended with "== all checks passed". It ran on HEAD `08edb6f2` plus my uncommitted spec changes.

## Bugs found

None. No test exposed behavior that violates the acceptance criteria, the recorded assumptions, or the intent of the fixes.

## Coverage

Status: verified for the task's TypeScript files. Template coverage of `mail-read-page.html` is not meaningful (measurement artifact, explained below).

Metric: whole-file coverage of task-changed production files.

Changed-line coverage: method is provider-native statement line hits from `coverage-final.json` (the line an istanbul statement starts on), intersected with the added lines of `git diff -U0 32667af6`. I did not infer changed-branch coverage. In the TS files, 191/196 changed executable lines are covered:
- `mail-read-page.ts` 125/130;
- `mail-frame.ts` 23/25;
- `format-bytes.ts` 8/8;
- `image-allowlist.ts` 17/17;
- `mail-read.service.ts` 12/12;
- `mail-list-page.ts` 5/5;
- `mail.routes.ts` 1/1;
- `mail-shortcuts.ts` 0/0 (N/A).

The 7 uncovered changed lines are the guard clauses listed in the gap table.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-mail-read`. Base `32667af6` and commits `8e16c085`, `73b39b38`, `5c45934c`, `8b328019`, `08edb6f2` came from the parent. HEAD is `08edb6f2`. File scope: `git diff --name-only 32667af6..HEAD -- web/src`, excluding `*.spec.ts`.
- Source/test snapshot identifier: production code is committed HEAD `08edb6f2`. `git diff HEAD -- web/src ':!*.spec.ts'` is empty. The only uncommitted test input is `mail-read-page.spec.ts`, sha256 `49507e0844c6cc719bf25f5d279447796cb8d5d919956d9d1000a71b193fc96b`. I hashed it before and after the final runs, and the hashes match. The other read specs are committed: `mail-frame.spec.ts` 2caa0722…bc71, `format-bytes.spec.ts` 36ce809f…c8b1, `image-allowlist.spec.ts` 0326cee9…d82b, `mail-read.service.spec.ts` 00999c82…81a6.
- Included files:
  - `web/src/app/features/mail/read/mail-read-page.ts`, `read/mail-frame.ts`, `read/format-bytes.ts`, `read/image-allowlist.ts`, `read/mail-read.service.ts`, `read/mail-read-page.html`;
  - `web/src/app/features/mail/list/mail-list-page.ts`, `list/mail-list-page.html`, `list/mail-shortcuts.ts`;
  - `web/src/app/features/mail/mail.routes.ts`.
- Excluded files:
  - `read/mail-read-page.scss`: styles only.
  - `web/e2e/mail-read/read.spec.ts` and `web/e2e/support/imap.ts`: Playwright test code, not instrumented by Vitest.
  - No files were renamed or deleted. No scoped file is missing from the report.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8`. Command `npm run coverage` in `web/`, which runs the whole web suite.
- Run start/completion time and exit status: 2026-10-21T15:57:58Z to 15:58:31Z, exit 0, 119 files and 2358 tests passed. An earlier coverage run at 15:55:59Z, before my last added test, was superseded by this run.
- Generated report paths and report hashes:
  - `web/coverage/web/coverage-final.json`: sha256 `0a0c783d2ba45052d1d4600c1bfa48408a8598f72e3b31e50256c135a1836a82`, written 17:58:30 +0200.
  - `web/coverage/web/coverage-summary.json`: sha256 `a8b512908d76a99f56063e6d5a545a76bd6a5949a6a1a77d96cb070491e940c7`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report write times match the run end, and the spec hash and the production tree were unchanged before and after the run.
- Final run after latest test additions and production fixes: yes. It ran on HEAD `08edb6f2`, which includes all fixes, and after all my test additions.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/read/mail-read-page.ts` | 154/159 (96.85%) | 91/107 (85.05%) | 36/36 (100.00%) | 130/130 (100.00%) |
| `web/src/app/features/mail/read/mail-frame.ts` | 31/33 (93.94%) | 14/16 (87.50%) | 8/9 (88.89%) | 25/25 (100.00%) |
| `web/src/app/features/mail/read/format-bytes.ts` | 8/8 (100.00%) | 6/6 (100.00%) | 1/1 (100.00%) | 8/8 (100.00%) |
| `web/src/app/features/mail/read/image-allowlist.ts` | 22/22 (100.00%) | 11/11 (100.00%) | 9/9 (100.00%) | 17/17 (100.00%) |
| `web/src/app/features/mail/read/mail-read.service.ts` | 15/15 (100.00%) | 15/15 (100.00%) | 7/7 (100.00%) | 12/12 (100.00%) |
| `web/src/app/features/mail/list/mail-list-page.ts` | 137/138 (99.28%) | 87/95 (91.58%) | 39/40 (97.50%) | 113/113 (100.00%) |
| `web/src/app/features/mail/list/mail-shortcuts.ts` | 17/17 (100.00%) | 27/27 (100.00%) | 4/4 (100.00%) | 12/12 (100.00%) |
| `web/src/app/features/mail/mail.routes.ts` | 9/9 (100.00%) | 6/6 (100.00%) | 3/3 (100.00%) | 8/8 (100.00%) |
| `web/src/app/features/mail/list/mail-list-page.html` | 182/182 (100.00%) | 26/26 (100.00%) | 15/15 (100.00%) | 124/124 (100.00%) |
| `web/src/app/features/mail/read/mail-read-page.html` (measurement artifact, see below) | 40/192 (20.83%) | 2/36 (5.56%) | 0/10 (0.00%) | 35/124 (28.23%) |

Aggregates (summed counters):
- Over the 8 TS files: statements 393/401 (98.00%), branches 257/283 (90.81%), functions 107/109 (98.17%), lines 325/325 (100.00%).
- Over all 10 measured files, including both templates: statements 615/775 (79.35%), branches 285/345 (82.61%), functions 122/134 (91.04%), lines 484/573 (84.47%). The `mail-read-page.html` artifact pulls these figures down.
- Excluded from both: `.scss` and the e2e files (see above).

Template artifact (unchanged from round 1): `mail-read-page.spec.ts` uses `TestBed.overrideComponent` to swap `LinkedItems` for a stub. Angular then compiles the template again through JIT, so the AOT template that coverage maps to `mail-read-page.html` never runs in that spec. The 20.83% comes only from `mail.routes.spec.ts`, which renders the loading state. The template is still tested: the page spec's tests render it and check the headers, frame, image banner, attachments, previews, save buttons (`isSaving`) and GTD buttons.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-read-page.ts:261` (stale successful proxied reload, CR-3 try block) | Ordering: a late reload must not replace the next message | Covered now by the new test `ignores a proxied load for an allowed sender that answers after navigating` |
| `mail-read-page.ts:266` (`destroyed` in the CR-3 catch) | No notification after the page is gone | Covered by the new `... after the page is gone` test (the branch is counted as covered) |
| `mail-read-page.ts:206` (`!id \|\| !partId`), `208` (key already saving) | Guards behind a hidden or disabled button | Not reachable through the UI: parts without a part id are not listed, and a disabled button gets no click. The disabled state itself is tested (3 save tests). |
| `mail-read-page.ts:114` (showImages with no message or already loaded), `143` (gtd while busy), `174` (preview without id) | Guards behind hidden or disabled UI | Not reachable through the UI. The disabled or hidden UI is tested. |
| `mail-read-page.ts:59,82,85,89,93,103,132,138,236,240,293` (`??`/`\|\|` fallbacks, empty id, no sender address) | Defensive defaults for DTO fields that the server always sets | Non-material. No test added. |
| `mail-frame.ts:93` (frame without a body), `97` (`body` gone during resize, `?? 0`), 1 function at line 78 (the destroy arrow when no observer exists) | Defensive | Non-material: jsdom and browsers always give an attached iframe a body. The CR-1 change to the body is covered by the shrink test. |
| `mail-list-page.ts:39,66,70,77,88,183,232,244,265` | Code that existed before this task | Outside the task. The task's lines are covered (5/5). |
| `mail-read-page.html` | Template rendering | Measurement artifact, described above. The behavior is tested. |

## Unable to test

- AC4 (Playwright against the e2e stack): I did not run it, at the parent's instruction. The parent runs it.
- In jsdom: sandbox/CSP enforcement and the frame's real layout height (the CR-1 shrink in a real browser). jsdom enforces neither sandbox nor CSP and does no layout. The unit tests check the attributes, the CSP meta tag, and the resize logic with stubbed `body.scrollHeight` and `ResizeObserver`. Only the Playwright spec covers real enforcement. The real shrink behavior is not checked by any automated test. A browser test could check it, but it is not required by the acceptance criteria.
- Template coverage numbers for `mail-read-page.html` (see "Coverage").

## Summary

Tests added and all validation passed. This round adds 5 tests for the fixed behavior: preview identity, stale and destroyed proxied reloads, and per-message saving keys. `npm run check` and `.taskcheck` pass. Coverage of the task's TypeScript files is sufficient. I found no production defects. The remaining gaps are UI-unreachable guards, defensive fallbacks, pre-existing code, and the template-coverage measurement artifact, all explained above.
