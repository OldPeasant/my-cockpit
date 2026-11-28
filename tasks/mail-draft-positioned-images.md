---
title: Mail: drafts from other clients must not show positioned-image references
status: todo
owner:
deps: [mail-apple-inline-images]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/compose,server/src/test/kotlin/ch/sonensei/cockpit/mail/compose]
prio: 2
---
## Goal
Opening a draft from the Drafts folder that another client wrote (e.g. Apple Mail on iPhone with a pasted image, no
Content-ID) must not show or re-send the internal positioned-image reference `cid:cockpit-part-N@cockpit.invalid`
that `MimeContent` (task mail-apple-inline-images) puts into the cached HTML body. Found as CR-4 in
`reviews/mail-apple-inline-images/review.md`.

## Acceptance criteria
- [ ] `DraftService.view` (used by GET /drafts/{id} and after save) removes positioned-image references from the draft
      HTML, like the forward path (`withoutPositionedImages`); the image stays listed as a draft attachment
- [ ] A plain-text-only draft from another client with an inline image does not turn into a broken HTML draft
      (either the HTML is dropped and the plain text kept, or the HTML has no synthetic reference)
- [ ] GreenMail test (`DraftSendGreenMailTests` or `DraftComposeGreenMailTests`) appends such a draft to the Drafts
      folder, opens it through the API, saves and sends it: neither the view nor the sent message contains
      `cockpit-part-`; `cd server && ./gradlew check` green
