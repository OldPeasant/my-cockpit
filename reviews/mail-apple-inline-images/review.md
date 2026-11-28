# Code Review

Date: 2026-11-28

Task: mail-apple-inline-images - Mail: show unreferenced inline images and text after images (Apple Mail)

Scope: round 4 plus delta review. Reviewed revision: 93ab54879a71c9e5873c51171b004cc4c9f7d1fb (production code identical to e1a9d906cd73aff64970ba71c6f2b9a82b999bb6; the delta e1a9d906c..93ab54879 adds one test, see "Delta review" below). Round-4 scope: Full task diff `git diff origin/main HEAD` (task commits 8b79b2aab..e1a9d906c; HEAD e90c61686 is a merge of `origin/wip/mail-apple-inline-images` whose `server/` tree is identical to e1a9d906c, checked with `git diff e1a9d906c e90c61686 -- server` = empty). Focus of this round: commit e1a9d906c (CR-1 fix in `mail/compose/DraftService.kt` plus two tests in `DraftSendGreenMailTests.kt`), and a re-check of the whole task for other consumers of the synthetic part references.

Requirements:
- tasks/mail-apple-inline-images.md (goal, acceptance criteria, notes, the three recorded assumptions, including the one moving the CR-1 fix into this task)
- AGENTS.md project notes (module boundaries, API contract, migrations, logging, fictitious test data)
- reviews/mail-apple-inline-images/review.md (rounds 1 to 3), fixes.md, tests.md
- Existing code conventions in `server/.../mail`

Git range:
- Branch: main
- HEAD: 93ab54879a71c9e5873c51171b004cc4c9f7d1fb (production code identical to e1a9d906c; round 4 was done at e90c61686)
- Base/start: origin/main 037f9dbc1
- Commits reviewed: 9 task commits (8b79b2aab..e1a9d906c) plus the merge e90c61686, the report commit 7ad27be39 and the test commit 93ab54879; code commits are 8b79b2aab, 9cd4f40f9, e1a9d906c, the rest are tests and reports
- Uncommitted changes: at review time the working tree had an uncommitted modification of `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftSendGreenMailTests.kt` (the concurrently running test-writer). It is not part of the reviewed revision and was not reviewed.

Summary: 0 P0, 0 P1, 1 P2, 0 P3 open (CR-1 resolved, CR-2 resolved, CR-3 accepted, CR-4 open and deferred by the parent worker to the follow-up task `mail-draft-positioned-images`; no new findings in the delta)

Gradle was not run for this review (another helper runs it concurrently). Existing result file read: `server/build/test-results/integrationTest/TEST-ch.sonensei.cockpit.mail.compose.DraftSendGreenMailTests.xml` (2026-11-28T10:21Z): 17 tests, 0 failures, 0 errors, 0 skipped, including both new forward tests by name.

What was checked for CR-1:
- `DraftService.respond` (`DraftService.kt:304-312`): the forward branch now calls `withoutPositionedImages(sanitized)`. The sanitizer keeps `cid:` sources as written (the existing cid path relies on that), so the synthetic `cid:cockpit-part-N@cockpit.invalid` reference arrives unchanged and `MimeContent.partImageId` (prefix and suffix match, part id regex) identifies it. Only those `img` elements are removed; real `cid:` images stay, matching the forwarded inline parts that keep their Content-ID. Replies were already covered by `withoutInlineImages`, which removes every `cid:` image including the synthetic ones.
- The positioned image itself is still forwarded: it is `inline = false` without Content-ID, so `forwardedAttachments` passes it on and `DraftMime` puts it into `multipart/mixed` as a normal attachment, which is the behaviour the task Notes ask for.
- A plain-text-only original with a positioned image now has a generated `textHtml`, so the forward quotes that HTML (with the image removed, leaving an empty `div`) instead of `ComposeText.textToHtml(textPlain)`. The visible result is equivalent; `quotedText` still uses `textPlain`.
- `MimeContent` is `internal` and in the same Gradle module; `DraftService` already imported it for `findPart`, so the new call adds no new dependency across module boundaries.
- Tests: `a forward drops the positioned image reference from the html and forwards the image as an attachment` checks the API response (no `cid:cockpit-part-`, no `cockpit.invalid`, attachment list exactly `image0.jpeg`) and the draft stored on the GreenMail server (JPEG part present, no HTML part containing the reference). `a forward keeps a real cid image next to a dropped positioned image` pins that a real `cid:` logo in `multipart/related` survives next to the dropped positioned image. Both assert observable behaviour, use fictitious data and follow the file's existing fixture style.
- Conclusion: CR-1 is fixed for the forward path. A second path that exposes the same reference is reported as CR-4.

Whole-task re-check (consumers of the cached `textHtml`): `MailReadService.read` resolves the synthetic references after sanitizing (guarded by `positionedImage`, tested in round 2); `MailSearchIndex` indexes text, not attribute values; `MailGtdService` uses the resolved read view; `DraftService.respond` is fixed; `DraftService.view` is not handled (CR-4). Acceptance criteria 1 to 6 are unchanged since round 3 and were re-confirmed against the current code; AC7 (`./gradlew check` green, OpenAPI unchanged) has no controller, DTO or `api/openapi.yaml` change in the diff, and recording its run with `tasks.py verify` remains the parent worker's step.

