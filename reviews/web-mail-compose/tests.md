# Test Report

Date: 2026-10-22

Task: web-mail-compose - Mail compose, reply, forward

Scope: `3f042d8b6159be030e20ee9566780e2affe3e108..dd03a60d7b9b7a6d50b899f3daef91ee5c944ca8` (task commits 06b177538, 8df2c8a51, 3be16cce4, 27c4e17c6, dd03a60d7 on branch main), plus the uncommitted test change listed below. This is round 2. It re-checks the final code after the fixer commit dd03a60d7 (CR-1..CR-5, TW-1, TW-2). Production code was not modified.

Requirements:
- `tasks/web-mail-compose.md`: acceptance criteria and recorded assumptions. The identity picker lists the mail accounts. Moving a stored draft to another account recreates it there and discards the old copy; this is disabled for replies and for drafts with attachments. Recipients are chips. The compose page takes `?draft=|?reply=|?replyAll=|?forward=` and replaces the URL with `?draft=`. Autosave runs every 10 s through one serial write queue, a 412 stops it, and leaving the page saves pending changes.
- SPEC.md §7.3 (via the task), docs/ARCHITECTURE.md "Coverage", AGENTS.md
- `reviews/web-mail-compose/fixes.md` and `review.md`: the behavior the fixer changed

## Test files changed

Round 2 (uncommitted):
- `web/src/app/features/mail/compose/mail-compose-page.spec.ts`

Round 1 (committed in 27c4e17c6): file-picker-dialog.spec.ts (new), mail-compose-page.spec.ts, recipient-input.spec.ts, rich-text-editor.spec.ts, mail-compose.service.spec.ts, mail.routes.spec.ts, mail-read-page.spec.ts, mail-list-page.spec.ts.

## Tests added or updated

Count: round 2 added 4 tests. The mail suite went from 392 to 396 tests. No existing test was changed, weakened or skipped. Round 1 added 54 tests, and the fixer added 10.

I reviewed the fixer's 10 new tests against the changed code. They cover the TW-1 and TW-2 regressions, a failed move reported once, a sender changed and changed back during the first save, the default identity after a move, Enter/submit not sending, `?draft=` after the first save, opened drafts and moved drafts, no URL change when the page is left, and the Files "more" note in the service and the dialog. Those tests are sound. They left these gaps, which the round-2 tests in the `sender change queued behind other writes` describe now cover:

1. `puts From back when the draft got an attachment before the queued move ran`. This is the runtime `!canChangeSender()` revert in `followSender`. The sender is changed while an upload is still running. When the queued move runs, the draft already holds an attachment. Expected result: no create, no discard, no error, and From shows the account that holds the draft again.
2. `keeps a sender picked while an earlier move was failing and moves the draft there`. This is the guard in `back()`. A move to account B is still pending when the user picks account C, and then the move to B fails. Expected result: one error, From stays on C, the draft moves to C, and the old copy is discarded once.
3. `after a failed move, Send treats the mail as sent from the account holding the draft`. This checks the fixer's claim that `ownSent()` now checks the right account. A move from IMAP to Google fails. Send then uses the old draft and its ETag, and reports "no copy in Sent", which is the IMAP behavior.
4. `sends a moved draft from its new account`. After a successful move from IMAP to Google, Send uses the moved draft's ETag, the recipients are carried over, and no Sent warning appears.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 TipTap editor | rich-text-editor.spec.ts (all) | `cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'` | Pass |
| AC1 identity picker (accounts as sender) | `sender`, `sender move and repeated clicks` describes; `keeps the sender of a stored draft with attachments` | same | Pass |
| AC1 identity picker: the draft ends up in the account shown, and Send uses it (TW-1, TW-2, CR-1) | `sender changes racing a save` (5 tests), `sender change queued behind other writes` (4 tests) | same | Pass |
| AC1 to/cc/bcc with autocomplete (recent correspondents) | recipient-input.spec.ts; Cc/Bcc tests in the page spec; service `asks for recent correspondents...` | same | Pass |
| AC1 attachments (upload or from Files), including the Files "more than 200" note (CR-5) | `attachments`, `failures` describes; file-picker-dialog.spec.ts incl. `says when a folder holds more entries than listed...`; service `says when a drive folder holds more entries than listed` | same | Pass |
| AC1 autosave every 10 s | `autosave`, `writes in flight`, `shows a failed save and retries it on the next tick` | same | Pass |
| AC1 send; Enter does not send (CR-2) | `send` describe; `sends only once when Send is pressed twice`; Sent-copy tests; `Send button and Enter` | same | Pass |
| AC1 reply/forward entry points; `?draft=` in the URL (CR-3) | `reply, forward and drafts`; `draft in the URL` (4 tests); read-page link test; list-page Compose link; routes compose test | same | Pass |
| AC2 Vitest covers autosave timing (fake timers) and recipient chips | page spec `autosave` (vi.useFakeTimers); recipient-input.spec.ts chip tests | same | Pass |
| AC2 `cd web && npm run check` passes | whole web suite | not run in round 2 | Not run: the parent runs it on the final code. The fixer reports a pass on dd03a60d7 (3161 tests, build OK). Round 2 only added 4 tests. `ng lint --max-warnings=0` and `prettier --check src/app/features/mail` pass. |
| AC3 Playwright reply with a Files attachment received by GreenMail | `web/e2e/mail-compose/compose.spec.ts` | not run | Not run: the parent runs the e2e stack, as instructed |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include 'src/app/features/mail/compose/mail-compose-page.spec.ts'`, then `cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'`

