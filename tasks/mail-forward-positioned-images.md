---
title: Mail: forwards drop internal positioned-image references
status: in_progress
owner: dell26/agent-1
deps: [mail-apple-inline-images]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/compose,server/src/test/kotlin/ch/sonensei/cockpit/mail/compose]
prio: 2
lease_until: 2026-11-28T11:38:28Z
---
## Goal
Forwarding a mail whose body shows inline images by position (mail-apple-inline-images: Apple Mail image without
Content-ID, plain-text mail with inline image) does not put the internal reference
`cid:cockpit-part-N@cockpit.invalid` into the forwarded HTML. Today `DraftService.respond` (FORWARD) quotes the
sanitized HTML as is; the image is forwarded as a normal attachment without Content-ID, so the recipient and the compose
editor see a broken image (review CR-1 of mail-apple-inline-images).

## Acceptance criteria
- [x] Forward of such a mail: the quoted HTML contains no `cid:cockpit-part-` reference (the `<img>` is removed, as replies
      already remove `cid:` images); the image is still forwarded as attachment
  - verified (2026-11-28 12:21+0100 by dell26/agent-1): server/gradlew -p server integrationTest --tests '*DraftSendGreenMailTests*' -> 29 tests, 0 failures; incl. 'a forward drops the positioned image reference from the html and forwards the image as an attachment' and the plain-text forward test (no cid:cockpit-part-, jpeg attached)
- [x] Real `cid:` images in forwards keep today's behaviour
  - verified (2026-11-28 12:21+0100 by dell26/agent-1): same run (29/29 pass): 'a reply drops inline cid images, a forward keeps them as inline parts' and 'a forward keeps a real cid image next to a dropped positioned image' pass
- [ ] Test in the compose tests (unit or GreenMail); `cd server && ./gradlew check` green
- ASSUMPTION (2026-11-28 12:19+0100 by dell26/agent-1): Behaviour already implemented on main by mail-apple-inline-images (commit 84aaa89fc, CR-1) incl. GreenMail tests; this task adds no production code, only verifies it and records review evidence.
