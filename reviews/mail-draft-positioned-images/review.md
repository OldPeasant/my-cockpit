# Code Review

Date: 2026-11-28

Task: mail-draft-positioned-images - Mail: drafts from other clients must not show positioned-image references

Scope: `git diff 114ec5a20..HEAD -- server` (task commits 8b122312a, 2b736f9f5, c379bca47, fd0cb62f1). This is a re-review after the fixes for CR-1 and CR-2 from the previous round.

Requirements:
- tasks/mail-draft-positioned-images.md (acceptance criteria and the recorded assumption)
- reviews/mail-draft-positioned-images/fixes.md (the fixes claimed for CR-1 and CR-2)
- AGENTS.md project notes (module boundaries, lint, fictitious test data)
- Existing code: MimeContent.render (mail/api), HtmlMailSanitizer (mail/sanitize), the DraftService forward path

Git range:
- Branch: main
- HEAD: fd0cb62f14ec3bb0b40ec3e400c97b1a4b9487d5
- Base/start: 114ec5a2089e430bd5b0ac99db0a2907fc605a84
- Commits reviewed: 4. Production code changed in 8b122312a and c379bca47; the other two commits change tests and reports.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

This was a static review. As the parent asked, Gradle was not run. fixes.md reports `./gradlew integrationTest --tests '*DraftSendGreenMailTests'` with 29 tests and 0 failures, and `./gradlew check` green.

Status of the previous findings:
- CR-1 (styled div HTML turned into plain text) is resolved. `isPlainTextRendering` (DraftService.kt) now requires each top-level `div`, and each `br` inside it, to have no attributes (`isBare`). That matches MimeContent.render exactly: it writes bare `<div>`, `<br>` and `<div><img ...></div>`. HtmlMailSanitizer keeps attributes such as `dir` and `style`, so a styled div now has attributes and its HTML is kept. A new GreenMail test covers `<div dir="ltr" style=...>` through open and save.
- CR-2 (empty `<div></div>` left behind, so the blank check could never match) is resolved. `positionedImagesRemoved` now removes the parent of a positioned image when the parent is a bare `div` whose only child node is that image, which is MimeContent's wrapper. Otherwise only the `img` is removed. The sanitizer serializes with `prettyPrint(false)` (HtmlMailSanitizer.kt:65), so no whitespace text node gets into the wrapper and the `childNodeSize() == 1` check holds for MimeContent output. The image-only test now asserts `html` is null, and the html-only text-blocks test asserts that no `<div></div>` is left.

Shared forward path: `withoutPositionedImages` (used by `respond` for FORWARD, DraftService.kt:314) now also removes the bare wrapper div. Only nodes that held nothing but a synthetic `cid:cockpit-part-...` image are removed. Styled or multi-child parents and real `cid:` images are not affected. This is a small improvement for forwards (no empty divs), not a regression. The existing forward GreenMail test still asserts that no `cockpit-part-` appears in the forwarded HTML. A sender could forge such a reference inside a real bare div, but then removing it only drops a broken image, so there is no harm.

Acceptance criteria:
1. `view` runs `draftHtml` on the sanitized HTML, so GET /drafts/{id} and the save response no longer carry positioned references. Attachments come unchanged from `content.attachments`, and the tests check that `image0.jpeg` is listed. Met.
2. A plain-text draft with an inline image without Content-ID opens with `html = null`, keeps its text, and is saved and sent as text/plain plus the image. Met, and the tests cover it, including a case with two images.
3. The GreenMail tests append foreign drafts to Drafts, then open, save and send them. They assert that `cockpit-part-` appears in neither the view, the saved draft nor the received message. Met as written. The `./gradlew check` evidence is reported by the parent and fixer; this review did not run it again.

Other checks: the change stays inside the compose package and the task's touches, and it matches the recorded assumption. The HTML still goes through the sanitizer before any removal, and the new code only removes nodes or drops the HTML, so there is no new security surface. Test data is fictitious. No debug output and no dead code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
