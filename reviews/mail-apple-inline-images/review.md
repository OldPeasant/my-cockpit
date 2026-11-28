# Code Review

Date: 2026-11-28

Task: mail-apple-inline-images - Mail: show unreferenced inline images and text after images (Apple Mail)

Scope: round 2, final code. Full task diff `git diff 10e5329f19cb29feec8f8ee78e81143e63dc8569..c372ae8f6 -- server`, with focus on the delta since round 1 (round 1 reviewed code identical to 5f824414d): `git diff 5f824414d..c372ae8f6 -- server` = the TW-1 fix in `MimeContent.cidReferences` (commit c372ae8f6), new tests in `MimeContentTests.kt` and the new `MailReadServiceInlineImagesTests.kt`. Production code outside `MimeContent.kt` is unchanged since round 1.

Requirements:
- tasks/mail-apple-inline-images.md (goal, acceptance criteria, notes, the two recorded assumptions)
- AGENTS.md project notes (API contract, migrations, logging, test data)
- reviews/mail-apple-inline-images/review.md (round 1), fixes.md, tests.md
- tasks/mail-forward-positioned-images.md (follow-up task for round-1 CR-1)
- Existing code conventions in `server/.../mail`

Git range:
- Branch: main
- HEAD: c372ae8f6
- Base/start: 10e5329f19cb29feec8f8ee78e81143e63dc8569
- Commits reviewed: 4 code/test commits (9885f288f, 702b5f1ff, a85b026bd, c372ae8f6); the remaining commits in the range only touch `tasks/` and `reviews/`
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 0 P3 open (round-1 findings: CR-1 deferred, CR-2 resolved, CR-3 accepted)

Gradle was not run for this review (a test-writer may run it concurrently). Existing result files read: `server/build/test-results/test/TEST-ch.sonensei.cockpit.mail.api.MimeContentTests.xml` 59 tests, 0 failures, 0 errors (2026-11-28T10:03Z) and `TEST-ch.sonensei.cockpit.mail.api.MailReadServiceInlineImagesTests.xml` 4 tests, 0 failures, 0 errors (2026-11-28T10:03Z), both written after the TW-1 fix commit time.

What was checked in the delta:
- TW-1 fix (`MimeContent.kt` `cidReferences`): each `cid:` match is now considered raw and with HTML character references unescaped (`Parser.unescapeEntities(raw, true)`, jsoup already a dependency), and each form is then bracket-stripped and percent-decoded as before. The previous raw and percent-decoded forms are still produced, so no earlier recognized reference is lost. Extra candidate ids only matter when a part with exactly that Content-ID exists, so a wrong unescape can at worst keep a real `cid:` part inline, never expose anything new. This matches how `MailReadService.resolveInlineImages` reads the attribute after HTML parsing, so the classification and the resolve side now agree for entity-encoded references. Regression test `a cid reference written with HTML character references keeps its image inline, shown once` asserts the HTML stays unchanged (no appended positioned image) and the part stays `inline = true`.
- `MailReadServiceInlineImagesTests.kt`: drives `MailReadService.read` with a mocked locator and loader and the real `HtmlMailSanitizer`, so the sanitize-then-resolve path is exercised end to end at the service level. It covers the reported Apple structure (inline URL, image after the text, no leftover `cid:`, attachment `inline = false`), forged synthetic references to a PDF, a `cid:`-referenced PNG, SVG, an unknown and a malformed part id (all lose `src`), the same forgeries coming from a parsed MIME message, and the plain-only escaped body. Assertions are on behaviour (resolved `src` per `alt`, ordering, attachment list), not on internals. Test data is fictitious. File lies within the task's `touches`.
- New `MimeContentTests` cases (percent-encoded/bracketed/case variants, related HTML variant of an alternative, alternative without HTML variant, single-variant alternatives framing an image, per-part size limit, every raster type, SVG and attachment-disposition parts not rendered, escaped `alt`, image before the first text and CRLF line breaks, `partImage` round trip and part id validation) match the acceptance criteria and the task notes (per-part `maxTextSize`).
- The resolve-side guard accepts `RemoteImageFetcher.IMAGE_TYPES` (includes e.g. bmp), which is wider than the raster set `MimeContent` renders; this only lets a mail show its own non-`cid:` image part that the existing `?inline=true` download already serves, and the test pins this behaviour explicitly. No new exposure.
- Acceptance criteria 1 to 6 remain satisfied by the final code (round-1 analysis of `MimeContent.collect`/`read`, `MailReadService.positionedImage`, migration `V202611280930__mail_reparse_bodies.sql` still applies, unchanged code). AC7: unit tests in `MimeContentTests` and the now added `MailReadService` tests plus the GreenMail test exist; no controller, DTO or `api/openapi.yaml` change, so OpenAPI stays unchanged. The `./gradlew check` part of AC7 is still unticked in the task file; recording it with `tasks.py verify` is the parent worker's step.

## P0 Critical

None.

## P1 High

- [x] **CR-1 | Forwarding a mail with a positioned image sends the internal synthetic cid reference to the recipient** (round 1; status: deferred)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt` (synthetic reference in `MailContent.textHtml`), consumed by `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt` (`respond`, FORWARD) and `DraftMime.kt`
    - Category: Integration
    - Problem: a forward quotes the sanitized HTML including `cid:cockpit-part-N@cockpit.invalid`, while the image is forwarded as a normal attachment without that Content-ID, so the editor and the recipient see a broken image.
    - Status: deferred by the parent worker to task `mail-forward-positioned-images` (tasks/mail-forward-positioned-images.md, depends on this task, touches `mail/compose`, acceptance criteria require the forwarded HTML to contain no `cid:cockpit-part-` reference and a compose test). The code in `DraftService` is outside this task's `touches`, so the deferral is consistent with the board rules. Note for the owner: until that task is done, forwarding such a mail from main shows the broken reference.

## P2 Medium

- [x] **CR-2 | The resolve-side guard for synthetic references has no test** (round 1; status: resolved)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt` (`positionedImage`)
    - Category: Tests
    - Status: resolved by `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailReadServiceInlineImagesTests.kt`: forged references to PDF, `cid:`-referenced PNG, SVG, unknown and malformed part ids lose their `src`, valid raster references resolve to `MailReadService.attachmentUrl(..., inline = true)`, both from a hand-built `MailContent` and from a parsed MIME message. Dropping the `!it.inline` or the type check in `positionedImage` would now fail these tests.

## P3 Low

- [x] **CR-3 | Plain text rendered into the HTML body loses its whitespace layout; joined plain text mixes line endings** (round 1; status: accepted)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt` (plain block rendering and `textPlain` join)
    - Category: Quality
    - Status: accepted as is by the parent worker (fixes.md). The behaviour is now pinned by the test `an image before the first text and text with CRLF line ends keep their order and breaks`; changing it later means updating that test deliberately.

## Review conclusion

No material issues found.
