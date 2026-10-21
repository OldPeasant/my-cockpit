# Test Report

Date: 2026-10-21

Task: web-settings-backup - Settings: backup and restore

Scope: base `332be96f68b03968a30366e384977cf8467c9854`..HEAD `bde44d4f809e51210ffa4a294b9c354ac609a972`
(task commits `eb8d466a` and `bde44d4f`, rebased from `74cc6d34` and `1568cef5`, which the parent named;
`git diff 1568cef5 HEAD -- web/src/app/features/settings` is empty, so the task code is the same).
Other commits in the range only touch `tasks/` (assume/verify records). The test changes in this
report were uncommitted when it was written.

Requirements:
- `tasks/web-settings-backup.md` acceptance criteria and its recorded assumptions
- SPEC.md §10.1 (backup and restore)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md; parent instructions

## Test files changed

- `web/src/app/features/settings/backup/backup-settings.spec.ts`
- `web/src/app/features/settings/backup/backup.service.spec.ts`

## Tests added or updated

Count: 34 added, 2 updated (stronger assertions only, none removed). 30 of the added tests are in the component spec and 4 in the service spec.

Updated:
- `forgets a backup the server discarded`: now also checks that later SSE events or resyncs for the forgotten job fetch nothing.
- `confirms a restore with the passphrase only when given`: now checks the passphrase body and the silent-errors context. The original test only waited for the promise.
- I also reformatted one pre-existing `flush(...)` call so that the file passes `prettier --check`. No behavior changed.

Added (component, `BackupSettings`):
- backup: `starts without a passphrase, clears the field and skips the catch-up of a finished job`, `resets the passphrase field after a backup started`, `accepts a passphrase of exactly 12 characters`, `rejects a passphrase longer than 256 characters`, `sends a single start request when submitted twice while starting`, `does not start another backup while one is running`, `shows the progress bar value from the steps done`, `shows 0 % progress when the job has no steps yet`, `ignores an older job answer that arrives after a newer one`, `ignores an older job request that fails after a newer one answered`, `shows a failed progress fetch while the backup runs`, `keeps a completed backup silently when a refetch fails`, `fetches nothing on an SSE reconnect without a backup`, `forgets the backup when its download is gone`, `shows the server message when a backup cannot start`, `downloads once when Download is clicked twice`, `keeps the backup and explains another download failure`
- confirmation gate / restore: `sends no passphrase for a backup without secrets`, `sends a single restore when submitted twice`, `does not restore with a passphrase longer than 256 characters`, `closes the gate again after cancel and a new upload`, `explains a restore refused while a backup runs`, `shows the server message of another restore failure`, `logs out and still shows the login page when the session check fails`
- restore upload: `opens the file picker from the upload button`, `does nothing when no file was chosen`, `shows upload progress with the file name and disables the button`, `clears an earlier upload error when the next upload succeeds`, `renders an unknown creation date and missing versions`, `offers no restore for a valid report without an upload id`

Added (service, `BackupService`): `does not remember a backup that failed to start`, `fetches the state of a backup silently`, `falls back to a default file name and revokes the object URL later` (fake timers), `saves nothing when the download fails`. The new download tests restore `URL.createObjectURL`/`revokeObjectURL` afterwards.

## Requirement coverage

