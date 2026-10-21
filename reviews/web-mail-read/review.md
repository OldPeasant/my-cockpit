# Code Review

Date: 2026-10-21

Task: web-mail-read - Mail reading view

Scope: second review round on the final code after fixes. Whole task diff `git diff 32667af6..08edb6f2` (base = `git merge-base HEAD origin/main`), with focus on fix commits 8b328019 and 08edb6f2 checked against the round-1 findings (CR-1..CR-6) and `reviews/web-mail-read/fixes.md`. Committed changes only. CR-4 is deferred to task `mail-attachment-to-files` (tasks/mail-attachment-to-files.md), as the parent supplied.

Requirements:
- tasks/web-mail-read.md (acceptance criteria 1-4 and the six recorded assumptions)
- SPEC.md §4.1, §4.3, §7.3, §7.4, §8.2 (as cited in round 1)
- docs/ARCHITECTURE.md, AGENTS.md project notes, docs/QUALITY.md artifact contract
- Round-1 report (previous version of this file), reviews/web-mail-read/fixes.md, reviews/web-mail-read/tests.md

Git range:
- Branch: main
- HEAD: 08edb6f287e4dee70e8bccb7ccbbb1aff62c344e
- Base/start: 32667af6
- Commits reviewed: 7 in range (task commits 8e16c085, 73b39b38, 5c45934c, 8b328019, 08edb6f2; plus 9fa52b8d and 33906dac, which only add/edit tasks/mail-attachment-to-files.md)
- Uncommitted changes: none (working tree clean at session start)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Method

- Read the full task diff, and read in full at HEAD: `web/src/app/features/mail/read/{mail-read-page.ts, mail-read-page.html (relevant parts), mail-frame.ts, mail-read.service.ts, image-allowlist.ts, format-bytes.ts}`, the diffs of the specs touched by the fix commits, `mail.routes.ts`, the list changes (`mail-list-page.{ts,html,spec.ts}`, `mail-shortcuts.{ts,spec.ts}`), `web/e2e/mail-read/read.spec.ts` and `web/e2e/support/imap.ts`.
- Ran `cd web && npx ng test --watch=false --include='src/app/features/mail/**/*.spec.ts'`: 13 files, 275 tests passed.
- Did not run `npm run check`, `.taskcheck` or the Playwright e2e stack. Those belong to the parent's evidence.

## Verification of round-1 findings

- **CR-1 (frame can't shrink): fixed.** `mail-frame.ts:97` now measures `contentDocument.body.scrollHeight`. The frame document gives the body `margin:0` and `padding:16px 20px` (mail-frame.ts:34). Because of the padding, child margins can't collapse through the body, so the body's scrollHeight is the full content height. In standards mode the body is an ordinary element, so the value can get smaller again. The ResizeObserver still watches the body. The new spec test (mail-frame.spec.ts, "shrinks again when the content gets shorter") pins the root at 900 while the body drops to 300 and checks that the frame shrinks.
- **CR-2 (stale text preview with a repeated part id): fixed.** `showPreview` keeps the `pending` object and applies the result only while `this.preview() === pending` (mail-read-page.ts:182-195). A newer preview of any kind, `closePreview`, or a navigation (`open()` clears the preview, line 251) replaces that identity, so a late success or failure is dropped. Two tests cover message A's part "4" answering late while message B's part "4" is being previewed, one for success and one for failure.
- **CR-3 (failed proxied load for an allowlisted sender fails the view): fixed.** The second `get` has its own try/catch (mail-read-page.ts:259-268). On failure the blocked copy is shown, `loadImages` stays false, and the error goes through `notifications.error`. The stale/destroyed guard applies before the notification. Because `imagesBlocked()` is true, the "Load images" banner (mail-read-page.html, `@if (imagesBlocked())`) offers a retry, which the test exercises. The 08edb6f2 test covers a late failure after navigation: no notification, and the new message stays shown.
- **CR-4 (Save to Files target vs SPEC §8.2): deferred**, as the parent supplied, to `mail-attachment-to-files`. The task file records the matching assumption ("drive root for now, not /Attachments/Mail"). No code change was expected. Not re-reported.
- **CR-5 (route change / stale responses untested): fixed.** The "navigating to another message" block now emits a second id with deferred promises. It covers the initial load, `showImages` after navigation, the preview, the CR-3 path and the per-message saving state. These tests ran green above.
- **CR-6 (import from files feature internals): fixed.** The mail read view now has its own `format-bytes.ts` with its own spec, so no import crosses into `features/files`. This duplicates 16 lines on purpose, because a `shared/` move would leave the task's touches. I accept that trade-off and don't report it.
- **tests.md observation 2 (`saving` keyed by part id only): fixed.** Keys are `messageId/partId` (`savingKey`, line 288), and the template uses `isSaving(part)` for the shown message. The double-save guard per attachment is kept, and a test covers concurrent saves of the same part id on two messages.

## Further checks on the final code (no findings)

- **Out-of-order async:** `open()` and `showImages()` share one `seq`. `showImages` can't overlap a running `open()` because `open()` clears `message` first and `showImages` returns early without a message. Every `open()` path either reaches a terminal state or is superseded by a newer load. `loadingImages` is reset by `open()`, so a superseded `showImages` can't leave the banner disabled.
- **Security:** the round-1 assessment still holds for HEAD. The sandbox stays static without `allow-scripts`, the CSP meta is first in `<head>`, `srcdoc` is set as a property from server-sanitized HTML, and previews and file names are interpolated, never rendered as HTML. The fix commits don't touch any of this. No secrets or personal data appear in the diff, and test data uses `example.test`/`cockpit.test`.
- **Stored state:** the allowlist read tolerates malformed JSON, non-arrays and non-string entries, and the write tolerates unavailable or full storage (image-allowlist.ts). Unchanged since round 1.
- **Integration:** the route `messages/:id` doesn't collide with the list matcher (`''` and `folders/:folderId` only). The list opens messages with `o` (guarded while typing, tested) and with double-click, and the e2e spec uses the double-click.
- **Scope:** the edits to `mail.routes.ts` and `list/` lie outside the task's `touches`. The task assumption records them and they are minimal.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. All round-1 findings except the deferred CR-4 are resolved in the code and covered by tests, and the fix commits introduce no regressions that I could find. `python tasks.py verify web-mail-read` still lists all four criteria as unverified. Before `done`, the parent worker must record evidence: `npm run check` for criterion 3 and the actual Playwright run `npm run e2e -- e2e/mail-read/read.spec.ts` against the e2e stack for criterion 4. Without that evidence, a claim would be a P1 Tests issue.
