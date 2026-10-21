# Test Report

Date: 2026-10-21

Task: web-settings-backup - Settings: backup and restore (second round, final revision)

Scope: base `6d9427e051a410ca8bd1e812740439bddff71b89` (merge-base with origin/main, from the parent)..HEAD
`be1769eeffe019d20536ef850c773bd6d0826d62`. Task commits: `04b60ab7` (implementation), `c4f07fc4` (tests),
`9a849288` (first-round test-writer tests and reports), `be1769ee` (fixer: CR-1 and the over-long restore
passphrase message). `git diff --name-only 6d9427e0..HEAD` lists only `web/src/app/features/settings/backup/`,
`web/src/app/features/settings/settings.routes{,.spec}.ts` and `reviews/web-settings-backup/`. This round
focuses on the fixer's changes (`reviews/web-settings-backup/fixes.md`) and re-measures coverage for the
whole task scope. The two tests added in this round are uncommitted.

Requirements:
- `tasks/web-settings-backup.md` acceptance criteria and its recorded assumptions
- SPEC.md §10.1 (backup and restore)
- `reviews/web-settings-backup/fixes.md` (CR-1 and the passphrase observation), `review.md`
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md; parent instructions

## Test files changed

- `web/src/app/features/settings/backup/backup-settings.spec.ts` (this round)
- Earlier rounds, already committed: `backup-settings.spec.ts`, `backup.service.spec.ts`

## Tests added or updated

Count: 2 added in this round (none updated or removed).

The fixer's own tests already cover the fixed behavior directly. I checked them and kept them unchanged:
`BackupService > uploads the zip as application/zip` (now checks Content-Type and bytes),
`uploads a zip the browser typed as "%s" as application/zip` (`application/x-zip-compressed` and empty type), and
`BackupSettings > confirmation gate > explains a restore passphrase that is too long when Replace all data is clicked`.

Added in this round (component, `BackupSettings`):
- `confirmation gate > restores after a too-long passphrase is shortened to 256 characters`: covers recovery
  after the new error. A 257-character passphrase is refused and shows "This passphrase is too long.".
  After the passphrase is shortened to exactly 256 characters (the upper boundary), the error goes away, and
  the confirm request is sent with that passphrase. This proves that the new `markAsTouched()` path does not
  block a valid retry.
- `restore upload > uploads a zip typed application/x-zip-compressed as application/zip`: covers CR-1 end to
  end through the component. A Windows-typed `.zip` is picked in the file input, and the
  `POST /api/v1/restore` request goes out with `Content-Type: application/zip` and the file's bytes unchanged.
  The report is then rendered.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Create backup with optional passphrase | `starts a backup and follows its progress from the SSE events`, `starts without a passphrase, ...`, `accepts a passphrase of exactly 12 characters`, `does not start with a passphrase shorter than 12 characters`, `rejects a passphrase longer than 256 characters`, `sends a single start request ...`, `explains a backup that is already running`, `shows the server message when a backup cannot start`; service `starts a backup ...`, `sends no passphrase when it is empty`, `does not remember a backup that failed to start` | F | Pass |
