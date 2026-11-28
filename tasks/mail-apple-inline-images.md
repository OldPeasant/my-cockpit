---
title: Mail: show unreferenced inline images and text after images (Apple Mail)
status: done
owner: dell26/agent-1
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api,server/src/test/kotlin/ch/sonensei/cockpit/mail/api,server/src/main/resources/db/migration/mail]
prio: 2
lease_until: 
releases: 1
extended: 
---
## Goal
Images that a mail shows by their position in the MIME tree, not through a `cid:` reference, are visible in My
Cockpit as they are in Thunderbird. Reported by the owner: an invitation from Apple Mail (iPhone) contained an image
that was in neither the body nor the attachment list on web/Android, but is shown in Thunderbird.

Root cause: the part looks like
```
Content-Type: image/jpeg; name=image0.jpeg; x-apple-part-url="..."
Content-Disposition: inline; filename=image0.jpeg
Content-Transfer-Encoding: base64
```
inside `multipart/mixed` with an `Apple-Mail-...` boundary and **no `Content-ID`**. `MimeContent` (`mail/api/MimeContent.kt`,
`leaves`) classifies a named part with disposition `inline` as no attachment, so it is listed with `inline = true`.
Both clients hide inline parts (`web/.../mail/read/mail-read-page.ts` `attachments`, `android/.../mail/read/MailReadRepository.kt`)
because they assume the HTML body shows them via `cid:`, which this one cannot.

A related loss, found by reading the code but not confirmed with a real mail: Apple Mail splits text around pasted
images (text, image, text in one `multipart/mixed`). `MimeContent.read` keeps only the first text/plain and the first
text/html part as body; every later text part becomes a hidden "inline attachment", so text after an image vanishes.

Intended approach (server only, clients stay unchanged):
1. A part keeps `inline = true` only if the HTML body actually references its Content-ID via `cid:`. Every other
   non-body part (unreferenced inline image, part without Content-ID, plain-text-only body) is a normal attachment
   (`inline = false`), so clients list it and it can be downloaded / saved to Files.
2. Body assembly like Thunderbird: the body text parts of a `multipart/mixed` are joined in MIME order (plain with
   plain, HTML with HTML; for a `multipart/alternative` child the matching variant is used), and unreferenced inline
   raster images (png, jpeg, gif, webp) are shown in the HTML body at their position between the text parts, as
   `<img>` pointing to the existing inline attachment download (`MailReadService.attachmentUrl(..., inline = true)`),
   added after sanitizing (server-generated markup, not untrusted). When the mail has only plain text, an HTML body is
   built from the escaped text (line breaks kept) plus those images; `textPlain` stays the joined plain text (search
   index, reply quoting). These images are still listed as attachments (step 1), so they can be saved.

## Acceptance criteria
- [x] Regression test with the reported structure (`multipart/mixed`, Apple boundary: text/html or text/plain, then an
      `image/jpeg` part with `Content-Disposition: inline; filename=image0.jpeg` and no Content-ID): the read API
      lists the image as attachment with `inline = false` and the HTML body contains an `<img>` pointing to its inline
      download
  - verified (2026-11-28 10:41+0100 by dell26/agent-1): cd server && ./gradlew test --tests '*MimeContentTests' (the reported Apple Mail structure ...) + integrationTest --tests '*MailReadGreenMailTests' (an Apple Mail inline image without Content-ID is listed and shown in the body: GreenMail IMAP append, read API -> inline=false, html has <img src=/api/v1/mail/messages/{id}/attachments/2?inline=true>, download serves image/jpeg) -> 45 + 9 tests passed
