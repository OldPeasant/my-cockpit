# Test Report

Date: 2026-11-14

Task: web-mail-task-archive - Web: mail → Next action & archive (read view, New view, mail list)

Scope: task commits on base `ddb9b8132693de81e4123adb6936175ecb73456f` (`git merge-base HEAD origin/main`):
`2657422cb` (mail list), `c0cad1a35` (read view, New view, locale), `4be43e6cc` (shortcut spec),
`db10d9b2c` (review report only), `bcadfd88c` (fixer: CR-1, CR-3, CR-4, CR-5, plus the first round of
these tests). Measured HEAD: `bcadfd88c545519c14f400e3be1c6e6f89e62502` plus the uncommitted test
changes listed below. The first commits were originally `8996e4867`, `21bace4a3`, `9a8d7b66e` on base
`e9a096d18`. After the rebase, `git diff 9a8d7b66e 593192c1e -- web` was empty (same web tree).

Requirements:
- `tasks/web-mail-task-archive.md` acceptance criteria
- `SPEC.md` §7.3 (GTD integration, "→ Next action & archive") and §7.4 (`POST /api/v1/mail/messages/to-tasks`)
- `docs/ARCHITECTURE.md` "Coverage"
- Coordinator instructions (CR-1 single-message behavior, threaded conversations, partial chunk failure)

## Test files changed

- `web/src/app/features/mail/list/mail.service.spec.ts`
- `web/src/app/features/mail/list/mail-list-store.spec.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`
- `web/src/app/features/mail/list/mail-testing.ts` (test double: `FakeMail.toNextActions`, `toTaskResult`)
- `web/src/app/features/mail/read/mail-read-page.spec.ts`
- `web/src/app/features/new/new-page.spec.ts`

The first round is already in commit `bcadfd88c`. The parent committed it, not the test-writer. The
second round (CR-1 assertion, threaded, partial failure, extra read view cases, Prettier fix of
new-page.spec.ts) is uncommitted in the five spec files above.

## Tests added or updated

Count: 69 new tests (8 service, 18 store, 15 list page, 20 read view, 8 New view). No existing test
was changed or removed.

## Requirement coverage

