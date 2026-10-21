# Code Review

Date: 2026-10-22

Task: web-mail-accounts: Settings: mail accounts

Scope: committed changes 21a794177f9653b986bafcf9e8e5e28d4278cf98..4af0359dac6b46921fade802e8077f4d1c857eb5 (final revision after the CR-1..CR-3 fixes). Commits: 9e7ae9bc (new task file mail-identities), 4a3ed74e (implementation), 096c4b3c and 86a1f026 (specs + test report), d616ae4a (earlier review), c27dd40e (fixes for CR-1..CR-3), 4af0359d (specs + test report only). Production scope: `web/src/app/features/settings/mail/` and `web/src/app/features/settings/settings.routes.ts`. `calendars/calendars-settings.spec.ts` in the range is a Prettier-only reformat of a test.

Requirements:
- tasks/web-mail-accounts.md acceptance criteria and its 3 recorded assumptions (primary identity only, follow-up task mail-identities; OAuth connect with purpose=MAIL returning to Settings -> Integrations; test connection on the stored account, so add = save, test, detect folders)
- SPEC.md §7.1 (mail accounts), §4.2 (Problem Details, If-Match/ETag, 409/412)
- docs/ARCHITECTURE.md, AGENTS.md project notes (feature folders, i18n-ready strings, no secrets logged, fictitious test data, lint not suppressed)
- Previous review (CR-1..CR-3) and reviews/web-mail-accounts/fixes.md

Git range:
- Branch: main
- HEAD: 4af0359dac6b46921fade802e8077f4d1c857eb5
- Base/start: 21a794177f9653b986bafcf9e8e5e28d4278cf98
- Commits reviewed: 7 (no uncommitted changes)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | "Detect folders" stays enabled while a role change runs, so the two folder-list writes can land out of order**
    - File: `web/src/app/features/settings/mail/folder-roles.html:7` (with `folder-roles.ts:79-91` and `folder-roles.ts:109-140`)
    - Category: Correctness
    - Problem: The fix for the earlier CR-1 serialises role changes with the `saving` flag, but the Detect button is only disabled by `detecting()`. If the user clicks Detect while a role PATCH (and its follow-up `refresh()` GET) is in flight, `detect()` (`syncFolders`) and `setRole()` (`folders.update(saved)` / `refresh()`) both write `folders` with no shared request identity. Whichever response arrives last wins: a `refresh()` GET served before the sync committed, or the PATCH response applied after the sync bumped versions, leaves stale folder versions/roles on screen.
    - Why it matters: Narrow timing window and no data loss, but the next role change then fails with 412 "It was changed elsewhere", caused by the user's own actions; the same class of self-inflicted 412 the earlier CR-1 set out to remove.
    - Suggested fix: Disable "Detect folders" while `saving()` is set (and/or return early in `detect()` when `saving()`), mirroring the `if (this.saving()) return;` guard in `setRole`. Add a spec that the button is disabled during a role change.

## Notes (checked, no finding)

- Earlier CR-1 (folder role change): resolved. `setRole` now applies the PATCH result first and reloads in a separate `refresh()`; a failed reload sets `loadError` (load alert with "Try again"), not the "could not be changed" text. `saving` is one boolean, every role select is disabled while it is set, and `finally` resets it on the early-return failure path. No regression found: the PATCH failure path still shows the save error and reloads.
- Earlier CR-2 (stale test result): resolved. `saved()` (wired only to the editor's `(saved)` output) drops the account's test entry unless a test is running; cancel or a refused save keep it; other accounts' results are untouched. A late folder-detection result after the entry is dropped is discarded (`detectFolders` checks the entry), which is consistent.
- Earlier CR-3 (plural): resolved with two `$localize` messages (`@@mailSettings.folderFound` / `@@mailSettings.foldersFound`); the new id is unique.
- AC1: IMAP/SMTP add with host/port/security and test connection per protocol; Google/Microsoft add via an existing OAuth connection or `start?purpose=MAIL`; primary identity (name + address) per the recorded assumption; Markdown signature sent verbatim (empty string removes it on edit); folder role mapping (Automatic / role / No role); per-account notification toggle that reverts on failure.
- §4.2: account update/remove use the latest list version (`current()`), the editor gets the replaced account through the `@for` input, folder PATCH sends `If-Match`; 409/412 map to a "changed elsewhere" message; validation field errors are shown.
- Loading/error states: every failure path ends its loading flag and leaves a retry. Double submits are guarded (add, edit, notify/remove, test, detect, role change).
- Security: password write-only (never prefilled, sent only when typed, `autocomplete="new-password"`), no logging, only text interpolation (no `innerHTML`), fictitious test data.
- Tests: `cd web && npx ng test --watch=false --include 'src/app/features/settings/mail/*.spec.ts' --include 'src/app/features/settings/settings.routes.spec.ts'` -> 6 files, 120 tests passed (run by this reviewer at HEAD). `npx eslint --max-warnings=0` on the task files and `npx prettier --check src/app/features/settings/mail` pass. Specs assert behavior through HttpTestingController and the DOM; no existing test was weakened. The test report records `npm run check`, `.taskcheck` and task-scoped coverage (statements 99.53%, branches 93.24%) for c27dd40e plus the HEAD specs.
- Evidence: `python tasks.py verify web-mail-accounts` shows both criteria still unrecorded; the parent must record them before `done`.

## Review conclusion

No material issues found. The single P3 finding is a small hardening that can be fixed before completing the task or left as it is.
