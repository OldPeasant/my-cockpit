# Test Report

Date: 2026-10-21

Task: web-mail-read - Mail reading view

Scope: task commits of `web-mail-read`. The parent gave base `f9f451a32803f53d62dd3a1cc0267e1aab527116` and task commits `aed7be71`, `b93afaef` (`git diff f9f451a3..b93afaef`). While I was measuring, the parent rebased the task onto main. The commits are now `574ca437` and `f6d130e2` on base `93e42c58151af96efdb5cc283615b888fa66931e` (the merge-base with `origin/main`), and HEAD is `86f8648cfea0e988bf1f19b1fbae67f1342b1be7` (later commits only touch `tasks/`). `git diff b93afaef 86f8648c -- web/` is empty, so every web input I measured is identical at both revisions.

Requirements:
- `tasks/web-mail-read.md` acceptance criteria and its recorded assumptions (route `/mail/messages/:id`, `o`/double-click open, client-side image allowlist, sandbox tokens, mark read on open)
- SPEC.md §7.3 / §4.1 as cited by the task and the code
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md conventions
- Parent instructions (focused command, do not re-run the e2e stack)

## Test files changed

- `web/src/app/features/mail/read/mail-read-page.spec.ts`
- `web/src/app/features/mail/read/mail-frame.spec.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`
- `web/src/app/features/mail/mail.routes.spec.ts`

## Tests added or updated

Count: 28 new tests (focused mail suite went from 228 to 256 tests). I weakened or removed no existing test. In the read page spec, the test double `FakeReadService` gained these features: an answer can be a deferred promise, marking read can fail, and a `setup` hook runs before the page starts. All existing tests still use the old behavior unchanged.

- `mail-read-page.spec.ts` (18 new):
  - Load and display: `reports an unexpected load failure in general words`, `keeps the message shown when marking it read fails`.
  - Header and body edge cases: `names a message without subject or body`, `shows a sender by address when it has no name, and the Cc line`, `offers only "Load images" when the sender has no address`.
  - Navigating to another message: `shows the loading state, not the previous message, while the next one loads`, `ignores an older load that answers after the newer one` (it also checks that the stale unread message is not marked read), `ignores an older load that fails after the newer one`, `does not apply proxied images of the previous message`, `does not report a failed image load of the previous message`, `closes an open attachment preview`.
  - Running actions: `disables the GTD buttons while one runs, so it cannot run twice`, `reports an unexpected GTD failure in general words and enables the buttons again`.
  - Attachment edge cases: `skips parts without a part id`, `shows a loading text preview, then its failure`, `cuts a long text preview at the limit`, `keeps the newer preview when an older text preview answers late`, `saves a part once while saving, then reports a failure and allows a retry`.
- `mail-frame.spec.ts` (4 new):
  - `has an accessible name by default`.
  - Fitting to the content: `grows to its document height on load and follows later size changes`, `observes only the latest document after another load, and stops on destroy`, `still sizes the frame once where ResizeObserver is missing`.
- `mail-list-page.spec.ts` (4 new): opening a message, `opens the cursor message with o`, `opens a row on double-click`, `opens nothing with o in an empty folder, nor while typing`, `names o and double-click in the key hint`.
- `mail.routes.spec.ts` (2 new): `opens the read view at /mail/messages/:id and loads that message with images blocked` (checks the component, the title `Message`, and `GET /api/v1/mail/messages/{id}?remoteImages=BLOCK`), `does not take a message path for the list`.

