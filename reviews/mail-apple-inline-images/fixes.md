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

### CR-1 — deferred

Status:
Out of scope

Reason:
Deferred by the parent worker to task mail-forward-positioned-images.

### CR-3 — Plain text rendered into the HTML body loses whitespace layout; mixed line endings

Status:
Requires decision (accepted by the parent worker, not to be fixed)

Reason:
P3; the parent worker accepted it as is for this task.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat test --tests '*MimeContentTests' --tests '*MailReadServiceInlineImagesTests'` — Pass (59/59 and 4/4)

Full test suite:
- `cd server && ./gradlew check` — Pass (5319 server unit tests, 0 failures; ktlintMainSourceSetCheck and ktlintTestSourceSetCheck pass)

Lint/type-check/build:
- ktlint via `./gradlew check` — Pass; Kotlin compilation — Pass

`.taskcheck`:
- Not run by the fixer (server part covered by `./gradlew check`; web and Android are unchanged); the parent worker runs `scripts/check.sh` before completion.

Remaining unresolved findings:
0 (CR-1 deferred to another task, CR-3 accepted)

## Final status

All validated findings fixed and validation passed.