Focused command (from `web/`): `npx ng test --watch=false --include` for the five specs plus
`mail-shortcuts.spec.ts`. Full command: `npm run coverage` (full suite).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 read view: button right after "→ Inbox task" (unchanged), `Shift+T` | `MailReadPage > → Next action & archive`: "sits right after "→ Inbox task"...", "runs with Shift+T, but not with t or as a chord or repeat", "ignores Shift+T while typing in an input", "is not run before the message has loaded", "is listed in the keyboard help" | focused + full | Pass |
| AC1 read view: success opens the task detail (`replaceUrl`), Back returns to where the mail was opened from | "creates the Next action, then opens its detail in place of the message"; harness `MailReadPage Archive, Spam and Delete: back to where it was opened from > → Next action & archive`: "opens the task in place of the message, so Back returns to the folder", "opens the task when the archive failed, and Back returns to the New view" | focused + full | Pass |
| AC1 read view: no double run, no triage while it runs (CR fix), no navigation after leaving | "is disabled while it runs...", "blocks Archive, Spam, Delete, Mark unread, Star and Move while it runs", "is disabled while an archive runs", "does not navigate when the page was left while it ran", "does not leave a message the user moved to while it ran" | focused + full | Pass |
| AC2 New view: mail rows offer the action, `Shift+T` on the cursor row, same result | `NewPage > → Next action & archive`: "is offered on mail rows only, with Shift+T in its title", "creates a Next action, archives the mail and opens the task detail", "keeps the New view in the history, so Back returns to it", "runs on the cursor row with Shift+T, not on a task row, not with modifiers", "sends once while it runs, and blocks "→ Inbox task" on the same mail" | focused + full | Pass |
| AC3 mail list: several mails, one Next action each, archived ones leave, list stays, "N next actions created" links to Next Actions | `MailListPage > → Next action & archive`: "creates one Next action per selected mail, archives them and stays in the list", "opens Next Actions from the message" (snack bar action navigates to `/next`), "opens the task of a single mail (the one under the cursor) with Shift+T", "is disabled while it runs...", "is disabled in an empty folder", "does not run on t, as a chord, repeated or while typing"; `MailListStore > next action & archive` (list order, cursor, selection, live-update interplay) | focused + full | Pass |
| AC3 threaded view: one task per row, rest of the conversation in the folder archived, refetch on failure (CR fix) | `MailListStore > next action & archive > in the threaded view`: "makes one task per row and archives the rest of its conversation in the folder", "leaves the conversation of a mail whose archive failed alone", "sends no archive when the conversation has no other message in the folder", "sends no archive for single messages", "refetches the list when archiving the rest of a conversation failed", "refetches the list when the conversation could not be looked up", "does not archive conversations in the flat view" | focused + full | Pass |
| AC4 archive failure: task kept, single mail detail still opens, error names the mail | read: "still opens the task when the archive failed, with an error naming the mail", "names the error code when the failed archive has no message, and a subjectless mail", "falls back to general words when the failed archive has no code or message"; New: "still opens the task when the archive failed..."; list: "still opens the task of a single mail whose archive failed" | focused + full | Pass |
| AC4 several mails (CR-1): one error, no success snack bar, names every mail not archived | list: "keeps a mail whose archive failed in the list, with an error naming it" (`2 next actions created. Not archived: “Lunch”.`), "names every mail not archived in the one error"; store: "keeps a mail whose archive failed in the list, still selected, and reports both" | focused + full | Pass |
| AC4 failed task creation: mail stays, error shown | read: "stays on the message with the API error when the task could not be created", "reports an unexpected failure or a missing result in general words", harness "stays on the message when the task could not be created"; New: "stays with the mail in place and the error...", "reports an unexpected failure or a missing result..."; list: "leaves the mails in place with the error when the request fails", "reports an unexpected failure in general words", "does nothing more when the server returned no result"; store: "throws a failed request and leaves the mails in place" | focused + full | Pass |
| AC4 partial chunk failure (CR fix): earlier requests' tasks kept and reported, the rest stays | service: "keeps the earlier results when request 2 fails..." (201 ids, third request fails, 200 results kept), "...request 1 fails..." (100 kept), "rejects a failed first request with its own error: no task was created"; store: "keeps what a partly failed run created: archived mails leave, the rest stays" (`NextActionsPartlyFailedError`); list: "reports created tasks and the mails left without one when a later request failed", "does not open the one created task when other mails got none" | focused + full | Pass |
| Service contract: `POST /api/v1/mail/messages/to-tasks`, `{ids, list: NEXT, archive: true}`, silent errors, at most 100 ids per request | `MailService > next action & archive`: "posts the ids as Next actions to archive, silently, and returns the results", "sends at most 100 ids per request, in order, and joins the results", "sends exactly 100 ids in one request", "sends no request for no ids", "rejects a failed request with the ApiError, without a toast" | focused + full | Pass |
| AC5 task detail's linked items show the mail; opening it shows the archived mail | none in this scope | n/a | Not testable here: link resolution is server behavior (`mail-task-archive-api`) and the task detail is another feature; needs an e2e run against the real stack |
| AC6 strings in English + German; shortcut in the keyboard help | read and list "listed in the keyboard help" tests above; existing `mail-shortcuts.spec.ts` (updated by the task); `npm run build:de` | `npm run build:de` | Pass (German build fails on missing translations, it succeeded with no warnings except the bundle budget) |
| AC7 unit tests cover the new behavior; `npm run check` passes | all of the above | `npm run check` | Pass. `sh scripts/check.sh` not run (it includes Gradle; the parent said not to run Gradle) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/mail/list/mail-list-store.spec.ts --include src/app/features/mail/list/mail-list-page.spec.ts --include src/app/features/new/new-page.spec.ts --include src/app/features/mail/list/mail.service.spec.ts --include src/app/features/mail/read/mail-read-page.spec.ts --include src/app/features/mail/list/mail-shortcuts.spec.ts`

Result:

Pass: 6 files, 579 tests (before the last three gap tests). Then store and read specs together:
2 files, 322 tests passed.

## Full test execution

Command:

`cd web && npm run check` (api:check, `ng lint --max-warnings=0`, e2e typecheck, `ng test --watch=false`, `ng build`)

Result:

Pass, exit 0 on the final test state: lint clean, 196 files, 4884 tests passed, build OK. The only
warning is the initial bundle budget, which also shows up without this task's changes.

## .taskcheck

Result:

Not run: `.taskcheck` is `sh scripts/check.sh`, which runs Gradle for server and android, and the
parent said not to run Gradle. The web part of it (`npm run check`) passed.

## Bugs found

None open. During the first round I saw three weak spots: a later chunk failure discarded the
earlier chunks' results, the threaded view archived only the listed message of a conversation, and
the read view could triage while the next action ran. The fixer was already working on all three
(CR findings), and its commit `bcadfd88c` fixes them. The second round tests them (see the AC3
threaded, AC4 partial, and AC1 "blocks Archive..." rows), and they pass.

## Coverage

Status: verified (fresh run on a stable snapshot, all tests passing).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. Method: added lines from `git diff -U0 ddb9b8132..HEAD` intersected
with lcov `DA` (executable) lines of the same file. Removed lines are not counted. Branch coverage is
not inferred from it.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-mail-task-archive;
  base `ddb9b8132693de81e4123adb6936175ecb73456f` (merge-base with origin/main after the rebase;
  originally `e9a096d18`); HEAD `bcadfd88c545519c14f400e3be1c6e6f89e62502`; task commits listed under
  Scope; file scope from `git diff --name-only ddb9b8132..HEAD -- web/src`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD above plus uncommitted test files
  (sha256): mail-list-page.spec.ts `c12ae46fcf0bd1bca0ea13661c233881a89be59c03a307860249c6e5a914f5a0`,
  mail-list-store.spec.ts `f5b5c170cca340f4a1bcb84785d72c2ca32f4038364d17154488e75d6d86297f`,
  mail.service.spec.ts `3772ed0816728585c2d1478a046bc26ce9d5310df8b009828eecf7f75dd08ec3`,
  mail-read-page.spec.ts `32c5c5a007ba71506d34ddb9f608d7a43db61ce3bee8aaa77d50966264bde65e`,
  new-page.spec.ts `bbeb4b69deac22d9e7c275406e980167da48e1effc2af4d9fa0d900ffa3d8192`. No uncommitted production changes.
- Exact included files: `web/src/app/features/mail/list/{mail-list-page,mail-list-store,mail-next-action,mail-shortcuts,mail.service}.ts`,
  `web/src/app/features/mail/read/mail-read-page.ts`, `web/src/app/features/new/new-page.ts`.
  Excluded: the templates `mail-list-page.html`, `mail-read-page.html`, `new-page.html` (compiled into
  the components, not instrumented as files by v8; their bindings are exercised by the DOM tests above);
  `web/src/locale/*.xlf` (data, checked by `npm run build:de`); spec files and `mail-testing.ts` (tests).
  No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8`;
  `npm run coverage` in `web/`; full unit suite (196 files).
- Run start/completion time and exit status: 2026-11-14T10:22:25Z (snapshot) to 10:23:25Z, 4884 tests passed, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json`
  (sha256 `91e4d10341598ac7a611fd2761cb8e4326398d8f68555e261ad9da7c00eb76fc`), `web/coverage/web/lcov.info`
  (sha256 `a14930283b4cd142ad5f0ee4132d16ecf6753bb424f8a5c6396917b9d26679cf`). Counters read with a scratch
  script in `.tmp/` (Git-ignored).
- Evidence outputs are fresh and inputs stayed unchanged: Vitest rewrote `coverage/` in this run. HEAD
  and the test file hashes matched before and after the run.
- Final run after latest test additions and production fixes: yes (after fixer commit `bcadfd88c` and
  all test additions).

Statements, branches and functions come from `coverage-final.json`. Lines come from lcov `DA`. Values
are covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines | Changed lines |
|---|---:|---:|---:|---:|---:|
| `web/src/app/features/mail/list/mail-list-page.ts` | 292/293 (99.7%) | 201/209 (96.2%) | 72/73 (98.6%) | 246/246 (100.0%) | 33/33 (100.0%) |
| `web/src/app/features/mail/list/mail-list-store.ts` | 438/438 (100.0%) | 219/232 (94.4%) | 98/98 (100.0%) | 352/352 (100.0%) | 43/43 (100.0%) |
| `web/src/app/features/mail/list/mail-next-action.ts` | 13/13 (100.0%) | 7/7 (100.0%) | 5/5 (100.0%) | 12/12 (100.0%) | 12/12 (100.0%) |
| `web/src/app/features/mail/list/mail-shortcuts.ts` | 24/24 (100.0%) | 27/27 (100.0%) | 8/8 (100.0%) | 17/17 (100.0%) | N/A (changed lines are data, not executable) |
| `web/src/app/features/mail/list/mail.service.ts` | 47/47 (100.0%) | 35/36 (97.2%) | 18/18 (100.0%) | 41/41 (100.0%) | 15/15 (100.0%) |
| `web/src/app/features/mail/read/mail-read-page.ts` | 320/326 (98.2%) | 204/221 (92.3%) | 66/67 (98.5%) | 261/261 (100.0%) | 17/17 (100.0%) |
| `web/src/app/features/new/new-page.ts` | 143/145 (98.6%) | 101/103 (98.1%) | 36/37 (97.3%) | 109/109 (100.0%) | 15/15 (100.0%) |
| Total (summed counters, 7 files; templates and locale files excluded as above) | 1277/1286 (99.3%) | 794/835 (95.1%) | 303/306 (99.0%) | 1038/1038 (100.0%) | 135/135 (100.0%) |

The v8 lcov line data marks every line as hit even where a statement on it did not run. Statements
are the stricter measure here.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-list-store.ts:567` (conversation with no other message in the folder) | threaded archive of the rest of a conversation | Covered now: "sends no archive when the conversation has no other message in the folder" |
| `mail-read-page.ts:247` (session removal rejects) | the task still opens if the saved list cannot be updated | Covered now: "still opens the task when updating the list it was opened from fails" |
| `mail-next-action.ts:13` (no archive detail and no code) | the error still says which mail was not archived | Covered now: "falls back to general words when the failed archive has no code or message" |
| Remaining uncovered statements and branches in the 7 files | none on lines this task added or changed (changed-branch check from `branchMap` found none) | Code that existed before this task in the same files; outside this task's scope |

## Unable to test

- AC5 (the task detail's linked items show the mail; opening it shows the archived mail): this depends
  on server link resolution and the task detail feature. It is not covered by web unit tests in this
  scope. Follow-up: an e2e run against the real stack, if the parent wants evidence beyond the server
  task's tests.
- `sh scripts/check.sh` / `.taskcheck`: not run because it includes Gradle (the parent said not to
  run Gradle). The parent should run it.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient and no production defects
are open. The weak spots seen in round one are fixed in `bcadfd88c` and covered by tests.