All request ordering is driven by deferred promises. No test uses real delays.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/mail/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: sanitized HTML in a sandboxed iframe (no scripts, CSP) | existing `shows headers and renders the HTML body in a sandboxed frame without scripts`, `sandboxes the frame without allow-scripts or allow-forms`, `puts the body into a document with the CSP`, `MAIL_FRAME_CSP forbids scripts...`; new frame fit/resize tests | F | Pass |
| AC1: 'load images' toggle with per-sender allowlist | existing remote-images tests and `image-allowlist.spec.ts`; new `does not apply proxied images of the previous message`, `does not report a failed image load of the previous message`, `offers only "Load images" when the sender has no address` | F | Pass |
| AC1: attachments (download, preview, save to Files) | existing attachment tests and `mail-read.service.spec.ts`; new attachment edge-case tests (loading/error preview, 100k limit, stale preview, save once while pending, failure and retry, parts without a part id) | F | Pass |
| AC2: → Inbox task, → Waiting For, Create note; linked items panel | existing GTD tests (create, linked items reload, `EMAIL` ref); new `disables the GTD buttons while one runs...`, `reports an unexpected GTD failure...` | F | Pass |
| AC3: Vitest tests cover iframe sandbox attributes and image toggle; `npm run check` passes | the tests above | F; `cd web && npm run check` | Pass (both commands exit 0) |
| AC4: Playwright `e2e/mail-read/read.spec.ts` against the e2e stack | not run by me | - | Not run: the parent asked me not to re-run the e2e stack; the parent reported `npm run e2e -- --no-deps e2e/mail-read/read.spec.ts -> 1 passed` |
| Assumption: route `/mail/messages/:id` opens the read view | `opens the read view at /mail/messages/:id...`, `does not take a message path for the list` | F | Pass |
| Assumption: list opens a message with `o` or double-click (single click only moves the cursor) | `opens the cursor message with o`, `opens a row on double-click`, `opens nothing with o in an empty folder, nor while typing`, `names o and double-click in the key hint`; existing `moves the cursor to a clicked row without opening or selecting it`, `mail-shortcuts.spec.ts` | F | Pass |
| Assumption: opening an unread message marks it read (best effort) | existing `marks an unread message read, not a read one`; new `keeps the message shown when marking it read fails`, stale-load test (a stale message is not marked read) | F | Pass |
| Ordering: stale load or image reload after navigating to another id | navigation tests listed above | F | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/mail/**/*.spec.ts'`

Result:

Pass: 12 files, 256 tests passed.

`npx prettier --write` on the 4 spec files and `npx eslint src/app/features/mail` reported no problems.

## Full test execution

Command:

`cd web && npm run check` (api:check, lint, e2e typecheck, test:ci, ng build)

Result:

Pass (exit 0). The build prints the pre-existing initial-bundle budget warning (657.71 kB against a 500 kB budget). This task does not cause it.

The full web unit suite also ran inside `npm run coverage`: 118 files, 2338 tests passed.

## .taskcheck

Result:

Pass. `sh scripts/check.sh`, the `.taskcheck` command, exited 0 with "all checks passed". It covered the TFP gate tests, server `./gradlew check`, web `npm run check`, and Android ktlint, unit tests and lint. It ran on the working tree with my new specs, which the parent had already rebased onto main (`86f8648c`).

## Bugs found

None. No test exposed behavior that violates the acceptance criteria or the recorded assumptions.

Observations for the parent. These are not defects against any requirement, so I wrote no failing tests for them:
- `mail-read-page.ts:247-249`: if the sender is on the allowlist and the second (proxied) `get` fails, the whole view shows "The message could not be loaded". The message with images blocked had already loaded and could have been shown. The spec does not define this case.
- `mail-read-page.ts:72,203`: the `saving` set of part ids is not cleared when the page moves to another message. Part ids such as `2` repeat across messages, so the same part's "Save to Files" button on the next message stays disabled until the earlier save finishes. This is minor UX only.

## Coverage

