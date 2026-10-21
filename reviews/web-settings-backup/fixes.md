# Fix Report

Date: 2026-10-21

Task: web-settings-backup — Settings: backup and restore

Scope: current task; findings CR-1 (`reviews/web-settings-backup/review.md`) and the minor observation in `reviews/web-settings-backup/tests.md` ("Production bugs found"), as passed on by the parent worker. Base 332be96f68b03968a30366e384977cf8467c9854, reviewed HEAD b5b2ddc6.

## Fixed findings

### CR-1 — Restore upload sends the browser's file MIME type, which the server rejects for common `.zip` types (415)

Files changed:
- `web/src/app/features/settings/backup/backup.service.ts`
- `web/src/app/features/settings/backup/backup.service.spec.ts`

Problem:
`BackupService.upload` passed the picked `File` straight to `uploadRestore`. The generated `RequestBuilder.body()` (`web/src/app/api/generated/request-builder.ts`) uses a Blob's own `type` as Content-Type, so a `.zip` typed `application/x-zip-compressed` (Windows browsers) or with no type was sent with that Content-Type. The server only consumes `application/zip` and `application/octet-stream`.

Fix:
The body is re-typed before upload with `file.slice(0, file.size, 'application/zip')` (same bytes, no copy). The generated client is unchanged. The existing upload test now checks the Content-Type and the body bytes instead of object identity. A new parameterized test uploads a `File` typed `application/x-zip-compressed` and one with an empty type. For both it asserts that the request Content-Type and body type are `application/zip` and that the bytes are kept. With the fix temporarily reverted, both new cases fail (2 failed), so they catch the bug.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/settings/backup/backup.service.spec.ts'` with the fix reverted — Fail (2 new tests fail, as expected)
- `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'` — Pass (10 files, 227 tests)

### tests.md observation — An over-long restore passphrase leaves "Replace all data" enabled, and clicking it does nothing

Files changed:
- `web/src/app/features/settings/backup/backup-settings.ts`
- `web/src/app/features/settings/backup/backup-settings.html`
- `web/src/app/features/settings/backup/backup-settings.spec.ts`

Problem:
`restore()` returned silently when `restorePassphrase` was invalid (more than 256 characters). The template had no error for that field, so the user got no feedback.

Fix:
`restore()` now marks the restore passphrase as touched when it is invalid, then returns without sending a request. This works the same way as the backup passphrase in `startBackup()`. The restore passphrase field shows `<mat-error i18n="@@backup.restore.passphraseLong">This passphrase is too long.</mat-error>` when it is touched and has a `maxlength` error. The text matches the backup field's message. New test `explains a restore passphrase that is too long when Replace all data is clicked`: it types 257 characters without blurring, checks that no error shows yet and the button is enabled, then clicks "Replace all data". It asserts that no confirm request is sent and the mat-error appears. No translation files are committed (`ng extract-i18n` is on demand), so the new message id needs no other file.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'` — Pass (10 files, 227 tests)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'` — Pass (10 test files, 227 tests)
- `cd web && npx prettier --check src/app/features/settings/backup/` — Pass

Full test suite:
- Not run by the fixer (the parent worker runs `.taskcheck`).

Lint/type-check/build:
- `cd web && npx ng lint --max-warnings=0` — Pass ("All files pass linting.")

`.taskcheck`:
- Not run (the parent worker runs it before `done`).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