Focused command (F): `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Create backup with optional passphrase | `starts a backup and follows its progress from the SSE events`, `starts without a passphrase, ...`, `accepts a passphrase of exactly 12 characters`, `does not start with a passphrase shorter than 12 characters`, `rejects a passphrase longer than 256 characters`, `sends a single start request ...`, `explains a backup that is already running`, `shows the server message when a backup cannot start`; service `starts a backup ...`, `sends no passphrase when it is empty`, `does not remember a backup that failed to start` | F | Pass |
| AC1 Backup progress (SSE) | `starts a backup and follows its progress from the SSE events`, `refetches the job after an SSE reconnect`, `shows the progress bar value ...`, `shows 0 % progress ...`, `ignores an older job answer ...`, `ignores an older job request that fails ...`, `shows a failed progress fetch while the backup runs`, `keeps a completed backup silently ...`, `fetches nothing on an SSE reconnect without a backup`, `forgets a backup the server discarded` | F | Pass (simulated `ChangeEventsService` stream; the real SSE connection was not exercised) |
| AC1 Download | `downloads the completed backup`, `forgets the backup when its download is gone`, `downloads once when Download is clicked twice`, `keeps the backup and explains another download failure`; service `downloads a completed backup under its file name`, `falls back to a default file name and revokes the object URL later`, `saves nothing when the download fails` | F | Pass (jsdom; a real browser save dialog was not run) |
| AC1 Restore: upload | `opens the file picker from the upload button`, `does nothing when no file was chosen`, `shows upload progress ...`, `shows a failed upload`, `clears an earlier upload error ...`; service `uploads the zip as application/zip` | F | Pass |
| AC1 Restore: show validation report | `renders format, versions, secrets, sorted counts and warnings`, `shows the problems of an invalid backup and offers no restore`, `asks for the passphrase of a backup with secrets`, `renders an unknown creation date and missing versions`, `offers no restore for a valid report without an upload id` | F | Pass |
| AC1 Confirm with typed confirmation | `enables the restore only once the confirmation word is typed exactly`, `closes the gate again after cancel and a new upload`, `sends a single restore when submitted twice`, `does not restore with a passphrase longer than 256 characters` | F | Pass |
| AC1 Restore progress | `restores, shows progress, then forces a new login` (indeterminate bar, per the recorded assumption) | F | Pass |
| AC1 Forced re-login | `restores, shows progress, then forces a new login`, `ends the session itself when the server still knows it`, `logs out and still shows the login page when the session check fails` | F | Pass |
| AC1 Restore error paths | `keeps the report and explains a failed restore` (422), `asks for a new upload when the old one is gone` (404), `explains a restore refused while a backup runs` (409), `shows the server message of another restore failure` (500) | F | Pass |
| Route `/settings/backup` | `settings routes > opens Backup & restore at /settings/backup` | F | Pass |
| AC2 Vitest tests cover report rendering and confirmation gate | "restore report", "restore upload" and "confirmation gate" describe blocks above | F | Pass |
| AC2 `cd web && npm run check` passes | part of `.taskcheck` (`sh scripts/check.sh`) | `sh scripts/check.sh` | Pass ("all checks passed") |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'`

Result:

Pass. 10 files, 224 tests. The baseline before my changes was 190 tests. `npx eslint --max-warnings=0` and `npx prettier --check` on both spec files passed as well.

## Full test execution

Command:

`cd web && npm run test:ci` and `cd web && npm run coverage`

Result:

