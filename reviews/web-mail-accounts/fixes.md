# Fix Report

Date: 2026-10-21

Task: web-mail-accounts: Settings: mail accounts

Scope: current task, `web/src/app/features/settings/mail/`; findings CR-1, CR-2, CR-3 from `reviews/web-mail-accounts/review.md` (all P3, all approved for fixing by the parent worker). Base commit 3ec8b9d8f8cea6b1c710e8da2a28575e3b25917c; task commits 851092cf, 0bb32e33, 56378b42, 3bc52f24.

## Fixed findings

### CR-1 — Folder role change: failed reload reported as failed change; other selects editable during reload

Files changed:
- `web/src/app/features/settings/mail/folder-roles.ts`
- `web/src/app/features/settings/mail/folder-roles.html`
- `web/src/app/features/settings/mail/folder-roles.spec.ts`

Problem:
`setRole` ran the PATCH and the follow-up folder reload in one `try`, so a failed reload after a saved PATCH showed "The role of X could not be changed". Only the changed folder's select was disabled, so during the reload the user could change the folder that just lost the role and get a self-inflicted 412.

Fix:
`saving` is now one boolean flag (a role change and its reload are in flight). While it is set, every role select is disabled and `setRole` ignores further calls. A PATCH failure still shows the save error and reloads, as before. On success, the saved folder is applied first. If the role moved from another folder, a separate `refresh()` reads the folders again. If that fails, it sets `loadError`, so the template shows the existing "Folders could not be loaded: ..." alert with "Try again" (the `loadError` branch comes before the list in the template) and does not show "could not be changed". Two specs were added: all selects are disabled during the reload and enabled after it, and a failed reload shows the load alert, whose "Try again" then restores the list with the saved role.

Validation:
- `cd web && npx ng test --watch=false --include 'src/app/features/settings/mail/*.spec.ts'` — Pass

### CR-2 — Stale connection test result stays visible after the account is edited

Files changed:
- `web/src/app/features/settings/mail/mail-settings.ts`
- `web/src/app/features/settings/mail/mail-settings.spec.ts`

Problem:
`saved()` replaced the account and closed the editor but kept the old test result, so a "Failed" result for settings that had since changed stayed visible.

Fix:
`saved()` (only wired to the edit editor's `(saved)` output) now removes the account's entry from `tests`, unless a test is still running. A running test keeps its state so the running guard and its result stay consistent. A spec was added: run a failing test, edit and save, then the protocol result and failure text are gone and "Test connection" is enabled.

Validation:
- `cd web && npx ng test --watch=false --include 'src/app/features/settings/mail/*.spec.ts'` — Pass

### CR-3 — "1 folders found." has no singular form

Files changed:
- `web/src/app/features/settings/mail/mail-settings.ts`
- `web/src/app/features/settings/mail/mail-settings.spec.ts`

Problem:
The add status always used the plural form.

Fix:
Uses the review's second suggested option: it picks between two `$localize` messages, the new id `@@mailSettings.folderFound` ("1 folder found.") and the existing `@@mailSettings.foldersFound`. The new id is unique in `web/src/app`. `src/locale/messages.xlf` has no mail settings entries, so no catalog update was needed. The existing spec now expects "1 folder found." and checks that "1 folders found." is absent.

Validation:
- `cd web && npx ng test --watch=false --include 'src/app/features/settings/mail/*.spec.ts'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include 'src/app/features/settings/mail/*.spec.ts'` — Pass, 5 files, 100 tests

Full test suite:
- `cd web && npm run test:ci` — Pass, 131 files, 2663 tests

Lint/type-check/build:
- `cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build) — Pass. The build prints the bundle budget warning for the initial bundle (664 kB against 500 kB). That bundle does not include the lazy `mail-settings` chunk, so the warning is not caused by these fixes.

`.taskcheck`:
- Not run. The fixes only change web code, which `npm run check` covers. The parent worker runs `.taskcheck` before `done`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