| AC1 Backup progress (SSE) | `starts a backup and follows its progress from the SSE events`, `refetches the job after an SSE reconnect`, `shows the progress bar value ...`, `shows 0 % progress ...`, `ignores an older job answer ...`, `ignores an older job request that fails ...`, `shows a failed progress fetch while the backup runs`, `keeps a completed backup silently ...`, `fetches nothing on an SSE reconnect without a backup`, `forgets a backup the server discarded` | F | Pass (simulated `ChangeEventsService` stream; the real SSE connection was not exercised) |
| AC1 Download | `downloads the completed backup`, `forgets the backup when its download is gone`, `downloads once when Download is clicked twice`, `keeps the backup and explains another download failure`; service `downloads a completed backup under its file name`, `falls back to a default file name and revokes the object URL later`, `saves nothing when the download fails` | F | Pass (jsdom; a real browser save dialog was not run) |
| AC1 Restore: upload (incl. CR-1 Content-Type fix) | `opens the file picker from the upload button`, `uploads a zip typed application/x-zip-compressed as application/zip` (new), `does nothing when no file was chosen`, `shows upload progress ...`, `shows a failed upload`, `clears an earlier upload error ...`; service `uploads the zip as application/zip`, `uploads a zip the browser typed as "application/x-zip-compressed" / "" as application/zip` | F | Pass (Angular `HttpTestingController`; the server's 415 behavior was not exercised against a running server) |
| AC1 Restore: show validation report | `renders format, versions, secrets, sorted counts and warnings`, `shows the problems of an invalid backup and offers no restore`, `asks for the passphrase of a backup with secrets`, `renders an unknown creation date and missing versions`, `offers no restore for a valid report without an upload id` | F | Pass |
| AC1 Confirm with typed confirmation (incl. passphrase validation fix) | `enables the restore only once the confirmation word is typed exactly`, `closes the gate again after cancel and a new upload`, `sends a single restore when submitted twice`, `does not restore with a passphrase longer than 256 characters`, `explains a restore passphrase that is too long when Replace all data is clicked`, `restores after a too-long passphrase is shortened to 256 characters` (new) | F | Pass |
| AC1 Restore progress | `restores, shows progress, then forces a new login` (indeterminate bar, per the recorded assumption) | F | Pass |
| AC1 Forced re-login | `restores, shows progress, then forces a new login`, `ends the session itself when the server still knows it`, `logs out and still shows the login page when the session check fails` | F | Pass |
| AC1 Restore error paths | `keeps the report and explains a failed restore` (422), `asks for a new upload when the old one is gone` (404), `explains a restore refused while a backup runs` (409), `shows the server message of another restore failure` (500) | F | Pass |
| Route `/settings/backup` | `settings routes > opens Backup & restore at /settings/backup` | F | Pass |
| AC2 Vitest tests cover report rendering and confirmation gate | the "restore report", "restore upload" and "confirmation gate" describe blocks above | F | Pass |
| AC2 `cd web && npm run check` passes | whole web check (api:check, lint, e2e:typecheck, test:ci, build) | `cd web && npm run check` | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'`

Result:

Pass. 10 files, 229 tests (227 after the fixer, plus the 2 added in this round). `npx prettier --check src/app/features/settings/backup/` passed.

## Full test execution

Command:

`cd web && npm run check` (2026-10-21, finished about 07:15Z), then `cd web && npm run coverage`

Result:

Pass. `npm run check`: "All files pass linting.", test:ci 93/93 test files passed, and `ng build` completed. The
`&&` chain reached the build, so every earlier step passed. The build printed the existing warning that the
initial bundle budget is exceeded (655.54 kB vs 500 kB). It is not caused by this task: `backup-settings` is a
lazy chunk (33.86 kB).

`npm run coverage`: the first attempt (07:15:03Z to 07:19:35Z) failed. 54 tests in 15 files failed, many of them
outside this task (app, trash, calendar, files, gtd, mail, notes, linked-items, settings/general, gtd, security),
and the run took about 4.5 minutes instead of about 30 seconds. It produced no coverage report. Right afterwards,
`npx ng test --watch=false --coverage --include='src/app/features/settings/backup/*.spec.ts'` passed (2 files,
59 tests), and an immediate rerun of `npm run coverage` (07:19:52Z to 07:20:28Z) passed with 93/93 files. The
inputs did not change between the attempts (hashes below). I conclude the failure was caused by machine load,
probably from other agents' processes running at the same time, and not by the code. That conclusion is an
inference: I could not inspect the system load (permission denied). None of the first attempt's numbers are used
here.

## .taskcheck

Result:

Not run by me in this round. The parent asked for `cd web && npm run check`, which is the web part of
`sh scripts/check.sh` and passed. The parent runs the full `.taskcheck` before `done`.

## Bugs found

None. Both earlier issues (CR-1 and the silent over-long restore passphrase) are fixed at HEAD. The tests listed
above confirm this, including the fixer's revert check for CR-1 recorded in `fixes.md`.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured as a separate metric. Three of the four scoped files were added entirely by
the task, so for them whole-file coverage equals added-line coverage. For the fixer's commit `be1769ee`, I compared
its added lines (`backup.service.ts` the `slice` and `invoke` lines; `backup-settings.ts:194-198`;
`backup-settings.html:203-207`) with the provider's uncovered locations listed below. None of the uncovered
locations is in those lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-settings-backup`. Base
  `6d9427e051a410ca8bd1e812740439bddff71b89` (from the parent). HEAD `be1769eeffe019d20536ef850c773bd6d0826d62`.
  Task commits `04b60ab7`, `c4f07fc4`, `9a849288`, `be1769ee`. Scope: `git diff --name-only 6d9427e0..HEAD -- web/src`
  minus `*.spec.ts`.
- Source/test snapshot identifier (including uncommitted inputs): production files at HEAD, unmodified (sha256:
  `backup.service.ts` bea3898c0dd75ade80094e09d3b18d150ff0e073a5cf0a0388f9054384787d73, `backup-settings.ts`
  bec845b7d05c9681bd913048213617d5a0cc7e8472b3e27f09e8a1e332b3ce04, `backup-settings.html`
  6d4fcf0f21a115526c137fe004e4daf6cf8e37114d2d62dd5238a26c48d0ed10, `settings.routes.ts`
  4eb9fb511246530b820f5d7d6c2264963e3295755ed556775016e4f209494d1f). Specs: `backup-settings.spec.ts`
  (uncommitted) e53b6a6780aeae857ebb431dd6b73adcf380619052880863545a98e881f12711, `backup.service.spec.ts`
  8eb4f50fa0874371db3e534c58e71475c8fef3af312364c5df7a79b4492d75c9, `settings.routes.spec.ts`
  10ee6c13d79de59953b280a822dfc604e4fe56fdf05105c06ea4e69ba852306a. The hashes were the same before and after the runs.
  `reviews/web-settings-backup/review.md` also showed as modified in the working tree. I did not change it, and it is not a test input.
- Exact included files; renamed/deleted/excluded files and reasons: included
  `web/src/app/features/settings/backup/backup.service.ts`, `backup-settings.ts`, `backup-settings.html`
  (instrumented as a template), and `web/src/app/features/settings/settings.routes.ts`. Excluded
  `backup-settings.scss` (a stylesheet with no executable behavior) and the spec files. No files were renamed or deleted, and none is missing.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8`. `npm run coverage` in `web/`, running the full web unit suite.
- Run start/completion time and exit status: 2026-10-21T07:19:52Z to 07:20:28Z, 93/93 test files passed, and the
  coverage summary was printed (all files: statements 9699/10331, branches 3470/3909, functions 1840/2091,
  lines 7082/7669). The earlier failed attempt is described under "Full test execution". It produced no report, and none of its numbers are used.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (mtime
  09:20:28 +0200, sha256 3d8ba2d5b491d5b62c3ed53acd5ab420779575fae291b517451a6cf487044348) and
  `web/coverage/web/coverage-final.json` (sha256 cdce1beaf10ce03e8a5b5004cf64276d0cb8ebd99ff24b7a7729f9ab9b11ac65).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The reports were written at the run's completion
  time, and their hashes differ from the first round's (6f283dde…/4ce57cd2…). The input hashes were the same before and after.
- Final run after latest test additions and production fixes: yes. The run measures HEAD `be1769ee` (with the fixer's changes) plus the 2 tests added in this round.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/settings/backup/backup.service.ts` | 24/24 (100%) | 11/11 (100%) | 8/8 (100%) | 20/20 (100%) |
| `web/src/app/features/settings/backup/backup-settings.ts` | 152/153 (99.35%) | 83/85 (97.65%) | 28/29 (96.55%) | 130/130 (100%) |
| `web/src/app/features/settings/backup/backup-settings.html` | 253/253 (100%) | 47/48 (97.92%) | 8/8 (100%) | 164/164 (100%) |
| `web/src/app/features/settings/settings.routes.ts` | 7/7 (100%) | N/A (0/0) | 6/6 (100%) | 7/7 (100%) |
| Aggregate (summed counters, 4 files; scss excluded, no files missing) | 436/437 (99.77%) | 141/144 (97.92%) | 50/51 (98.04%) | 321/321 (100%) |

The first-round figures (HEAD `bde44d4f`, before the fixes: 427/428 statements, 138/141 branches, 50/51 functions, 313/313 lines) are superseded by the table above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `backup-settings.ts:227` (`navigateByUrl(LOGIN_PATH).catch(() => false)` arrow: 1 statement, 1 function) | Defensive: router navigation to `/login` rejecting | Left uncovered. This is a defensive fallback; the forced re-login itself is covered by three tests. |
| `backup-settings.ts:99` (`report()?.counts ?? {}` fallback arm) | `counts` is required in `RestoreReportResponse`; the fallback runs only with no report | Left uncovered (not reachable with valid server data). |
| `backup-settings.ts:63` (`if` arm 0) | An instrumentation artifact on the class declaration (decorator) | Not behavior. |
| `backup-settings.html:49` (`@switch` with no matching case) | A job state outside RUNNING/COMPLETED/FAILED | Left uncovered, because the API enum has no other value. |
| Fixer lines `backup.service.ts` upload, `backup-settings.ts:194-198`, `backup-settings.html:203-207` | CR-1 Content-Type; over-long restore passphrase error | Covered (none of them is in the uncovered list). Tests: the fixer's three tests plus the two added in this round. |

## Unable to test

- Real SSE progress against a running server, a real browser file download/upload, and the server's actual
  Content-Type acceptance (415 vs 200) for a re-typed upload. The unit tests use a fake `ChangeEventsService`
  stream, jsdom and `HttpTestingController`. No acceptance criterion names E2E or a running server, so I did not
  run Playwright. A follow-up is only needed if such a criterion is added.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient for the final revision, and no production defects were found.