- [x] A `cid:`-referenced image in `multipart/related` (today's working case) is unchanged: `inline = true`, shown in
      the body at the `cid:` position, not listed, not appended a second time
  - verified (2026-11-28 10:41+0100 by dell26/agent-1): MimeContentTests 'mixed, related and alternative parts ...' (logo inline=true, html unchanged, no marker) + MailReadGreenMailTests 'the body is fetched ... sanitized and cached' (logo inline=true, html points at ?inline=true, 2 attachments) -> passed
- [x] Text after an image (`multipart/mixed`: text, inline image, text; both as plain-only and as
      `multipart/alternative` text/plain + text/html parts) is in the body in order with the image between the text
      parts; `textPlain` contains both text parts
  - verified (2026-11-28 10:41+0100 by dell26/agent-1): MimeContentTests 'text after an image is in the body in order, plain only' and '..., as alternatives' (html: text, positioned img, text; textPlain both parts) -> passed
- [x] Plain-text-only mail with an unreferenced inline image: HTML body is the escaped text plus the image below it,
      no HTML injection through the text (test with `<script>`/`<img onerror>` in the text)
  - verified (2026-11-28 10:42+0100 by dell26/agent-1): MimeContentTests 'text after an image ... plain only' uses <script> and <img onerror> in the text: html has them escaped (&lt;script&gt;...), image div between/below text -> passed
- [x] Bodies cached before this change are re-parsed: a new Flyway migration in
      `server/src/main/resources/db/migration/mail/` empties `mail_body_attachments` (the loader then re-fetches and
      overwrites body and attachments); covered by a test or verified against a DB with a cached old entry
  - verified (2026-11-28 10:42+0100 by dell26/agent-1): New migration V202611280930__mail_reparse_bodies.sql (delete from mail_body_attachments), applied by Flyway in the GreenMail Spring test; MailReadGreenMailTests 'a body cached without its attachment row ... is read again from the server' puts an old body, deletes attachment rows, reads via API -> re-fetched, new html with positioned image cached -> passed
- [x] Non-raster or unknown inline parts (e.g. `application/pdf` with `inline`, HEIC) are listed as attachments and not
      put into the body as `<img>`
  - verified (2026-11-28 10:42+0100 by dell26/agent-1): MimeContentTests 'a plain-only mail with a non-raster inline part gets no HTML body' (pdf + heic inline -> listed inline=false, html null) and 'a non-raster inline part next to HTML is listed and not put into the body' -> passed
- [x] Unit tests in `MimeContentTests` / `MailReadService` tests plus one GreenMail test (`MailReadGreenMailTests`)
      that appends the reported message to an IMAP folder and reads it through the API; `cd server && ./gradlew check`
      green; OpenAPI unchanged (or regenerated if the response schema changes)
  - verified (2026-11-28 11:33+0100 by dell26/agent-1): Round 4 on final code (93ab54879): cd server && ./gradlew test --tests '*MimeContentTests' --tests '*MailReadService*' -> 60+5+4 passed; ./gradlew integrationTest --tests '*MailReadGreenMailTests' (appends the reported Apple message via IMAP, reads via API) --tests '*DraftSendGreenMailTests' -> 9 + 18 passed; ./gradlew check jacocoTestReport -> BUILD SUCCESSFUL, 5321 tests 0 failures; no controller/DTO change, git diff origin/main HEAD -- api/openapi.yaml -> empty (OpenAPI unchanged)

## Notes
- Web and Android need no change: they already render the HTML body and list `inline = false` parts. Check after the
  change that forwarding such a mail (compose) now lists the image as attachment, which is the wanted behaviour.
- Keep the `maxTextSize` limit (`cockpit.mail.read.max-text-size`, default 5 MB) per text part as today; joined text may exceed one part's limit.
- ASSUMPTION (2026-11-28 10:41+0100 by dell26/agent-1): Positioned inline images are stored in the cached HTML body as synthetic cid references (cid:cockpit-part-<partId>@cockpit.invalid) and resolved after sanitizing in MailReadService; a resolved reference only points to a raster, non-cid-referenced part of the same message, so a mail forging it can only show its own image. Chosen because the cached body has no message id and the existing cid: path already survives the sanitizer.
- ASSUMPTION (2026-11-28 10:41+0100 by dell26/agent-1): A text/plain part outside multipart/alternative next to HTML parts is rendered escaped (with <br>) into the joined HTML body, like Thunderbird shows all parts of multipart/mixed; a single HTML part stays byte-identical.
- ASSUMPTION (2026-11-28 11:24+0100 by dell26/agent-1): CR-1 (forward quotes cid:cockpit-part- refs) fixed inside this task in mail/compose/DraftService.kt, outside its touches: the quality gate requires P1 findings to be fixed, not deferred. Follow-up task mail-forward-positioned-images is now redundant; a human may drop it.
- release by dell26/agent-1: All ACs implemented; AC1-6 verified. Round-2 test-writer + round-3 review done on 47d8ba0dd (check.sh passed there). Then CR-1 (P1, gate forbids deferring) was fixed by fixer in 62058dbc9 (DraftService forward drops cid:cockpit-part- imgs, 2 GreenMail tests in DraftSendGreenMailTests, fixes.md updated). Left: re-run test-writer + code-reviewer on 62058dbc9 (review.md is stale), verify AC7 (gradlew check + integrationTest MailReadGreenMailTests/DraftSendGreenMailTests), write quality.json (CR-1 fixed, TW-1 fixed, CR-2 fixed/verified, CR-3 accepted), run check.sh, done.
- ASSUMPTION (2026-11-28 11:33+0100 by dell26/agent-1): CR-4 (P2: DraftService.view shows cid:cockpit-part- refs for drafts written by other clients with an unreferenced inline image) deferred to new task mail-draft-positioned-images; non-blocking, outside this task's touches, and fixing it needs another full review round.
- done by dell26/agent-1: `sh scripts/check.sh` passed in 74s on d3c581984; criteria verified 7/7
