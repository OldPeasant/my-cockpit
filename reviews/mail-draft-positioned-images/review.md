# Code Review

Date: 2026-11-28

Task: mail-draft-positioned-images - Mail: drafts from other clients must not show positioned-image references

Scope: task commit 31bbc648b on base 11bd6970b (`git diff 11bd6970b..31bbc648b`); rebased on main as 2f6e8f8c4, `git range-diff` shows the two commits as identical

Requirements:
- tasks/mail-draft-positioned-images.md (acceptance criteria and the recorded assumption)
- reviews/mail-apple-inline-images/review.md CR-4 (background)
- AGENTS.md project notes (module boundaries, lint, fictitious test data)
- Existing code: MimeContent (mail/api), HtmlMailSanitizer, DraftService forward path

Git range:
- Branch: main
- HEAD: 2f6e8f8c43f85aa105571ec5804c264b1c133979 (contains the rebased task commit)
- Base/start: 11bd6970b5e38867b4a5be3d2ffb2ec3b88e96a8
- Commits reviewed: 1 (31bbc648bd8225c8f281136a686f3ea50d8c8db0, identical to 2f6e8f8c4); the other commits in the range only touch tasks/

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Static review only; Gradle was not run, as the parent asked. The parent reports `./gradlew integrationTest --tests '*DraftSendGreenMailTests'` with 20 passed and `./gradlew check` green.

Acceptance criteria assessment:
1. `view` (used by GET /drafts/{id} and after save, DraftService.kt:146 and :586) now runs `draftHtml`. It removes the positioned references with the same selection as the forward path (`positionedImagesRemoved`, shared with `withoutPositionedImages`). Attachments come unchanged from `content.attachments`, so the image stays listed. Met.
2. A plain-text draft (text/plain plus an inline image without Content-ID) gets `html = null` and keeps its text. MimeContent renders it as bare `<div>` text/`<br>` blocks plus `<div><img></div>`. After the image is removed, `isPlainTextRendering` sees that structure, so the HTML is dropped. Met for the stated case. A false positive is described in CR-1.
3. Two GreenMail tests append a foreign draft (HTML and plain text, each with an inline JPEG without Content-ID), then open, save as is, and send it. They assert that `cockpit-part-` is absent from the view, the save response and every received text part, and that the plain draft is sent without an HTML part. The criterion is covered as written.

The refactor of `withoutPositionedImages` keeps the forward behaviour unchanged. The change stays in the compose package, which matches the recorded assumption and the task's touches. No security concern: the HTML still goes through the sanitizer first, and the change only removes nodes or drops the HTML.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Real HTML drafts made of styled divs are silently turned into plain text**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt:503`
    - Category: Correctness
    - Problem: `isPlainTextRendering` checks only the tag name (`div`) and the child node types (text, `br`). It ignores the attributes of the div. MimeContent's plain rendering (MimeContent.kt:218) always produces bare `<div>` elements. HtmlMailSanitizer, however, keeps `style`, `dir`, `class`, `lang`, `align` on every element (HtmlMailSanitizer.kt:199). Take an HTML draft from another client with a text part and an unreferenced inline image whose HTML is, for example, `<div dir="ltr" style="font-family:Georgia;color:#336">Hello<br></div>`. This is a common shape for simple mobile and webmail HTML drafts. It matches the check, so its HTML is dropped. The first save then rewrites the draft as plain text, and the formatting and text direction are lost without any notice to the user.
    - Why it matters: Criterion 2 is about plain-text drafts. Converting real HTML drafts loses user content (formatting) on save, and the drafts that trigger this path (Apple Mail with pasted images) are the ones this task targets. The tests do not cover this case: the HTML test uses `<p>`, which never matches.
    - Suggested fix: Make the check match MimeContent's output exactly. Require `node.attributesSize() == 0` for the div (and for each `br`). Add a GreenMail or focused test: an HTML draft whose HTML is a styled `div` plus a positioned image keeps its HTML (without `cockpit-part-`) through open and save.

## P3 Low

- [ ] **CR-2 | `rest.isBlank()` is effectively unreachable; an image-only draft becomes an empty HTML draft**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt:499`
    - Category: Quality
    - Problem: MimeContent wraps every positioned image in `<div>...</div>`, and only the `img` is removed. So after removal the body is never blank for MimeContent output, because at least `<div></div>` remains. Take a draft that has only an inline image and no text part (`textPlain == null`). The view returns `html = "<div></div>"` and no text, and saving it produces an HTML message with an empty div. The same leftover empty wrapper divs remain inside HTML drafts.
    - Why it matters: Nothing breaks, and no `cockpit-part-` reference survives. But the blank check suggests a case is handled when it is not, and the draft switches from "no body" to an empty HTML body.
    - Suggested fix: Remove the empty wrapper div together with the image (for example, `img.parent()` when it is a `div` with that image as its only child). Alternatively, treat a body with no text and no remaining elements other than empty divs as blank.

## Review conclusion

Material issues found; fix before completing the task.