## Delta review (e1a9d906c..93ab54879)

- `git diff e1a9d906c 93ab54879 --stat -- server/ api/ web/` shows only `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftSendGreenMailTests.kt` (+35 lines); the other file in commit 93ab54879 is `reviews/mail-apple-inline-images/tests.md`. No production, API or migration change.
- New test `a reply and a forward of a plain-text mail with a positioned image quote its text without the image reference`, with the fixture `plainOriginalWithPositionedImage` (a `multipart/mixed` with a `text/plain` part and the existing `positionedJpeg` inline part without Content-ID). It covers the plain-text case noted under CR-1 above (generated `textHtml` instead of `ComposeText.textToHtml`): the reply HTML contains the text, no `cockpit-part-` and has no attachments; the forward HTML contains the text with the markup-like text escaped (`&lt;b&gt;bring a chair&lt;/b&gt;`), no `cockpit-part-`, exactly `image0.jpeg` as attachment, and the draft stored on GreenMail has the JPEG part and no HTML part with the reference.
- The assertions check observable behaviour (API response and the stored MIME draft), the data is fictitious, and the test reuses the file's helpers (`remote`, `syncAll`, `cached`, `onServer`, `leaves`) in the same style as the two round-4 forward tests. Selecting the forward draft by subject avoids ambiguity with the reply draft also saved in Drafts.
- Gradle was not run by this reviewer; the test-writer reports `DraftSendGreenMailTests` 18/18 passing.
- No new findings. CR-4 is unaffected by the delta (the test exercises `respond`, not `view`).

## P0 Critical

None.

## P1 High

- [x] **CR-1 | Forwarding a mail with a positioned image sends the internal synthetic cid reference to the recipient** (round 1; status: resolved in e1a9d906c)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt:311` and `:463`
    - Category: Integration
    - Problem (original): a forward quoted the sanitized HTML including `cid:cockpit-part-N@cockpit.invalid`, while the image was forwarded as a normal attachment without that Content-ID, so editor and recipient saw a broken image.
    - Status: resolved. `withoutPositionedImages` removes exactly the synthetic references from the forwarded HTML; real `cid:` images stay. Covered by the two new `DraftSendGreenMailTests` (passing per the result file above). The earlier deferral to `mail-forward-positioned-images` is superseded; the task notes record that the follow-up task is now redundant.

## P2 Medium

- [ ] **CR-4 | Opening a draft from another client that contains a positioned image exposes the synthetic reference in the editor and in the sent mail** (round 4; status: open, deferred by the parent worker to the follow-up task `mail-draft-positioned-images`)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt:705` (`view`, used by `get` at `:143` and after every save)
    - Category: Integration
    - Problem: every message in the Drafts folder is a Cockpit draft (`draftRow` only checks the folder role), including drafts written by Apple Mail on an iPhone. `view` returns `content.textHtml` only sanitized, so for such a draft with an image without `cid:` reference the draft HTML contains an `img` element with `src="cid:cockpit-part-N@cockpit.invalid"`, while the image is listed as a normal attachment (`inline = false`, no Content-ID). The web compose page shows the HTML as is (broken image), a `PUT` round trip stores the reference in the draft, and sending delivers it to the recipient next to the attachment, which is the same defect as CR-1 through the draft path. Before this task such an image was an `inline` part without a reference in the HTML, so no broken reference existed. In addition, a plain-text-only foreign draft with such an image now opens as an HTML draft (generated escaped HTML) instead of a plain-text one.
    - Why it matters: a broken image icon in the editor and in a mail sent to a third party, created by server-internal markup; no data is lost (the image is still sent as attachment).
    - Suggested fix: apply `withoutPositionedImages` to the sanitized HTML in `view` (and consider returning `null` HTML when the original had no HTML part, or accept the HTML draft deliberately with an assumption), plus a GreenMail test that appends a foreign draft with an Apple-style positioned image to Drafts and checks `GET /drafts/{id}` contains no `cid:cockpit-part-`.

- [x] **CR-2 | The resolve-side guard for synthetic references has no test** (round 1; status: resolved)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt` (`positionedImage`)
    - Category: Tests
    - Status: resolved by `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailReadServiceInlineImagesTests.kt` (forged references to PDF, cid-referenced PNG, SVG, unknown and malformed part ids lose their `src`; valid raster references resolve to the inline download).

## P3 Low

- [x] **CR-3 | Plain text rendered into the HTML body loses its whitespace layout; joined plain text mixes line endings** (round 1; status: accepted)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt` (`render`, plain join in `read`)
    - Category: Quality
    - Status: accepted as is by the parent worker (fixes.md); pinned by the test `an image before the first text and text with CRLF line ends keep their order and breaks`.

## Review conclusion

No material issues found. No P0 or P1 findings are open: CR-1 is properly fixed and now also covered for plain-text originals by the delta test. CR-4 (P2, synthetic-reference leak through the draft view path) remains open and is deferred by the parent worker to the follow-up task `mail-draft-positioned-images`; it is not blocking by severity. The delta 93ab54879 adds a sound test and introduces no new issues.
