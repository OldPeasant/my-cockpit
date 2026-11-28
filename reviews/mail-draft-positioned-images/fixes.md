# Fix Report

Date: 2026-11-28

Task: mail-draft-positioned-images - Mail: drafts from other clients must not show positioned-image references

Scope: current task; findings CR-1 and CR-2 from reviews/mail-draft-positioned-images/review.md. Base 114ec5a20, fix commit c379bca47.

## Fixed findings

### CR-1 - Real HTML drafts made of styled divs are silently turned into plain text

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftSendGreenMailTests.kt`

Problem:
`isPlainTextRendering` checked only tag names, so a draft with a text part and a styled HTML body such as
`<div dir="ltr" style="font-family:Georgia">Hello<br></div>` plus a positioned image lost its HTML on open and save.

Fix:
The check now matches MimeContent's plain rendering exactly. Each top-level `div` and each `br` inside it must have no
attributes. This uses a new small helper `isBare(element, tag)`. New GreenMail test
`a draft with a text alternative and styled div html plus a positioned image keeps its html through open and save`
asserts that the view and save response keep the HTML (with `dir="ltr"` and the text), contain no `<img` and no
`cockpit-part-`. Before the fix this test would fail: the old check treated this HTML as plain text, so `html` was null.

Validation:
- `cd server && ./gradlew integrationTest --tests '*DraftSendGreenMailTests'` - Pass (29 tests, 0 failures)

### CR-2 - rest.isBlank() is effectively unreachable; an image-only draft becomes an empty HTML draft

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftSendGreenMailTests.kt`

Problem:
Only the `img` was removed, so MimeContent's `<div>` wrapper stayed as `<div></div>`. An image-only draft opened with
`html = "<div></div>"`, and the blank check never matched.

Fix:
`positionedImagesRemoved` now removes the image's parent too when that parent is a bare `div` (no attributes) whose
only child node is the image, which is exactly MimeContent's wrapper. Otherwise it removes only the image. This code is
shared with the forward path (`withoutPositionedImages`), so forwards no longer keep empty wrapper divs either. Real
`cid:` images and styled wrappers are unaffected. The test `a draft that is only a positioned image opens without an
image reference` now asserts that `html` is null. The html-only text-blocks test also asserts that no `<div></div>` is
left.

Validation:
- `cd server && ./gradlew integrationTest --tests '*DraftSendGreenMailTests'` - Pass (29 tests, 0 failures)

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` - no changes needed beyond the edits
- `cd server && ./gradlew integrationTest --tests '*DraftSendGreenMailTests'` - BUILD SUCCESSFUL, 29 tests, 0 failures, 0 errors

Full test suite:
- `cd server && ./gradlew check` - BUILD SUCCESSFUL (3m 24s)

Lint/type-check/build:
- ktlint checks are part of `./gradlew check` - Pass

`.taskcheck`:
- Not run (server-only change; `./gradlew check` covers the server part of scripts/check.sh)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