Result:

Pass. The page spec ran 60/60. The mail suite ran 18 files, 396/396 passed. On the first try, two of my new tests failed because of a test mistake: `mockResolvedValueOnce` on `send` bypasses the fake's `sends` log. I fixed them to assert on the spy, and no production defect was involved.

## Full test execution

Command:

`cd web && npx ng lint --max-warnings=0`; `cd web && npx prettier --check src/app/features/mail`

Result:

Pass. "All files pass linting." and "All matched files use Prettier code style!". The full `npm run check` was not run in round 2 (see the AC2 row). Server and Android were not run, because nothing changed there and sub-agents do not run Gradle.

## .taskcheck

Result:

Not run. It includes Gradle and is left to the parent, as instructed.

## Bugs found

None in round 2. TW-1 and TW-2 from round 1 are fixed in dd03a60d7, and their tests pass unchanged.

Observation, not a finding (no requirement covers it): if a never-saved message is sent directly, `send` creates the draft. `apply` then calls `showInUrl` and replaces the URL with `?draft=` and the new draft id just before the page navigates to `/mail`. Browser Back may then lead to a draft that no longer exists after sending. A draft that was autosaved before sending already has this URL, so the behavior is consistent. The parent may decide whether it matters.

## Coverage

Status: verified (unfiltered run, all 396 tests passed, exit 0)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: the five compose `.ts` files are new in this task, so every line in them counts as changed. `mail.routes.ts` has 5 added lines, and all of its executable lines are covered (9/9). Changed executable TS lines: 454/454 covered, from lcov LF/LH. Changed-branch coverage was not measured separately. Angular templates (`*.html`, including the CR-2 change to `mail-compose-page.html`) are compiled into their components and are not instrumented as separate files. The template change is exercised by `Send button and Enter`.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-mail-compose`, base `3f042d8b6159be030e20ee9566780e2affe3e108`, HEAD `dd03a60d7b9b7a6d50b899f3daef91ee5c944ca8`. The parent supplied the base and the fixer commit. The commit list is from `git log 3f042d8b..HEAD`, and the production files match round 1 plus the fixer diff.
- Source/test snapshot identifier (including uncommitted inputs): sha256 of the production files at HEAD: mail-compose-page.ts `82786cff…1709`, mail-compose-page.html `924f9ad9…4249`, file-picker-dialog.ts `872a80da…be5e`, mail-compose.service.ts `5c328e17…0395`, recipient-input.ts `9404277b…5579`, rich-text-editor.ts `922f9249…f21f`, mail.routes.ts `2d9d58f6…5260`. The only uncommitted input is mail-compose-page.spec.ts, sha256 starting `bf7a31f30456bffa9` (after prettier). The other specs are as committed.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/compose/{file-picker-dialog,mail-compose-page,mail-compose.service,recipient-input,rich-text-editor}.ts` and `web/src/app/features/mail/mail.routes.ts`. Excluded: `*.spec.ts`, `*.scss`, `*.html` (templates, not instrumented separately), `web/e2e/**` (Playwright, not unit-instrumented), and `reviews/**`. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest v5.0.5 with v8 (`@vitest/coverage-v8`). Command `npm run coverage -- --include 'src/app/features/mail/**/*.spec.ts'`, run in `web/`. 18 spec files, 396 tests, no filter.
- Run start/completion time and exit status: 2026-10-22T09:24:53+02:00 to 09:25:04+02:00, all tests passed (exit 0).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (mtime 09:25:04.88, sha256 `c30e047146edf90cb14a6d112bd21954180019c9f62cb82625bc2f48c8ee6dc4`) and `web/coverage/web/lcov.info` (mtime 09:25:04.95, sha256 `7fd5e941ea7598ce9c0e7530726bef1680ead85a85100eee76e424e862715ca1`). The counters were read with the scratch script `.tmp/cov.mjs` (istanbul `s`/`b`/`f` counters; lines from lcov LF/LH).
- Evidence outputs are fresh and inputs stayed unchanged: both report files were written at the end of this run, and they differ from the round-1 hashes. All input hashes were the same before and after the run.
- Final run after latest test additions and production fixes: yes. The run used the fixed code dd03a60d7 and all round-2 tests.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/compose/mail-compose-page.ts` | 248/250 (99.2%) | 116/130 (89.2%) | 68/68 (100%) | 213/213 (100%) |
| `web/src/app/features/mail/compose/file-picker-dialog.ts` | 100/100 (100%) | 24/25 (96.0%) | 11/11 (100%) | 59/59 (100%) |
| `web/src/app/features/mail/compose/mail-compose.service.ts` | 28/28 (100%) | 19/21 (90.5%) | 14/14 (100%) | 24/24 (100%) |
| `web/src/app/features/mail/compose/recipient-input.ts` | 122/125 (97.6%) | 36/42 (85.7%) | 30/32 (93.8%) | 86/86 (100%) |
| `web/src/app/features/mail/compose/rich-text-editor.ts` | 79/82 (96.3%) | 27/30 (90.0%) | 18/19 (94.7%) | 63/63 (100%) |
| `web/src/app/features/mail/mail.routes.ts` | 10/10 (100%) | 6/6 (100%) | 4/4 (100%) | 9/9 (100%) |
| **Total (summed counters, 6 files, none missing)** | 587/595 (98.7%) | 228/254 (89.8%) | 145/148 (98.0%) | 454/454 (100%) |

All branches in the new fixer code are covered: `followSender` (early returns, the runtime `!canChangeSender()` revert, the `back()` guard, a failed create), `showInUrl` (same id, left, new id), `changeSender` `.catch`, and `files()` `more`.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-compose-page.ts:278` (`save` when finished), `:418` (`attach` while busy) | Guards against saving after send/discard and against a second attach | Left uncovered. A test can't reach these from the UI without poking internals: the buttons are disabled while busy, and the timer is cleared once the draft is finished. |
| `mail-compose-page.ts` branches 109, 113, 202, 320, 325, 326, 335, 338, 339, 343, 482, 496 | `?? null`/`?? ''`/`?? []` defaults for server fields that are absent, `draftAccountId` fallback when `accountId` is absent, empty display name | Left uncovered. These are defensive defaults, and the generated types mark these fields as required. |
| `recipient-input.ts:154-155` (template paste/blur listener), `:180` (Enter while the panel is open), branches 52, 111, 187, 195 | Enter on a highlighted suggestion | Left uncovered. `adds the highlighted suggestion, not the typed text, on Enter` verifies the behavior, but jsdom's key event ordering never reaches this guard branch. |
| `rich-text-editor.ts:122`, `:159`, `:185`, branch 150 | Guards that only matter before the first render | Left uncovered: they can't be reached after the first render. |
| `mail-compose.service.ts:114,117` branches (`items ?? []`, `name ?? ''`) | Correspondents list absent | Left uncovered: defensive defaults. |
| `file-picker-dialog.ts:115` branch (class-level decorator/tracking fallback) | Generated template helper | Left uncovered: no behavior depends on it. |

## Unable to test

- AC3 (Playwright `e2e/mail-compose/compose.spec.ts` against the e2e stack, received by GreenMail): not run, as instructed. The parent runs it. The spec clicks Send by role and name, so the CR-2 change to `type="button"` does not affect it.
- `.taskcheck` and the full `npm run check`: not run in round 2. The parent must run them on the final code.
- Real browser history after the `?draft=` URL replacement, and real Enter key implicit submission: verified only in jsdom, which dispatches a submit event. The e2e run is the browser check.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. Round 2 added 4 tests for the queued sender move (runtime revert, the `back()` guard, and Send after a failed or a successful move). The mail suite passes 396/396. Task-scoped coverage is 587/595 statements, 228/254 branches, 145/148 functions and 454/454 lines.
