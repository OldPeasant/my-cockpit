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
- [ ] Forward of such a mail: the quoted HTML contains no `cid:cockpit-part-` reference (the `<img>` is removed, as replies
      already remove `cid:` images); the image is still forwarded as attachment
- [ ] Real `cid:` images in forwards keep today's behaviour
- [ ] Test in the compose tests (unit or GreenMail); `cd server && ./gradlew check` green
