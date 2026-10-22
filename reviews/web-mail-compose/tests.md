# Test Report

Date: 2026-10-22

Task: web-mail-compose - Mail compose, reply, forward

Scope: `3f042d8b6159be030e20ee9566780e2affe3e108..8df2c8a51d8d70181feaac742153d15f0af43b95` (task commits 06b177538, 8df2c8a51 on branch main), plus the uncommitted test changes listed below. Production code was not modified.

Requirements:
- `tasks/web-mail-compose.md` acceptance criteria and recorded assumptions (identity picker = mail accounts; recipients as chips, invalid ones kept red, left out of autosaves and blocking Send; compose page with `?draft=|?reply=|?replyAll=|?forward=`; 10 s fixed-interval autosave through one serial write queue, 412 stops autosave, leaving saves pending changes; images shown as placeholders)
- SPEC.md §7.3 (via the task), docs/ARCHITECTURE.md "Coverage", AGENTS.md

## Test files changed

- `web/src/app/features/mail/compose/file-picker-dialog.spec.ts` (new)
- `web/src/app/features/mail/compose/mail-compose-page.spec.ts`
- `web/src/app/features/mail/compose/recipient-input.spec.ts`
- `web/src/app/features/mail/compose/rich-text-editor.spec.ts`
- `web/src/app/features/mail/compose/mail-compose.service.spec.ts`
- `web/src/app/features/mail/mail.routes.spec.ts`
- `web/src/app/features/mail/read/mail-read-page.spec.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`

## Tests added or updated

Count: 54 added (mail suite 328 -> 382 tests); no existing test changed, weakened or skipped.

- mail-compose-page (27): edits made during an in-flight save are saved on the next tick with the new ETag; a tick during a slow save waits (never two saves at once); Send pressed twice sends once; Discard pressed twice discards once; discard waits for a running save and uses its ETag; failed autosave shows "Not saved: ..." and is retried on the next tick; unexpected save error; upload failure reported, busy cleared, next upload works; draft creation failure before upload; failed attach from Files; load failure + retry; discard failure; send failing in its pre-save; discard of a never-saved message; cancelled Files dialog / empty file list; "no copy in Sent" message for IMAP vs. GOOGLE; send of an unchanged stored draft without re-save; Cc/Bcc shown for a stored draft and Bcc saved; Cc/Bcc toggle; inline parts hidden from the list but kept on remove; sender locked for a stored draft with attachments; nothing saved when leaving unchanged; autosave stops after leaving; moved draft kept when the old copy cannot be deleted; 2 defect tests (see Bugs found).
- file-picker-dialog (8): root listing, folder navigation and breadcrumbs, empty folder, close with the chosen file, Cancel, ApiError and generic error, stale success and stale failure of a folder already left are ignored.
- recipient-input (6): no chip for empty/duplicate (case-insensitive) input; single-address paste left to the input; failed suggestion lookup; out-of-order suggestion answers; pending lookup dropped once a chip was added; Enter adds the highlighted suggestion, not the typed text.
- rich-text-editor (7): Bold/Italic/Underline/Numbered list/Quote from the toolbar; toolbar mousedown keeps the selection; image without alt shows `[image]`.
- mail-compose.service (2): accounts list; draft answer without id rejected.
- mail.routes (2): `/mail/compose` loads the compose page (title "Compose", accounts requested); list matcher does not take `compose`.
- mail-read-page (1): Reply / Reply all / Forward hrefs. mail-list-page (1): Compose href.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 TipTap editor | rich-text-editor.spec.ts (all 11) | `npm --prefix web exec -- ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'` | Pass |
| AC1 identity picker (accounts as sender) | `sender` and `sender move and repeated clicks` describes; `keeps the sender of a stored draft with attachments` | same | Pass |
| AC1 identity picker: draft ends in the account shown | `sender changes racing a save` (2 tests) | same | Fail (TW-1, TW-2) |
| AC1 to/cc/bcc with autocomplete (recent correspondents) | recipient-input.spec.ts; `shows Cc/Bcc of a stored draft...`, `opens the Cc/Bcc fields...`; service `asks for recent correspondents...` | same | Pass |
| AC1 attachments (upload or from Files) | `attachments` and `failures` describes; file-picker-dialog.spec.ts | same | Pass |
| AC1 autosave every 10 s | `autosave`, `writes in flight`, `shows a failed save and retries it on the next tick` | same | Pass |
| AC1 send | `send` describe; `sends only once when Send is pressed twice`; Sent-copy messages | same | Pass |
| AC1 reply/forward entry points | `reply, forward and drafts`; read-page link test; list-page Compose link; routes compose test | same | Pass |
| AC2 Vitest covers autosave timing (fake timers) and recipient chips | mail-compose-page.spec.ts `autosave` (vi.useFakeTimers), recipient-input.spec.ts chip tests | same | Pass |
| AC2 `cd web && npm run check` passes | whole web suite | `npm --prefix web run check` | Fail: only the 2 TW-1/TW-2 tests fail (3147 passed, 2 failed); `ng build` step not reached |
| AC3 Playwright reply with Files attachment received by GreenMail | `web/e2e/mail-compose/compose.spec.ts` | not run | Not run: the parent runs the e2e stack (instruction) |

