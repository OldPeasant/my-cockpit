# Code Review

Date: 2026-10-21

Task: web-settings-backup - Settings: backup and restore

Scope: Production code of the task at HEAD (commits eb8d466a and bde44d4f on main, rebased equivalents of 74cc6d34 and 1568cef5), diff 332be96f..HEAD limited to `web/src/app/features/settings/backup/*` and `web/src/app/features/settings/settings.routes.ts` (+spec). Task/assumption-only commits under `tasks/` were not reviewed as code.

Requirements:
- tasks/web-settings-backup.md acceptance criteria and recorded assumptions
- SPEC.md §10.1 (and §10.2 secrets/passphrase)
- api/openapi.yaml, Backup tag (`/api/v1/backup*`, `/api/v1/restore*`, `BackupJobResponse`, `RestoreReportResponse`)
- Server contract: `server/.../backup/api/BackupController.kt`, `RestoreController.kt`, `domain/BackupJobService.kt`
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- Parent agent instructions (review production code at HEAD)

Git range:
- Branch: main
- HEAD: bde44d4f809e51210ffa4a294b9c354ac609a972
- Base/start: 332be96f68b03968a30366e384977cf8467c9854
- Commits reviewed: 2 (eb8d466a, bde44d4f); no uncommitted changes

Summary: 0 P0, 1 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Restore upload sends the browser's file MIME type, which the server rejects for common `.zip` types (415)**
    - File: `web/src/app/features/settings/backup/backup.service.ts:53`
    - Category: Integration
    - Problem: `upload(file)` passes the picked `File` directly to the generated `uploadRestore`. The generated `RequestBuilder.body()` (`web/src/app/api/generated/request-builder.ts:250-251`) ignores the declared `application/zip` when the body is a `Blob` and uses `value.type` as `Content-Type`. The server endpoint only consumes `application/zip` and `application/octet-stream` (`RestoreController.kt:40`). Browsers on Windows (Chrome, Edge, Firefox) commonly report `.zip` files as `application/x-zip-compressed`. The upload then fails with 415 Unsupported Media Type, and the user cannot restore. The service test (`backup.service.spec.ts:68-77`) only uses a Blob that already has type `application/zip`, so it does not catch this. The `accept=".zip,application/zip"` attribute on the file input does not change the file's reported type.
    - Why it matters: Acceptance criterion 1 ("restore: upload, show validation report ...") fails for a large group of real browsers. The upload works only when the OS happens to map `.zip` to `application/zip` (or to no type).
    - Suggested fix: Before uploading, re-type the body in `BackupService.upload`, e.g. `file.slice(0, file.size, 'application/zip')` or `new Blob([file], { type: 'application/zip' })`. Add a service test that uploads a `File` with type `application/x-zip-compressed` (and one with an empty type) and asserts that the request's `Content-Type` is `application/zip`.

## P2 Medium

None.

## P3 Low

None.

## Notes (not findings)

- Verified as correct against the contract:
    - the SSE event type `BACKUP` with the job id (`BackupJobService.EVENT_TYPE`, `publish(EVENT_TYPE, id, op)`);
    - the passphrase bounds 12..256 with empty allowed (`BACKUP_PASSPHRASE_PATTERN`);
    - the optional confirm body;
    - the handling of the 404, 409 and 422 statuses;
    - the required `problems`, `warnings` and `counts` fields of the report.
- Out-of-order job refreshes are handled by `refreshSeq` (failures included), and starting a new job invalidates in-flight refreshes. Progress events that arrive before the job id is known are caught up once. A resync refetches the job.
- The confirmation gate is enforced both by the disabled button and in `restore()` (a form submit while the gate is closed sends nothing). A test covers this.
- After a successful restore the local session is cleared (`checkSession(true)` or `logout()`), and the user is sent to `LOGIN_PATH`. Every failure path resets `restoring`.
- `tasks.py verify web-settings-backup` shows both criteria still unverified. Criterion 1 describes running UI behavior. Its evidence should come from an actual run against the server, not only from the Vitest suite. If it cannot be run here, mark it `--skip` with the reason.

## Review conclusion

Material issues found; fix before completing the task.
