# Code Review

Date: 2026-10-21

Task: web-mail-read - Mail reading view

Scope: task commits aed7be71 (mail read view) and b93afaef (Playwright spec, Imap.appendRaw) on top of base f9f451a3, as supplied by the parent worker (`git diff f9f451a3..HEAD`). Committed changes only.

Requirements:
- tasks/web-mail-read.md (acceptance criteria 1-4 and the five recorded assumptions)
- SPEC.md §4.1 (HTML mail untrusted, sandboxed iframe with CSP, load-images toggle, per-sender allowlist), §4.3 (linked items panel), §4.7, §7.3 (read view, GTD buttons), §7.4, §8.2 (special folders)
- docs/ARCHITECTURE.md (web stack, testing rules), AGENTS.md project notes
- Context: tasks/mail-sanitizer.md, tasks/mail-read-api.md and server/.../mail/sanitize/HtmlMailSanitizer.kt (server-side sanitizer the frame relies on)

Git range:
- Branch: main
- HEAD: b93afaef
- Base/start: f9f451a32803f53d62dd3a1cc0267e1aab527116
- Commits reviewed: 2 (aed7be71, b93afaef)

Note on method: the Bash tool was denied in this review session, so `git diff`/`git log` could not be run. The scope was taken from the parent's commit list and the files it named, and every file was read in full at the current working tree (git status at session start: clean, HEAD b93afaef). Files reviewed: web/src/app/features/mail/read/{mail-frame.ts, mail-frame.spec.ts, mail-read-page.ts, mail-read-page.html, mail-read-page.spec.ts, image-allowlist.ts, image-allowlist.spec.ts, mail-read.service.ts, mail-read.service.spec.ts}, web/src/app/features/mail/mail.routes.ts, web/src/app/features/mail/list/{mail-list-page.ts, mail-list-page.html, mail-shortcuts.ts, mail-shortcuts.spec.ts}, web/e2e/mail-read/read.spec.ts, web/e2e/support/imap.ts. I did not run the e2e stack (as instructed) and did not run `tasks.py verify`.

Summary: 0 P0, 0 P1, 0 P2, 6 P3

## Security assessment (no findings)

I checked the iframe hardening and found no exploitable gap:
- `sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"` is static in the template (mail-frame.ts:53), so it can't be bound or changed at runtime. It has no `allow-scripts`, `allow-forms` or `allow-top-navigation`. Without `allow-scripts`, adding `allow-same-origin` gives mail content no script capability. The parent can still read `contentDocument` to size the frame.
- The CSP meta (mail-frame.ts:17-19) is the first thing after `<meta charset>`, so mail content can't come before it. It sets `default-src 'none'`, `script-src 'none'`, `img-src 'self' data:`, `style-src 'unsafe-inline'` (so no remote stylesheets or `@import`), `form-action 'none'` and `base-uri 'none'`. Because of `script-src 'none'`, `javascript:` navigations are blocked too.
- The server sanitizer (HtmlMailSanitizer.kt:86-108) drops every `img` that isn't http(s), `cid:` or a raster `data:` URL. A mail therefore can't plant a relative `/api/v1/mail/image-proxy?url=...` image and slip past "blocked" through `img-src 'self'`. Links get `rel="noopener noreferrer"`, so `allow-popups-to-escape-sandbox` doesn't open a reverse-tabnabbing path.
- Plain-text bodies, text previews and file names are rendered by interpolation, never as HTML. Image previews cover raster types only (no SVG). Downloads use same-origin `<a download>` links, and the server forces `attachment`/octet-stream on those (mail-read-api assumption).
- `srcdoc` is set as a DOM property (mail-frame.ts:84), not through Angular's sanitizer. This is safe only because the input is server-sanitized HTML and the frame is sandboxed. The code comment documents this.
- No secrets, personal data or debug logging appear in the changed code. Test data is fictitious (`example.test`, `cockpit.test`).

## Acceptance criteria assessment

1. **Sanitized HTML in sandbox+CSP, load-images toggle with per-sender allowlist, attachments (download, preview, save to Files):** implemented (mail-frame.ts, mail-read-page.ts/html, image-allowlist.ts, mail-read.service.ts). The allowlist lives in localStorage; stored values that are malformed, the wrong type or unavailable are handled (image-allowlist.ts:26-53) and tested. Save to Files works as recorded in the assumption, with one open point (CR-4).
2. **Buttons → Inbox task, → Waiting For, Create note; linked items panel:** implemented (mail-read-page.html:15-43, 218). Double submits are blocked through `busy`, and the panel reloads after an action.
3. **Vitest tests for sandbox attributes and image toggle; `npm run check` passes:** sandbox tokens, CSP and srcdoc are covered (mail-frame.spec.ts, mail-read-page.spec.ts:129-140). The toggle is covered for load once, always for this sender, stop, a failed reload and no remote images (mail-read-page.spec.ts:158-219). The parent reports that `npm run check` passes. I could not re-run it here.
4. **Playwright `e2e/mail-read/read.spec.ts`: script does not run, images only after toggle:** the spec asserts the sandbox attribute, no script or `onerror` flag, no title change, no dialog, no proxied request and no remote `img` before the toggle, and a loaded proxied image after it (read.spec.ts:64-87). The parent reports it passed against the e2e stack. Not re-run here, as instructed. The proxy is stubbed with `page.route` because the stack is offline (read.spec.ts:46-51). So the e2e run proves the client-side gating, not the server's proxy fetch, which mail-read-api tests separately.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Mail frame can grow but never shrink**
    - File: `web/src/app/features/mail/read/mail-frame.ts:95`
    - Category: Correctness
    - Problem: `resize()` sets the frame height from `contentDocument.documentElement.scrollHeight`. In standards mode (the document starts with `<!doctype html>`), the root element's scrollHeight is never less than the frame's viewport height. Once the frame is tall, a later measurement can't come back smaller. Examples: the window widens and the text reflows into fewer lines, or the ResizeObserver fires after a layout change.
    - Why it matters: blank white space stays under the mail after a resize, and the page scrolls further than the content.
    - Suggested fix: measure the content, not the viewport. The body has `margin:0`, so `body.scrollHeight` or `Math.ceil(body.getBoundingClientRect().height)` works. Alternatively, reset `style.height` to the minimum before measuring.

