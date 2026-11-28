# Code Review

Date: 2026-11-28

Task: mail-apple-inline-images - Mail: show unreferenced inline images and text after images (Apple Mail)

Scope: round 3, final code at HEAD 38a844b7f. Delta since round 2 (round 2 reviewed c372ae8f6, now rebased as 70f2f5745 with identical code): `git diff 0e007fe02..38a844b7f -- server` = two new tests, one in `MimeContentTests.kt` and one in `MailReadServiceInlineImagesTests.kt`. `git diff 70f2f5745..38a844b7f -- server` confirms only these two test files changed; no production code changed since round 2. Full task diff `git diff 10e5329f19cb29feec8f8ee78e81143e63dc8569..38a844b7f -- server` was reviewed in rounds 1 and 2.

Requirements:
- tasks/mail-apple-inline-images.md (goal, acceptance criteria, notes, the two recorded assumptions)
- AGENTS.md project notes (API contract, migrations, logging, test data)
- reviews/mail-apple-inline-images/review.md (rounds 1 and 2), fixes.md, tests.md
- tasks/mail-forward-positioned-images.md (follow-up task for CR-1)
- Existing code conventions in `server/.../mail`

Git range:
- Branch: main
- HEAD: 38a844b7f
- Base/start: 10e5329f19cb29feec8f8ee78e81143e63dc8569
- Commits reviewed this round: 1 test commit (38a844b7f); earlier rounds covered the code commits 9885f288f, 702b5f1ff, a85b026bd and 70f2f5745 (formerly c372ae8f6); the remaining commits only touch `tasks/` and `reviews/`
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 0 P3 open (earlier findings: CR-1 deferred, CR-2 resolved, CR-3 accepted)

Gradle was not run for this review (the parent runs the check concurrently). Existing result files read: `server/build/test-results/test/TEST-ch.sonensei.cockpit.mail.api.MimeContentTests.xml` 60 tests, 0 failures, 0 errors (2026-11-28T10:11Z) and `TEST-ch.sonensei.cockpit.mail.api.MailReadServiceInlineImagesTests.xml` 5 tests, 0 failures, 0 errors (2026-11-28T10:10Z), including the new test by name. The test-writer reported `./gradlew check` 5321 tests, 0 failures.

What was checked in the delta:
- `MimeContentTests` `cid references with named, hex or bracket character references, also mixed with percent escapes, stay inline`: four references (`&amp;`, `&#x40;`, `%40` mixed with `&#46;`, and `&lt;...&gt;`) against parts with matching Content-IDs. Traced through `MimeContent.cidReferences` (`MimeContent.kt:245`): `CID_REFERENCE` (`cid:([^"'\s>)]+)`) captures each encoded value whole (the bracket form is entity-encoded, so the `>` stop character does not truncate it); `Parser.unescapeEntities` produces `a&b@...`, `hex@...`, `mixed%40example.test` (then percent-decoded to `mixed@...`) and `<angle@...>` (then bracket-stripped). All four parts are therefore classified inline, and the HTML stays unchanged (no appended positioned image), which is what the test asserts. The named and hex cases would fail without the round-2 unescape step, so the test has regression value beyond the round-2 test. The Content-ID `<a&b@example.test>` is unusual but legal enough for a header and only used as test input.
- `MailReadServiceInlineImagesTests` `a cid reference written with character references shows its image once, through the inline download`: runs the parsed MIME message through `MimeContent.read` and `MailReadService.read` with the real sanitizer. After HTML parsing the `src` is `cid:logo%40example.test`; `resolveInlineImages` (`MailReadService.kt:277`) percent-decodes it and matches the part's Content-ID, so exactly one `img` with the inline download URL for part 2 remains, and the attachment is `inline = true`. This pins the agreement between the classification side and the resolve side end to end (no duplicate appended image, no lost image). Assertions are on observable output (rendered `src` list, attachment list), not internals.
- Test data is fictitious (`example.test` domains, placeholder `PNG` bodies, generic subjects). Both tests follow the existing file style (raw-string MIME fixtures, Kotest matchers, backtick names) and stay within the task's `touches`.
- Full-task conclusion: production code is identical to round 2, so the round-2 analysis of acceptance criteria 1 to 7 still holds for HEAD 38a844b7f. No controller, DTO or `api/openapi.yaml` change. Recording the `./gradlew check` part of AC7 with `tasks.py verify` remains the parent worker's step.

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
    - Status: resolved by `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailReadServiceInlineImagesTests.kt`: forged references to PDF, `cid:`-referenced PNG, SVG, unknown and malformed part ids lose their `src`, valid raster references resolve to `MailReadService.attachmentUrl(..., inline = true)`, both from a hand-built `MailContent` and from a parsed MIME message. Dropping the `!it.inline` or the type check in `positionedImage` would fail these tests.

## P3 Low

- [x] **CR-3 | Plain text rendered into the HTML body loses its whitespace layout; joined plain text mixes line endings** (round 1; status: accepted)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt` (plain block rendering and `textPlain` join)
    - Category: Quality
    - Status: accepted as is by the parent worker (fixes.md). The behaviour is pinned by the test `an image before the first text and text with CRLF line ends keep their order and breaks`; changing it later means updating that test deliberately.

## Review conclusion

No material issues found.
