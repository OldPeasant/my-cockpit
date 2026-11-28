# Code Review

Date: 2026-11-28

Task: mail-apple-inline-images - Mail: show unreferenced inline images and text after images (Apple Mail)

Scope: `git diff 10e5329f19cb29feec8f8ee78e81143e63dc8569..5f824414d` (task commits b99aba389, 5f824414d). The current HEAD f69ba18fa (main, origin/wip/mail-apple-inline-images) is the same code rebased: `git diff 5f824414d f69ba18fa` only touches `tasks/mail-apple-inline-images.md`.

Requirements:
- tasks/mail-apple-inline-images.md (goal, acceptance criteria, notes, the two recorded assumptions)
- AGENTS.md project notes (API contract, migrations, logging, test data)
- Existing code conventions in `server/.../mail` (sanitizer, `MailContentLoader`, compose `DraftService` / `DraftMime`)

Git range:
- Branch: main
- HEAD: f69ba18fa (code identical to 5f824414d)
- Base/start: 10e5329f19cb29feec8f8ee78e81143e63dc8569
- Commits reviewed: 2

Summary: 0 P0, 1 P1, 1 P2, 1 P3

Gradle was not run for this review (a test-writer runs it at the same time). Existing results read: `server/build/test-results/test/TEST-ch.sonensei.cockpit.mail.api.MimeContentTests.xml` shows 45 tests, 0 failures (2026-11-28T09:40Z). No result file for `MailReadGreenMailTests` was present.

What was checked and found in order:
- AC1: `MimeContent.collect`/`read` lists the Apple part (inline disposition, filename, no Content-ID) with `inline = false` and renders it as a positioned reference after the HTML; `MailReadService.resolveInlineImages` turns it into `attachmentUrl(..., inline = true)` after sanitizing. Covered by the MimeContent unit test and the GreenMail test (including the inline download bytes).
- AC2: a `cid:`-referenced image keeps `inline = true` (Content-ID found by `cidReferences`, raw and percent-decoded, case-insensitive), is excluded from `images` and therefore not appended; a single HTML part is returned byte-identical. NEWSLETTER test unchanged and green.
- AC3, AC4, AC6: joined plain and HTML text in MIME order, alternatives by variant, escaped plain text (`&`, `<`, `>`, quotes) and escaped `alt`; non-raster and HEIC parts are listed, not rendered. Everything server-built still passes through `HtmlMailSanitizer` afterwards, so there is no HTML injection path through text or file names.
- XSS / forged references: `partImageId` only accepts the exact synthetic prefix/suffix and a part id matching `PART_ID`; `positionedImage` only resolves to a part of the same message that is not `cid:`-referenced and has a type in `RemoteImageFetcher.IMAGE_TYPES`, and the URL is built from the attachment's own `partId`. A forged reference can only show the message's own raster part, which the existing `?inline=true` download already allows. No new exposure.
- AC5: `V202611280930__mail_reparse_bodies.sql` deletes only `mail_body_attachments` (no FK points to it; it references `mail_body`). `MailContentLoader.cached` treats a body without its row as a miss and the reload upserts body, attachments and search index. Version is newer than all existing migrations; location and naming follow the convention. GreenMail test covers the miss after the delete.
- AC7 / OpenAPI: no controller, DTO or `api/openapi.yaml` change; response schema unchanged.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Forwarding a mail with a positioned image sends the internal synthetic cid reference to the recipient**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt:218` (reference emitted into `MailContent.textHtml`), consumed at `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt:303-311` and `:331`, assembled by `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftMime.kt:80-85`
    - Category: Integration
    - Problem: `MailContent.textHtml` is not only read by `MailReadService` (which resolves `cid:cockpit-part-N@cockpit.invalid` after sanitizing) but also by `DraftService.respond`. For `ResponseMode.FORWARD` the sanitized HTML is quoted as is (the sanitizer keeps `cid:` sources), so the forward draft contains an img element whose src is `cid:cockpit-part-2@cockpit.invalid`. The forwarded image arrives via `forwardedAttachments` with `inline = false` and `contentId = null`, so `DraftMime.build` puts it into `multipart/mixed` as a normal attachment and no part carries that Content-ID. Result for the exact mail the task is about (and for every plain-only mail with an inline image, which before had `textHtml = null` and was quoted via `ComposeText.textToHtml`): the compose editor shows a broken image, and the sent mail contains a dangling reference to an internal `@cockpit.invalid` id that recipients render as a broken image next to the attachment. When such a forward draft is later read through `MailReadService`, `positionedImage` interprets the original message's part id against the draft's parts, so it can show a different raster part of the draft or nothing. Replies are not affected (`withoutInlineImages` removes `img[src^=cid:]`). The task notes explicitly ask to check forwarding after this change; the attachment listing works, the body does not.
    - Why it matters: user-visible defect in mail sent to third parties, introduced by this change; leaks an internal implementation detail into outgoing mail.
    - Suggested fix: keep the synthetic reference out of anything that is edited or sent. Either strip positioned references when quoting a forward (the image is forwarded as an attachment anyway), or, for a forward, give each referenced positioned image a generated Content-ID, set `inline = true` on its `ComposedAttachment` and rewrite the `src` to that `cid:` so `DraftMime` places it in `multipart/related`. A small helper next to `MimeContent.partImageId` keeps the format in one place. Add a compose test (forward of the Apple structure: the outgoing MIME has no `cockpit.invalid` reference). `DraftService` is outside this task's `touches`; the parent decides whether to fix it here or in a follow-up task before shipping.

## P2 Medium

- [ ] **CR-2 | The resolve-side guard for synthetic references has no test**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt:303-309`
    - Category: Tests
    - Problem: `positionedImage` is the security boundary that makes forged `cid:cockpit-part-...@cockpit.invalid` references harmless (only non-inline parts with a type in `IMAGE_TYPES`, valid part ids only). The only test touching it is the GreenMail happy path. No test shows that a forged reference to a non-raster part (e.g. the PDF), to a `cid:`-referenced inline part, to an unknown or malformed part id, or a case/encoding variant loses its `src`, and the acceptance criterion names "MailReadService tests" while no MailReadService test changed.
    - Why it matters: a later refactor of the lookup (e.g. dropping the `!it.inline` or type check, or reusing `RASTER_TYPES` differently) would silently widen what a mail can embed, without a failing test.
    - Suggested fix: add `MailReadService` (or controller-level) tests with a mocked loader returning HTML that contains forged synthetic references to a PDF part, an inline `cid:` part and a non-existent part id; assert `src` is removed, and that a valid reference resolves to `attachmentUrl(..., inline = true)`.

## P3 Low

- [ ] **CR-3 | Plain text rendered into the HTML body loses its whitespace layout; joined plain text mixes line endings**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt:217` and `:58`
    - Category: Quality
    - Problem: plain parts become div blocks with br line breaks only, so runs of spaces, indentation and ASCII tables collapse once a plain-only mail gains an HTML body (it is now shown as HTML instead of the plain view). `textPlain` joins parts with `\n` while the parts themselves keep CRLF (visible in the test expectation `"...\r\nline two\nAfter..."`).
    - Why it matters: small display regression for plain-text mails with an inline image, and inconsistent line endings in reply quoting and the search index.
    - Suggested fix: emit the plain blocks as div elements with the style white-space: pre-wrap (the sanitizer allows `style`) and join with the part's own line ending or normalize to one.

## Review conclusion

Material issues found; fix before completing the task.