Status: verified for the task's TypeScript files. Template coverage of `mail-read-page.html` is not meaningful; the reason is below.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: I computed it from provider-native statement line hits, intersected with the added lines of `git diff -U0 f9f451a3..HEAD`. In the TS files: 180/180 executable changed lines covered (mail-list-page.ts 5/5, mail.routes.ts 1/1, read/*.ts 174/174; mail-shortcuts.ts has no executable changed lines, so N/A). I did not infer changed-branch coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-mail-read`. Base `f9f451a3` and commits `aed7be71` and `b93afaef` came from the parent. After the rebase they are `574ca437` and `f6d130e2` on `93e42c58`. Measured trees: `b93afaef` and `86f8648c`, whose `web/` trees are identical. The file scope comes from `git diff --name-only f9f451a3..b93afaef -- web/src` and `git diff --name-only 93e42c58..f6d130e2 -- web/src` (excluding `*.spec.ts`), which list the same files.
- Source/test snapshot identifier (including uncommitted inputs): production code = committed HEAD. Uncommitted spec files, by sha256: `mail-read-page.spec.ts` ca47a8dd…40d1, `mail-frame.spec.ts` 3fd9df06…4139, `mail-list-page.spec.ts` 7029b64b…dc62, `mail.routes.spec.ts` d06964af…7498, plus the unchanged `image-allowlist.spec.ts` 0326cee9…d82b and `mail-read.service.spec.ts` 00999c82…81a6. I took these hashes before and after the measurement runs; they match.
- Exact included files: `web/src/app/features/mail/list/mail-list-page.ts`, `list/mail-list-page.html`, `list/mail-shortcuts.ts`, `mail.routes.ts`, `read/image-allowlist.ts`, `read/mail-frame.ts`, `read/mail-read-page.ts`, `read/mail-read-page.html`, `read/mail-read.service.ts`.
- Excluded files and reasons:
  - `read/mail-read-page.scss`: styles only, no executable behavior.
  - `web/e2e/mail-read/read.spec.ts` and `web/e2e/support/imap.ts`: Playwright test code and test support, not production code, and not instrumented by Vitest.
  - No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` 5.0.5. Command `npm run coverage` in `web/`, which runs the whole web suite with reporters text-summary, json-summary, json and lcov.
- Run start/completion time and exit status: final run 2026-10-21T15:36:18Z to 15:36:50Z, exit 0, 118 files and 2338 tests passed. An earlier identical full run at 15:34:57Z gave the same per-file numbers. In between, a diagnostic run (`npx ng test --watch=false --coverage --include=src/app/features/mail/read/mail-read-page.spec.ts`) checked the template artifact. The final full run then overwrote its output.
- Generated report paths and report hashes/run identifier:
  - `web/coverage/web/coverage-final.json`: sha256 5bb41c38116e991197aa1b2e9a2acea941fcd561cf2c8884a51e5eab73b7f70c, written 17:36:50 +0200.
  - `web/coverage/web/coverage-summary.json`: sha256 84dbf5b2436742b173285999220c66af5fc57ac74024712f32f815d5ea5f060c.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/` during the run (file times match the run end), and the spec hashes and web tree were unchanged before and after.