Pass. 93 files and 1767 tests in both runs.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` ended with "== all checks passed" (server, web, android). The web build printed an existing warning that the initial bundle budget is exceeded. It is not caused by this task: the backup section is a lazy chunk.

## Bugs found

None. Observation (not a requirement violation, no fix required): when the restore passphrase is longer than 256 characters, the "Replace all data" button stays enabled. Clicking it does nothing and shows no message. The test `does not restore with a passphrase longer than 256 characters` only asserts that no request is sent. The parent may choose to show a validation message.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. 3 of the 4 scoped files are added entirely by the task, so for them whole-file coverage is the same as added-line coverage. `settings.routes.ts` is 100% covered as a whole file, so its changed lines are covered too.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-settings-backup`. Base `332be96f68b03968a30366e384977cf8467c9854` (the merge-base with HEAD). Measured HEAD `bde44d4f809e51210ffa4a294b9c354ac609a972`. The task commits are `eb8d466a` and `bde44d4f`: the parent named them as `74cc6d34`/`1568cef5`, and they are identical in content after the rebase. The file scope comes from `git diff --name-only 332be96f..HEAD -- web/src` minus `*.spec.ts`.
- Source/test snapshot identifier (including uncommitted inputs): production files are at HEAD, unmodified (sha256: `backup.service.ts` b48d6209…, `backup-settings.ts` 4ca5842f…, `backup-settings.html` 9db7f2c0…, `settings.routes.ts` 4eb9fb51…). Uncommitted specs: `backup-settings.spec.ts` sha256 3061c9d8d4a39ab19afab7962ff7b9f4c7b27c8c63632ea1162a5c5b49101a68 and `backup.service.spec.ts` sha256 e38c867e1bcea9e954cd767578617242ac0aa1756e0c84c1ef3f7b4a02ac6b4e. The hashes were the same before and after the final run.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/settings/backup/backup.service.ts`, `backup-settings.ts`, `backup-settings.html` (instrumented as a template), and `web/src/app/features/settings/settings.routes.ts`. Excluded `backup-settings.scss` (a stylesheet with no executable behavior) and the spec files. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5, Node v24.21.0. `npm run coverage` in `web/`, running the full web unit suite.
- Run start/completion time and exit status: started about 2026-10-21T07:05:02Z and finished 07:05:46Z (report mtime 09:05:46 +0200). All 93 files and 1767 tests passed; the coverage summary was printed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 6f283dde2b68cc05ddebca631a18962c8ad5f1308fd25a051263c59f5ba89a42) and `web/coverage/web/coverage-final.json` (sha256 4ce57cd2cf98896aca16c1d94e14f2146f48bbe92eb4511559f803f771530314).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote `coverage/` during this run (the earlier run's summary hash 42a09a11… differs), and the spec hashes were the same before and after.
- Final run after latest test additions and production fixes: yes for the test additions. No production fixes have been made yet.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/settings/backup/backup.service.ts` | 23/23 (100%) | 11/11 (100%) | 8/8 (100%) | 19/19 (100%) |
| `web/src/app/features/settings/backup/backup-settings.ts` | 149/150 (99.33%) | 82/84 (97.62%) | 28/29 (96.55%) | 127/127 (100%) |
| `web/src/app/features/settings/backup/backup-settings.html` | 248/248 (100%) | 45/46 (97.83%) | 8/8 (100%) | 160/160 (100%) |
| `web/src/app/features/settings/settings.routes.ts` | 7/7 (100%) | N/A (0/0) | 6/6 (100%) | 7/7 (100%) |
| Aggregate (summed counters, 4 files; scss excluded, no files missing) | 427/428 (99.77%) | 138/141 (97.87%) | 50/51 (98.04%) | 313/313 (100%) |

An earlier run, before the last 3 tests were added, measured `backup-settings.ts` at 147/150 statements and 80/84 branches, and `backup-settings.html` at 246/248 statements. That run is superseded by the figures above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `backup-settings.ts:223` (`navigateByUrl(...).catch(() => false)` arrow) | Defensive: router navigation to `/login` rejecting | Left uncovered. This is a defensive fallback; the forced re-login itself is covered by three tests. |
| `backup-settings.ts:99` (`report()?.counts ?? {}` fallback) | `counts` is required in `RestoreReportResponse`; the fallback runs only with no report | Left uncovered (not reachable with valid server data). |
| `backup-settings.ts:63` (`if` branch) | An instrumentation artifact on the class declaration (decorator) | Not behavior. |
| `backup-settings.html:49` (`@switch` with no matching case) | A job state outside RUNNING/COMPLETED/FAILED | Left uncovered, because the API enum has no other value. |
| Earlier gaps `backup-settings.ts:151`, `:262` and `backup-settings.html:270` | Download double-click guard; start errors other than 409; Upload button opening the picker | Covered by `downloads once when Download is clicked twice`, `shows the server message when a backup cannot start` and `opens the file picker from the upload button`. |

## Unable to test

- Real SSE progress against a running server, and a real browser file download/upload. The unit tests use a fake `ChangeEventsService` stream and jsdom. No E2E criterion names these, so I did not run Playwright. A follow-up is only needed if an E2E criterion is added.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient, and no production defects were found.
