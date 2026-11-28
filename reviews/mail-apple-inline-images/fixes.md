# Fix Report

Date: 2026-11-28

Task: mail-apple-inline-images (Apple Mail inline images: join body text parts, show unreferenced inline images)

Scope: current task; findings TW-1 (reviews/mail-apple-inline-images/tests.md) and CR-1, CR-2, CR-3 (reviews/mail-apple-inline-images/review.md)

## Fixed findings

### TW-1 — A cid reference written with HTML character references is treated as unreferenced

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt`

Problem:
`MimeContent.cidReferences` matched `cid:` on the raw HTML text, so a reference such as `cid:pic&#64;example.test` was not recognized as pointing to Content-ID `pic@example.test`. `MailReadService` resolves the attribute after HTML parsing (decoded form), so the image was marked `inline = false`, appended a second time as a positioned image and listed as an attachment.

Fix:
For every matched reference, the raw value and its HTML-entity-unescaped form (`org.jsoup.parser.Parser.unescapeEntities(raw, true)`, jsoup is already a server dependency) are both fed into the existing angle-bracket removal and percent-decoding. The raw and percent-decoded forms are kept as before, so no previously recognized reference is lost. KDoc updated. No test changed.

Validation:
- `cd server && ./gradlew ktlintFormat test --tests '*MimeContentTests' --tests '*MailReadServiceInlineImagesTests'` — Pass (MimeContentTests 59/59, including `a cid reference written with HTML character references keeps its image inline, shown once`; MailReadServiceInlineImagesTests 4/4)
- `cd server && ./gradlew check` — Pass (5319 tests, 0 failures, 0 errors, 0 skipped; ktlint clean)

### CR-1 — Forwarding a mail with a positioned image sends the internal synthetic cid reference to the recipient

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftSendGreenMailTests.kt`

Problem:
`DraftService.respond` kept the sanitized HTML unchanged for FORWARD, so a positioned image reference `cid:cockpit-part-N@cockpit.invalid` (built by `MimeContent.partImage`) ended up in the forwarded HTML, while the image itself is forwarded only as a plain attachment without that Content-ID. Editor and recipient saw a broken image.

Fix:
For FORWARD the quoted HTML now goes through a new private `withoutPositionedImages`: Jsoup body fragment, prettyPrint off, UTF-8 (same as `withoutInlineImages`); it removes every `img` whose `src` starts with `cid:` (case-insensitive) and whose rest is recognized by `MimeContent.partImageId`. Real `cid:` images are untouched, and when no positioned image is found the original HTML string is returned unchanged (no re-serialization). Replies keep using `withoutInlineImages`. The attachment handling (`forwardedAttachments`) is unchanged, so the image is still forwarded as an attachment. Fixed now at the parent worker's request because the quality gate does not allow deferring a P1; the follow-up task mail-forward-positioned-images covers the same change, so the parent worker decides what happens to it.

Tests added (in the existing GreenMail compose tests, tagged `@ExternalTest`, so they run with `integrationTest`, not with `check`):
- `a forward drops the positioned image reference from the html and forwards the image as an attachment`: an Apple Mail style multipart/mixed (HTML part plus an unreferenced inline JPEG). The forward response HTML keeps the text and contains neither `cid:cockpit-part-` nor `cockpit.invalid`; the draft lists `image0.jpeg` as attachment; the stored draft on the IMAP server has the JPEG part and no `cockpit-part-` in its HTML part.
- `a forward keeps a real cid image next to a dropped positioned image`: a related part with a `cid:`-referenced logo plus a positioned JPEG. The forward HTML still contains `img src="cid:logo-1@example.test" alt="logo"` exactly, has no `cid:cockpit-part-`, and both `logo.png` and `image0.jpeg` are attachments. The existing test `a reply drops inline cid images, a forward keeps them as inline parts` keeps covering a forward with only a real cid image.
- Regression value checked: with the FORWARD branch temporarily set back to `else -> sanitized`, both new tests fail (17 tests, 2 failed); with the fix restored they pass.

Validation:
- `cd server && ./gradlew ktlintFormat integrationTest --tests '*DraftSendGreenMailTests'` — Pass (17/17, including both new tests)
- `cd server && ./gradlew integrationTest --tests '*DraftSendGreenMailTests' --tests '*DraftComposeGreenMailTests' check` — Pass (integration 34/34; unit 5321 tests, 0 failures, 0 errors, 0 skipped; ktlint clean)

### CR-2 — The resolve-side guard for synthetic references has no test

Files changed:
- none (verified only)

Problem:
The review noted that `MailReadService.positionedImage`, the guard that keeps forged synthetic part references harmless, had no test.

Fix:
No code change needed: the test-writer added `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailReadServiceInlineImagesTests.kt` with 4 tests (the reported Apple Mail message resolves through the inline download; a forged part reference only resolves to a raster part no cid references, with PDF, cid-referenced PNG, SVG, unknown and malformed part ids losing their src; a parsed MIME mail forging references to its own PDF or cid image shows neither; a plain-text-only mail with an image gets an escaped HTML body). Verified they exist and pass.

Validation:
- `cd server && ./gradlew test --tests '*MailReadServiceInlineImagesTests'` (part of the focused run above) — Pass (4/4)

## Not fixed

### CR-3 — Plain text rendered into the HTML body loses whitespace layout; mixed line endings

Status:
Requires decision (accepted by the parent worker, not to be fixed)

Reason:
P3; the parent worker accepted it as is for this task.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat test --tests '*MimeContentTests' --tests '*MailReadServiceInlineImagesTests'` — Pass (59/59 and 4/4; TW-1 round)
- `cd server && ./gradlew ktlintFormat integrationTest --tests '*DraftSendGreenMailTests'` — Pass (17/17; CR-1 round)
- `cd server && ./gradlew integrationTest --tests '*DraftComposeGreenMailTests'` — Pass (17/17; CR-1 round)

Full test suite:
- `cd server && ./gradlew check` — Pass (TW-1 round: 5319 server unit tests; CR-1 round: 5321 server unit tests; 0 failures each; ktlintMainSourceSetCheck and ktlintTestSourceSetCheck pass)

Lint/type-check/build:
- ktlint via `./gradlew check` — Pass; Kotlin compilation — Pass

`.taskcheck`:
- Not run by the fixer (server part covered by `./gradlew check`; web and Android are unchanged); the parent worker runs `scripts/check.sh` before completion.

Remaining unresolved findings:
0 (CR-3 accepted as is)

## Final status

All validated findings fixed and validation passed.