- Final run after latest test additions and production fixes: yes. It ran after all my test additions. The parent made no production fixes during my work; the rebase did not change `web/`.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/read/mail-read-page.ts` | 142/147 (96.60%) | 82/99 (82.83%) | 34/34 (100.00%) | 120/120 (100.00%) |
| `web/src/app/features/mail/read/mail-frame.ts` | 31/33 (93.94%) | 14/16 (87.50%) | 8/9 (88.89%) | 25/25 (100.00%) |
| `web/src/app/features/mail/read/image-allowlist.ts` | 22/22 (100.00%) | 11/11 (100.00%) | 9/9 (100.00%) | 17/17 (100.00%) |
| `web/src/app/features/mail/read/mail-read.service.ts` | 15/15 (100.00%) | 15/15 (100.00%) | 7/7 (100.00%) | 12/12 (100.00%) |
| `web/src/app/features/mail/list/mail-list-page.ts` | 137/138 (99.28%) | 87/95 (91.58%) | 39/40 (97.50%) | 113/113 (100.00%) |
| `web/src/app/features/mail/list/mail-shortcuts.ts` | 17/17 (100.00%) | 27/27 (100.00%) | 4/4 (100.00%) | 12/12 (100.00%) |
| `web/src/app/features/mail/mail.routes.ts` | 9/9 (100.00%) | 6/6 (100.00%) | 3/3 (100.00%) | 8/8 (100.00%) |
| `web/src/app/features/mail/list/mail-list-page.html` | 182/182 (100.00%) | 26/26 (100.00%) | 15/15 (100.00%) | 124/124 (100.00%) |
| `web/src/app/features/mail/read/mail-read-page.html` (measurement artifact, see below) | 40/192 (20.83%) | 2/36 (5.56%) | 0/10 (0.00%) | 35/124 (28.23%) |

Aggregate over the 7 TS files (summed counters): statements 373/381 (97.90%), branches 242/269 (89.96%), functions 104/106 (98.11%), lines 307/307 (100.00%).
Aggregate over all 9 measured files, including both templates: statements 595/755 (78.81%), branches 270/331 (81.57%), functions 119/131 (90.84%), lines 466/555 (83.96%). The `mail-read-page.html` artifact pulls this figure down. `.scss` and the e2e files are excluded (see above). No scoped file is missing from the report.

Template artifact: `mail-read-page.spec.ts` uses `TestBed.overrideComponent` to swap in a stub for `LinkedItems`. That makes Angular recompile the template through JIT, so the AOT template that coverage maps to `mail-read-page.html` never runs in that spec. The diagnostic run with only that spec reported 0/124 template lines, even though its 34 tests render and check headers, frame, image banner, attachments, previews and GTD buttons. The 20.83% comes only from `mail.routes.spec.ts`, which renders the loading state. The template behavior is tested, but this tooling cannot measure it without changing the spec's stubbing approach. Changing that approach would mean measuring the real `LinkedItems` instead of a stub, so I kept the existing approach.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-read-page.ts:59,82,85,89,93,225,229,271` (`??` / `\|\|` fallbacks) | Defensive defaults for missing route id, sender, message id, address or content type | Non-material: generated DTO fields that the server always sets. No test added. |
| `mail-read-page.ts:103` (empty id), `114` (showImages with no message or already loaded), `132`/`138` (no sender address), `143` (gtd while busy or without id), `174`/`203` (preview/save without id), `190` (stale preview error) | Guard clauses behind UI that is already hidden or disabled | The disabled and hidden UI is covered by new tests (GTD buttons disabled while busy, save disabled while pending, "Always load" hidden without an address, stale preview success path). The guards themselves cannot be reached through the UI. |
| `mail-read-page.ts:249` (allowlisted sender, but the message has no id) | Defensive | Non-material. Related observation about a failing proxied reload is in "Bugs found". |
| `mail-frame.ts:93` (frame without a document body), `95` (`contentDocument` gone during a resize) | Defensive | Non-material: jsdom and browsers always give an attached iframe a document. |
| `mail-frame.ts` 1 uncovered function | The arrow passed to `effect`/`onDestroy` as counted by v8 (the observer is undefined when no load happened) | Non-material. Destroy after load is covered. |
| `mail-list-page.ts:39,66,70,77,183,232,244,265` and 1 function | Code that existed before this task (select, sender, focus helpers) | Outside the task. The task's lines 206-217 and the template's dblclick are fully covered. |
| `mail-read-page.html` (see artifact note) | Template rendering | Behavior covered by the JIT-rendered spec. Not measurable as described. |

## Unable to test

- AC4, the Playwright run against the e2e stack, was not run by me, at the parent's instruction. The parent reports `npm run e2e -- --no-deps e2e/mail-read/read.spec.ts -> 1 passed`.
- In real browsers: that script execution is blocked inside the frame (sandbox and CSP enforcement) and the frame's real layout height. jsdom enforces neither sandbox nor CSP and does no layout. The unit tests check the attributes, the CSP meta tag and the fit/resize logic (with a stubbed `scrollHeight` and `ResizeObserver`). Actual enforcement is covered only by the Playwright spec. No follow-up beyond AC4.
- Template coverage numbers for `mail-read-page.html` (see "Coverage").

## Summary

Tests added and all validation passed. Coverage of the task's TypeScript files is sufficient, and I found no production defects. The remaining gaps are defensive guards, pre-existing code, and a template-coverage measurement artifact, all explained above. I report two non-blocking observations for the parent to decide on.
