# Code Review

Date: 2026-10-21

Task: web-settings-backup - Settings: backup and restore

Scope: Final revision 36f90acd4d6476669ef9745405096d63714677d6. The full review covered 6d9427e051a410ca8bd1e812740439bddff71b89..be1769ee, limited to the task's production code: `web/src/app/features/settings/backup/*` (service, component, template, styles) and `web/src/app/features/settings/settings.routes.ts` (+spec). That range includes the fixes from `reviews/web-settings-backup/fixes.md`: first-review CR-1 and the over-long restore passphrase. The delta review covered `git diff be1769ee 36f90acd -- web/`. Report files under `reviews/` were not reviewed as code.

Requirements:
- tasks/web-settings-backup.md acceptance criteria and recorded assumptions
- SPEC.md §10.1
- api/openapi.yaml Backup tag and the server contract (`RestoreController` consumes `application/zip` / `application/octet-stream`)
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- First review (CR-1) and reviews/web-settings-backup/fixes.md
- Parent agent instructions

Git range:
- Branch: main
- HEAD: 36f90acd4d6476669ef9745405096d63714677d6
- Base/start: 6d9427e051a410ca8bd1e812740439bddff71b89
- Commits reviewed: 5 (04b60ab7, c4f07fc4, 9a849288, be1769ee, 36f90acd); no uncommitted code changes

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes (not findings)

- First-review CR-1 is resolved.
    - `BackupService.upload` (`backup.service.ts:58-61`) re-types the body with `file.slice(0, file.size, 'application/zip')`. This keeps the bytes and does not copy them.
    - The generated request builder then sends `Content-Type: application/zip` whatever type the browser reports.
    - A parameterized service test covers `application/x-zip-compressed` and an empty type. It checks the header, the body type and the bytes.
- The over-long restore passphrase is resolved.
    - `restore()` (`backup-settings.ts:277-283`) marks the control as touched and returns without sending a request.
    - The template shows the `@@backup.restore.passphraseLong` error (`backup-settings.html:203-207`), the same way the backup passphrase works. A component test covers it.
    - The field only renders when `hasSecrets` is true. When it is hidden, the control is reset on upload and cancel, so a hidden invalid value cannot block the restore.
- I re-checked the rest of the production code against the first review and found no regressions:
    - The confirmation gate is enforced by the disabled button and again in `restore()`.
    - `refreshSeq` drops out-of-order job refreshes, failures included.
    - Every failure path resets `restoring`, `uploading`, `starting` and `downloading`.
    - On a 404 the job or the upload is forgotten.
    - After a successful restore the user is forced to sign in again.
    - The route swap in `settings.routes.ts` follows the other sections.
- Delta be1769ee..36f90acd: under `web/`, only `backup-settings.spec.ts` changed, with 2 added tests and no production code. Both tests assert behavior through the DOM and the HTTP testing controller, and neither weakens or removes an existing test.
    - `restores after a too-long passphrase is shortened to 256 characters` checks the 256 boundary. It also checks that the error clears and that the confirm body carries the passphrase. The navigation is stubbed, which is fine because the re-login flow is covered elsewhere.
    - `uploads a zip typed application/x-zip-compressed as application/zip` covers CR-1 through the component's file input, end to end within the unit layer.
    - The test data is fictitious.
- Validation run during the review of be1769ee: `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'` -> 10 files, 227 tests passed. I could not run the tests on 36f90acd during the delta review because command execution was denied. The parent worker should re-run that command (or `npm run check`) on the final revision.
- `tasks.py verify web-settings-backup` still listed both criteria as unverified.
    - Criterion 1 describes running UI behavior: record evidence from an actual run against the server, or mark it `--skip` with the reason.
    - Criterion 2 needs `cd web && npm run check` run on the final code.

## Review conclusion

No material issues found.
