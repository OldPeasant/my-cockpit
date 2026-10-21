# Code Review

Date: 2026-10-21

Task: web-mail-accounts: Settings: mail accounts

Scope: committed changes 3380f4bd..d21666c2 (aef6050b implementation, 1ffd4ced specs, d21666c2 test-writer specs + tests.md, 7faec2bd new task file only). Production scope: `web/src/app/features/settings/mail/`, `web/src/app/features/settings/settings.routes.ts` (+ spec). `calendars/calendars-settings.spec.ts` in the range is a Prettier-only reformat of a test (no behavior change).

Requirements:
- tasks/web-mail-accounts.md acceptance criteria and its 3 recorded assumptions (primary identity only, follow-up tasks/mail-identities.md; OAuth connect with purpose=MAIL returning to Settings -> Integrations; test connection on the stored account, so add = save, test, detect folders)
- SPEC.md §7.1 (accounts), §4.2 (If-Match/ETag, 409/412, Problem Details), §4.7
- docs/ARCHITECTURE.md, AGENTS.md project notes (feature folders, i18n, no secrets logged, fictitious test data)
- Sibling settings sections for consistency: calendars, integrations (OAuth callback, `purpose` handling), backup/security error helpers
- Server contract: generated API models (`MailAccountResponse`, `Create/UpdateMailAccountRequest`, `UpdateMailFolderRequest`, `ConnectionTestResponse`) and `server/.../mail/imap/MailFolderService.overrideRole`

Git range:
- Branch: main
- HEAD: d21666c2855e145f51c8111302d1c82fa04e3f6d
- Base/start: 3380f4bd2d7a247317bae24cd0564c0345591d7d
- Commits reviewed: 4 (no uncommitted changes)

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Folder role change: a failed follow-up reload is reported as a failed change, and other selects stay editable during the reload**
    - File: `web/src/app/features/settings/mail/folder-roles.ts:108`
    - Category: Correctness
    - Problem: In `setRole`, the PATCH and the follow-up `folders()` reload (done when the role moved from another folder, line 113-115) share one `try`. If the PATCH succeeds and the reload fails, the catch shows "The role of X could not be changed: ..." although the server saved the change (confirmed test-writer observation). Related: only the changed folder's select is disabled (`saving` holds one id), so while the reload is in flight the user can change the folder that just lost the role; that PATCH carries the pre-move `version` (the server bumped it in `MailFolderService.overrideRole`, lines 79-84) and fails with 412 "changed elsewhere", caused by the user's own previous change.
    - Why it matters: Misleading error text in a rare double-failure case, and a self-inflicted 412 in a short race window. In both cases the component then reloads, so no data is lost.
    - Suggested fix: Apply the PATCH result first, then reload in its own try (on failure, show a load/refresh error, not "could not be changed"). Optionally disable all role selects while a role change and its reload run.

- [ ] **CR-2 | Stale connection test result stays visible after the account is edited**
    - File: `web/src/app/features/settings/mail/mail-settings.ts:202`
    - Category: Correctness
    - Problem: `saved()` replaces the account and closes the editor but leaves `tests` untouched. After a failed add, the status tells the user to "Check the result below and fix it with Edit" (line 293). After they fix the servers or password with Edit, the old "Failed" per-protocol result is still shown under the account until they run Test connection again by hand.
    - Why it matters: The page shows a failure for settings that no longer exist, which can make the user think the fix did not work.
    - Suggested fix: In `saved()`, drop the account's entry from `tests` (or re-run `runTest` for IMAP/SMTP accounts when login or servers changed). Add a spec for it.

- [ ] **CR-3 | "1 folders found." has no singular form**
    - File: `web/src/app/features/settings/mail/mail-settings.ts:308`
    - Category: Quality
    - Problem: `$localize\`...${found.length}:count: folders found.\`` always uses the plural (confirmed test-writer observation). Other features use ICU plurals for counts (e.g. `web/src/app/features/gtd/next/next-view.html:7`, `web/src/app/features/settings/gtd/delete-dialog.ts:43`).
    - Why it matters: Wrong English text for one folder, and inconsistent with the project's i18n convention (SPEC.md §4.2: UI strings i18n-ready).
    - Suggested fix: Keep the count in the state and render it in the template with an ICU plural (`{count, plural, =1 {1 folder found.} other {{{count}} folders found.}}`), or pick between two `$localize` strings.

## Notes (checked, no finding)

- AC1: IMAP/SMTP add with host/port/security and test connection; Google/Microsoft add via an existing OAuth connection or `start?purpose=MAIL`; primary identity (name + address) per the recorded assumption; Markdown signature stored verbatim (empty removes it, per the API contract); folder roles with Automatic / role / No role matching `UpdateMailFolderRequest` and the server's `overrideRole` semantics; per-account notification toggle that reverts on failure. Integrations treats `purpose=MAIL` as "connected" only and creates no calendar source.
- Optimistic locking: account updates/removals use `this.current(account)` for the latest version; folder PATCH sends `If-Match`; 409/412 map to a "changed elsewhere" message.
- Double submit guarded for add, edit, notify/remove, test and detect. Every failure path ends its loading state and keeps a retry.
- Security: password is write-only (never prefilled, sent only when typed, `autocomplete="new-password"`), no logging, test data fictitious, no unsafe HTML.
- Tests: 112 tests in the 6 affected spec files pass (`cd web && npx ng test --watch=false --include 'src/app/features/settings/mail/*.spec.ts' --include 'src/app/features/settings/settings.routes.spec.ts'`, run by this reviewer). Specs assert behavior through HttpTestingController and the DOM; no existing test was weakened (the routes spec replaces the placeholder assertion with the real component). The test report records `npm run check` passing and task-scoped coverage (99.65% statements, 92.59% branches). AC evidence is not yet recorded with `tasks.py verify`; that is the parent's step. `.taskcheck` failed in the test-writer's run on a server test outside this task and must be re-run before `done`.

## Review conclusion

No material issues found. The three P3 findings are small fixes that can be done before completing the task or left as they are.
