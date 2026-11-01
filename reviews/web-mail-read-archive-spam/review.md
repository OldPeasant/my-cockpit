# Code Review

Date: 2026-11-01

Task: web-mail-read-archive-spam - Mail read view: Archive and Spam buttons

Scope: The final revision of the task, `7bd976910..a54d750d7`: implementation commit e8c44eb2d (web/src/app/features/mail/read/mail-read-page.{ts,html}, spec, hand-added trans-units in web/src/locale/messages{,.de}.xlf) and the test-only commits 023dc51ec and a54d750d7 (mail-read-page.spec.ts). This review replaces the earlier one, which covered only the implementation commit (as 668e6ca55/f48ec9813, before the rebase).

Requirements:
- tasks/web-mail-read-archive-spam.md (acceptance criteria, notes, recorded assumption about the xlf units)
- AGENTS.md project notes (conventions, i18n, generated files, touches)
- Existing code: web/src/app/features/mail/list/mail-shortcuts.ts, web/src/app/features/mail/list/mail.service.ts (`apply`, `MailAction`), the Delete flow from web-mail-read-delete
- reviews/web-mail-read-archive-spam/tests.md (test report)

Git range:
- Branch: main
- HEAD: a54d750d7391343de1b1e5240f610e8110a1254c
- Base/start: 7bd97691087a7934aca7197fe1ad2ee76ecbfe73
- Commits reviewed: 3 (e8c44eb2d, 023dc51ec, a54d750d7, taken from the reflog's rebase entries)

Note on method: Bash access to `git` was denied in this session, so `git diff 7bd976910..HEAD` could not be printed. The commits and HEAD were confirmed from `.git/logs/HEAD`. The working tree has only the untracked `reviews/web-mail-read-archive-spam/` folder (see the session's git status), so the changed files were read in full from the working tree, which matches HEAD for them: mail-read-page.ts, mail-read-page.html, the Archive/Spam and harness sections of mail-read-page.spec.ts (lines 1158-1544), and the mailRead.* trans-units in both xlf files.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verified acceptance criteria

1. Buttons and API. In mail-read-page.html (lines 72-109), the "Manage" group shows Archive, Spam and Delete, in that order. It is rendered only inside `@if (state() === 'ready')`. All three call the same `triage(action)` (mail-read-page.ts:193-214), which calls `MailService.apply(action, [id])` with `ARCHIVE`, `SPAM` or `DELETE`. Delete's code is reused, not duplicated, as the task notes ask. All three buttons are bound to `[disabled]="!!triaging()"`. `triage()` also returns early while a request runs, so a keyboard shortcut cannot start a second request.
2. Success and failure. On `ok: true`, the page navigates to `origin` (from `mailListOrigin`, read once when the page is created) with `replaceUrl: true`, the same as Delete. It does not navigate if the page was destroyed or the user moved to another message. On `ok: false`, a missing result, or a result for another id, the view stays open and shows `result.detail` (the server's NO_ROLE_FOLDER text), or "Something went wrong." when there is no detail. Each action has its own localized prefix. A thrown `ApiError` shows its `userMessage`. `triaging` is reset in `finally`, so the buttons work again after a failure.
3. Shortcuts. `onKeydown` maps the list's `archive` (`e`) and `spam` (`!`) shortcuts through the shared `mailShortcutFor` and `TRIAGE_SHORTCUTS`. `mailShortcutFor` ignores typing targets (`isTypingTarget`), IME composition, chords other than AltGr/Option on `!`, repeats, and events that were already handled. The read view also ignores the keys while a dialog is open or the message is not ready. `preventDefault` is called only when an action runs.
4. Tests. The spec covers both buttons (placement, titles, API call, navigation), both shortcuts (including Shift+`!`, typing in an input or contenteditable, Meta chords, repeats, an open dialog, loading, and load failure), all three buttons disabled during a request, NO_ROLE_FOLDER for Archive and Spam, ApiError and unexpected errors, refusals without a detail or with a missing result, trying again after a failure, and the guards for a destroyed page and for moving to another message. The RouterTestingHarness suite (spec lines 1460-1493) now runs Archive and Spam through the real router: return to the folder the message was opened from, fall back to `/mail` when opened from another page, replace the history entry, and stay on the message after a failure. This addresses CR-1 of the earlier review. tests.md reports `cd web && npm run check` passing (4018 tests) and 31/31 changed executable lines covered. I did not run the suite myself because Bash was limited to read-only inspection.

## Other checks

- i18n: `mailRead.manageAria`, `archive`, `archiveTitle`, `spam`, `spamTitle`, `archiveFailed` and `spamFailed` are in both messages.xlf and messages.de.xlf. The German targets match the mail list, and the `reason` placeholders match. The hand-added units are outside the task's `touches`, which is covered by the recorded assumption.
- Architecture and scope: production changes stay in `web/src/app/features/mail/read/`. The page uses the existing `MailService.apply` contract and the list's shortcut mapping. No API, generated client or server changes. Move is not added, as the task requires.
- Security: no new rendering of untrusted HTML. The server's detail text is passed to the notification service as plain text. Nothing is logged, and no secrets appear in the diff.
- Evidence for the parent worker: `python tasks.py verify web-mail-read-archive-spam` still shows all four criteria as unverified. tests.md names the pre-rebase hashes (d2364d7b2, 29599aad0, 3d341d70b) and an uncommitted spec state that is now committed as a54d750d7. Under the patch-id freshness rule in docs/QUALITY.md this may be acceptable, but the parent should confirm it and record `verify` evidence before running `done`.

## Review conclusion

No material issues found.
