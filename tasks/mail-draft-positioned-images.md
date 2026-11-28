---
title: Mail: drafts from other clients must not show positioned-image references
status: in_progress
owner: dell26/agent-1
deps: [mail-apple-inline-images]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/compose,server/src/test/kotlin/ch/sonensei/cockpit/mail/compose]
prio: 2
lease_until: 2026-11-28T11:26:49Z
---
## Goal
Opening a draft from the Drafts folder that another client wrote (e.g. Apple Mail on iPhone with a pasted image, no
Content-ID) must not show or re-send the internal positioned-image reference `cid:cockpit-part-N@cockpit.invalid`
that `MimeContent` (task mail-apple-inline-images) puts into the cached HTML body. Found as CR-4 in
`reviews/mail-apple-inline-images/review.md`.

## Acceptance criteria
- [x] `DraftService.view` (used by GET /drafts/{id} and after save) removes positioned-image references from the draft
      HTML, like the forward path (`withoutPositionedImages`); the image stays listed as a draft attachment
  - verified (2026-11-28 11:43+0100 by dell26/agent-1): cd server && ./gradlew integrationTest --tests '*DraftSendGreenMailTests' -> 20 passed; new test 'an html draft from another client with a positioned image opens, saves and sends without the image reference' asserts GET /drafts/{id} html has no cockpit-part- and attachments list image0.jpeg
- [x] A plain-text-only draft from another client with an inline image does not turn into a broken HTML draft
      (either the HTML is dropped and the plain text kept, or the HTML has no synthetic reference)
  - verified (2026-11-28 11:43+0100 by dell26/agent-1): cd server && ./gradlew integrationTest --tests '*DraftSendGreenMailTests' -> 20 passed; test 'a plain-text draft from another client with a positioned image stays plain text through open, save and send' asserts view html null, text kept, saved draft html null, sent message has only text/plain + image0.jpeg
- [x] GreenMail test (`DraftSendGreenMailTests` or `DraftComposeGreenMailTests`) appends such a draft to the Drafts
      folder, opens it through the API, saves and sends it: neither the view nor the sent message contains
      `cockpit-part-`; `cd server && ./gradlew check` green
  - verified (2026-11-28 11:43+0100 by dell26/agent-1): GreenMail tests in DraftSendGreenMailTests append drafts to Drafts, GET, PUT, send: ./gradlew integrationTest --tests '*DraftSendGreenMailTests' -> 20 passed, view/saved/received contain no cockpit-part-; cd server && ./gradlew check -> BUILD SUCCESSFUL
- ASSUMPTION (2026-11-28 11:39+0100 by dell26/agent-1): DraftService.view drops the HTML of a draft when, after removing positioned image references, it only holds the plain-text rendering MimeContent builds (<div> text/<br> blocks) and the draft has a text part; detected structurally in the compose package instead of adding a flag to MailContent (mail/api is outside this task's touches)
