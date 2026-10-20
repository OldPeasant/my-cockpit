---
title: Cheaper link checks and loadOlder candidate narrowing in mail sync
status: todo
owner:
deps: [mail-header-sync, mail-idle]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/items,server/src/main/kotlin/ch/sonensei/cockpit/mail/sync,server/src/test/kotlin/ch/sonensei/cockpit/mail/sync]
prio: 2
---
## Goal
Follow-ups from the mail-header-sync review (reviews/mail-header-sync/review.md CR-6, CR-7, CR-8): make large
resyncs and paging through old mail cheap.

## Acceptance criteria
- [ ] `LinkService.hasLinks(ItemRef)` (or a batched variant) backed by an `exists` query; `MailHeaderStore.isLinked` uses it instead of resolving all links (CR-6)
- [ ] `MailHeaderSyncService.loadOlder` narrows candidates (e.g. `UID SEARCH BEFORE` the oldest cached date) instead of reading INTERNALDATE of every uncached message on every page; still picks by arrival date (CR-7)
- [ ] Class comment of `MailHeaderSyncService`: the Message-ID lookup of linked rows is described under the full resync, not the incremental sync (CR-8)
- [ ] Unit tests and the `external` GreenMail sync tests pass

## Notes