- [ ] **CR-2 | A text preview from a previous message can show under the current message**
    - File: `web/src/app/features/mail/read/mail-read-page.ts:186-191`
    - Category: Correctness
    - Problem: a finished text preview applies its result when `this.preview()?.partId === partId`. The check compares only the part id. Part ids such as `"2"` repeat across messages. Angular reuses the component when only `:id` changes, for example through an email→email link in the linked items panel or browser history. If the user starts a preview, moves to another message and previews a part with the same id before the first request returns, the old message's text appears under the new attachment's name.
    - Why it matters: the user sees content from the wrong attachment. The window is narrow but real.
    - Suggested fix: also store and compare the message id in `Preview`, or capture `this.seq` like `showImages` does and ignore stale results.

- [ ] **CR-3 | For an allowlisted sender, a failed proxied load fails the whole view**
    - File: `web/src/app/features/mail/read/mail-read-page.ts:247-251, 258-265`
    - Category: Correctness
    - Problem: for an allowlisted sender, `open()` loads the message once with images blocked, then loads it again with `PROXY`. If the second request fails, the catch sets state `failed` and throws away the blocked copy that already loaded fine.
    - Why it matters: a temporary error on the second call makes a readable message unreadable. `showImages()` handles the same failure better: it shows a notification and keeps the blocked body.
    - Suggested fix: on a failed second request, show the blocked message, leave `loadImages` false (the "Load images" banner then offers a retry) and report the error through `notifications.error`.

- [ ] **CR-4 | "Save to Files" target folder not reconciled with SPEC.md §8.2**
    - File: `web/src/app/features/mail/read/mail-read.service.ts:55-58`
    - Category: Integration
    - Problem: attachments are uploaded to the Files root (`uploadFile` without `folderId`). SPEC.md §8.2 lists auto-managed special folders `/Attachments/{Tasks,Notes,Mail,Journal}`, which suggests saved mail attachments belong in `/Attachments/Mail`. The recorded assumption covers "Files root" and the 50 MB limit but doesn't mention §8.2.
    - Why it matters: if the special folder is meant for this, saved attachments end up in the wrong place and need a later migration or UI change.
    - Suggested fix: use `/Attachments/Mail` if the Files API exposes it. Otherwise, extend the assumption to say the root was chosen against §8.2 and why, so a human can confirm.

- [ ] **CR-5 | Changing the route parameter and stale load responses are untested**
    - File: `web/src/app/features/mail/read/mail-read-page.spec.ts:95-117`
    - Category: Tests
    - Problem: the spec creates `params` as a `BehaviorSubject` but never emits a second id. Nothing tests the `seq` guard: a slower load of message A finishing after the user moved to message B, or a `showImages` result arriving after navigation (mail-read-page.ts:115-125, 237-249).
    - Why it matters: out-of-order results are exactly the regression unit tests miss, and the guard has no protection against future edits.
    - Suggested fix: add a test that emits a second id while the first `get` is still pending (use a deferred promise in `FakeReadService`). It should assert that the page shows message B and ignores A's late answer, success or error.

- [ ] **CR-6 | Read view imports an internal helper of the files feature**
    - File: `web/src/app/features/mail/read/mail-read-page.ts:25`
    - Category: Architecture
    - Problem: `formatBytes` is imported from `../../files/upload/format-bytes`, an internal file of another feature folder. The project notes say each feature lives in its own folder, and shared code otherwise sits in `shared/` (as `shared/links` does).
    - Why it matters: a refactor inside `features/files/upload` can break the mail feature without warning.
    - Suggested fix: move `formatBytes` to `shared/` in a follow-up (it touches the files feature, outside this task's touches), or record the coupling.

## Review conclusion

No material issues found. The iframe sandbox and CSP setup is sound, and all four acceptance criteria are addressed in code and tests. The six P3 findings are optional improvements. The parent worker must still record evidence for criteria 3 and 4 with `tasks.py verify`, because the task file shows no criterion verified yet.