## Focused test execution

Command:

`npm --prefix web exec -- ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'` (plus per-file runs while writing)

Result:

Fail: 380 passed, 2 failed (the two TW-1/TW-2 defect tests in `mail-compose-page.spec.ts > sender changes racing a save`).

## Full test execution

Command:

`npm --prefix web run check` (api:check, lint, e2e:typecheck, test:ci, ng build)

Result:

Fail: api:check, lint (`All files pass linting.`) and e2e:typecheck passed; test:ci 155 files, 3147 passed, 2 failed (the same two defect tests); `ng build` not reached. Prettier `--check web/src/app/features/mail` passes. Server and Android suites not run (no change there; sub-agents do not run Gradle).

## .taskcheck

Result:

Not run (runs Gradle; left to the parent per instruction). Its web part (`npm run check`) fails on the two defect tests until TW-1/TW-2 are fixed.

## Bugs found

### Failed move of a stored draft to another account is silent and leaves the wrong sender shown

Finding ID:

`TW-1`

Test:

`MailComposePage > sender changes racing a save > reports a failed move to another account and keeps showing the account holding the draft`

Production source:

`web/src/app/features/mail/compose/mail-compose-page.ts:178-193` (`changeSender`)

Requirement:

AC1 identity picker; assumption "Moving a stored draft to another account recreates it there and discards the old copy". Error paths of a write must be reported (like save/upload failures).

Expected behavior:

When `create` in the other account fails, the user is told (error notification or "Not saved" state) and the sender shown stays the account that still holds the draft.

Actual behavior:

`accountId` is set to the new account before the write; when `service.create` rejects, nothing is reported (`void this.write(...)` drops the rejection, `saveState` untouched) and the From field keeps showing the new account while the draft, later autosaves and Send stay in the old account.

Severity:

Medium

Fix required:

`Yes - production code must be fixed by the parent worker / fixer.`

### Sender changed while the first autosave is running: mail is sent from the old account

Finding ID:

`TW-2`

Test:

`MailComposePage > sender changes racing a save > stores the draft in the account picked while its first save was running`

Production source:

`web/src/app/features/mail/compose/mail-compose-page.ts:178-186` (`changeSender`, the `!this.stored()` branch) and `apply` (line 335)

Requirement:

AC1 identity picker: the sender the user picked is the one the mail is sent from.

Expected behavior:

If the sender changes while the first `create` is in flight, the draft ends up in the newly picked account (moved after the create, or the change queued), or the picker reverts; From and the stored draft's account agree.

Actual behavior:

`changeSender` sees `stored() === false`, only sets `accountId`; the in-flight create stores the draft in the first account; `apply` does not reconcile `accountId`. Afterwards From shows the second account, but autosave updates and Send use the draft in the first account (the test sends and the draft's `accountId` is the first account). The From select is only disabled while `busy()`, which an autosave does not set.

Severity:

Medium (mail sent from an account other than the one shown; needs a change during the short save window)

Fix required:

`Yes - production code must be fixed by the parent worker / fixer.`

## Coverage

Status: verified (with disclosed limitation: the final coverage run excludes the 2 failing defect tests via `--filter`, because Vitest writes no coverage report for a failing run; the unfiltered run fails only on those 2 tests).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: the five compose `.ts` files are new in this task, so their changed lines are the whole files; in `mail.routes.ts` (5 added lines) every executable line is covered (9/9). Changed executable TS lines: 428/428 covered (from lcov `DA` data). Changed-branch coverage not separately measured. Angular templates (`*.html`) are compiled into their components and are not instrumented as separate files.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-mail-compose`, base `3f042d8b6159be030e20ee9566780e2affe3e108`, HEAD `8df2c8a51d8d70181feaac742153d15f0af43b95`, commits 06b177538 and 8df2c8a51 given by the parent and matched with `git diff --stat 3f042d8b..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): production unchanged at HEAD (mail-compose-page.ts sha256 `ff23061b...eb7`, file-picker-dialog.ts `0bc563dc...3f`, mail-compose.service.ts `3058d61d...a8`, recipient-input.ts `9404277b...79`, rich-text-editor.ts `922f9249...1f`, mail.routes.ts `2d9d58f6...05`). Uncommitted specs: file-picker-dialog.spec.ts `a7ca620c...26`, mail-compose-page.spec.ts `c452ac8c...7a`, mail-compose.service.spec.ts `3bf1d01c...3f`, recipient-input.spec.ts `dba71df0...1f`, rich-text-editor.spec.ts `fa5bd4b6...00`, mail.routes.spec.ts `30bb155f...f2`, mail-read-page.spec.ts `aa3ebba4...7c`, mail-list-page.spec.ts `17002f26...50`.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/compose/{file-picker-dialog,mail-compose-page,mail-compose.service,recipient-input,rich-text-editor}.ts`, `web/src/app/features/mail/mail.routes.ts`. Excluded: `*.spec.ts`, `*.scss`, `*.html` (templates, not separately instrumented; read/list template links covered by the two link tests), `web/e2e/**` (Playwright spec and helper, not unit-instrumented). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 + v8 (`@vitest/coverage-v8`), `npm --prefix web run coverage -- --include 'src/app/features/mail/**/*.spec.ts' --filter '^(?!.*sender changes racing a save)'`, run with cwd = repo root (`ng` runs in `web/`); 18 spec files, 380 passed, 2 skipped by the filter.
- Run start/completion time and exit status: 2026-10-22T09:15:37+02:00 to 09:15:44+02:00, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 `988892a4bb4a15062e9f10604a689b873f045ff981ecde5a09afc6d269dbdecc`), `web/coverage/web/lcov.info` (sha256 `e5215ec18bade90a580b951d18bc94457735265c489f0c8dda14cc1ae4220078`); counters read with a scratch script `.tmp/cov.mjs` (istanbul `s`/`b`/`f` counters, lines from lcov LF/LH).
- Evidence outputs are fresh and inputs stayed unchanged: the report directory is rewritten per run (it was absent after the failing unfiltered run); input hashes identical before and after.
- Final run after latest test additions and production fixes: yes for the test additions; no production fixes have been made yet (TW-1/TW-2 open), so coverage must be re-measured after the fix.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/compose/mail-compose-page.ts` | 225/227 (99.1%) | 100/113 (88.5%) | 64/64 (100%) | 195/195 (100%) |
| `web/src/app/features/mail/compose/file-picker-dialog.ts` | 92/92 (100%) | 22/23 (95.7%) | 11/11 (100%) | 52/52 (100%) |
| `web/src/app/features/mail/compose/mail-compose.service.ts` | 27/27 (100%) | 19/21 (90.5%) | 14/14 (100%) | 23/23 (100%) |
| `web/src/app/features/mail/compose/recipient-input.ts` | 122/125 (97.6%) | 36/42 (85.7%) | 30/32 (93.8%) | 86/86 (100%) |
| `web/src/app/features/mail/compose/rich-text-editor.ts` | 79/82 (96.3%) | 27/30 (90.0%) | 18/19 (94.7%) | 63/63 (100%) |
| `web/src/app/features/mail/mail.routes.ts` | 10/10 (100%) | 6/6 (100%) | 4/4 (100%) | 9/9 (100%) |
| **Total (summed counters, 6 files, none missing)** | 555/563 (98.6%) | 210/235 (89.4%) | 141/144 (97.9%) | 428/428 (100%) |

Baseline before the new tests (same command without filter, existing specs only): compose page 204/227 statements, file-picker-dialog 4/92, total 428/563 statements, 162/235 branches, 117/144 functions, 353/428 lines.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-compose-page.ts:276` (`save` when finished), `:375` (`attach` while busy) | guards against saving after send/discard and double attach | not reachable from the UI in a way a test can hit without poking internals (buttons are disabled while busy; the timer is cleared on finish); left |
| `mail-compose-page.ts` branches 109, 113, 200, 322-343, 439-457 | `?? null`/`?? ''`/`?? []` defaults for absent server fields | defensive defaults; the generated types mark these fields required; left |
| `recipient-input.ts:154-155` (template paste/blur listener), `:180` (Enter while panel open, chip-input side) | Enter on a highlighted suggestion | behavior verified by `adds the highlighted suggestion, not the typed text, on Enter`; jsdom key event ordering does not reach this guard branch; left |
| `rich-text-editor.ts:159` (toggle before the editor exists), `:185` (link before the editor exists), `:122` | pre-render guards | not reachable after first render; left |
| `mail-compose.service.ts:105,108` (`items ?? []`, `name ?? ''`) | absent correspondents list | defensive defaults; left |
| `file-picker-dialog.ts:110` branch | template tracking fallback | left |

## Unable to test

- AC3 (Playwright `e2e/mail-compose/compose.spec.ts` against the e2e stack, GreenMail receipt): not run here by instruction; the parent runs it.
- `.taskcheck` (`sh scripts/check.sh`): not run (includes Gradle, which this helper does not run); the parent must run it after TW-1/TW-2 are fixed.
- Real drag/paste/clipboard and TipTap rendering in a browser: covered by jsdom only; the e2e spec is the browser check.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1, TW-2: sender changes in `changeSender`). All other new and existing tests pass; task-scoped coverage 555/563 statements, 210/235 branches, 141/144 functions, 428/428 lines.
