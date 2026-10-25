# Code Review

Date: 2026-10-25

Task: mail-sync-perf - Cheaper link checks and loadOlder candidate narrowing in mail sync

Scope: `git diff eeb4f9dc..98e9f945` (task commits 0dfb037c implementation, 98e9f945 tests + tests.md), committed changes only

Requirements:
- `tasks/mail-sync-perf.md` acceptance criteria (#1-#4)
- `reviews/mail-header-sync/review.md` CR-6, CR-7, CR-8 (origin of the task)
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` conventions
- Parent instructions: focus on `newestMissing` (IMAP SINCE day granularity), `LinkRepository.existsTouching`, `oldestReceivedAt`

Git range:
- Branch: main
- HEAD: 98e9f9450b3971759ab5812ea0bbde68bb28c0a5
- Base/start: eeb4f9dc0cef3ec4b9b62cc62e2a708b9fe58bbb
- Commits reviewed: 2 (0dfb037c, 98e9f945); working tree clean

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Checks run by the reviewer:
- `cd server && ./gradlew test --tests '*MailHeaderSyncServiceTests*' ktlintCheck` -> BUILD SUCCESSFUL.
- I could not run the Postgres tests (`LinkServiceTests`, `MailHeaderStoreTests`) or `HeaderSyncGreenMailTests`
  because no Docker daemon is available. So the JPQL of `existsTouching` and `oldestReceivedAt` was only checked by
  reading it, and the real IMAP `SEARCH SINCE` path was not run end to end.
- `python tasks.py verify mail-sync-perf`: no criterion has recorded evidence yet.

Checked and found sound (no finding):
- `newestMissing`, correctness of the cut: the window is `[day(anchor - span), now]`. It always contains every missing
  message that arrived after the oldest cached one (the gaps), so those are never dropped. The test
  `loadOlder still picks missing messages that arrived after the oldest cached one first` covers this. Suppose the
  server maps INTERNALDATE to a calendar day with a single timezone, whether UTC or its local zone, DST included. Then
  that day is a non-decreasing function of the instant. So every message outside the window (day < D) arrived before
  every message inside it (day >= D), and the `limit` newest missing messages are among the candidates. Sending the
  UTC date of `since` while the server reads it in its own zone only moves the cut by under a day. It does not break
  the partition. For the case of a per-message zone, see CR-3.
- Loop termination: `missing.size > limit` holds at the call site. The span doubles until `since <= EPOCH`, which
  takes about 12 iterations from today and a few more for a far-future anchor, with no Duration overflow. The second
  operand of the exit condition is redundant but harmless. Concurrent expunges or arrivals between `uidsFrom` and
  `uidsSince` can only shrink the candidates, which stay a subset of `missing`.
- `oldestReceivedAt` (`MailSyncRepository.kt:23`): `min(m.receivedAt) ... where m.folderId = :folderId and
  m.uid is not null` matches the located definition (`MailMessage.isLocated`, together with the folder filter). It
  ignores null dates and returns null for an empty folder. It can use the partial index `mail_message_folder_received`
  (`folder_id, received_at desc ... where uid is not null`).
- `existsTouching` (`LinkRepository.kt:25-28`): the parentheses are correct, type and id are matched together, and
  both directions are covered. `select count(l) > 0` is a valid Hibernate 6 selection, and the indexes `link_source`
  and `link_target` back both arms of the OR. `isLinked` now uses `hasLinks` (`MailHeaderStore.kt:175`). AC #1 is met
  in code. See CR-2 for the remaining "exists" point.
- CR-8 / AC #3: the Message-ID lookup sentence now sits under **Full sync** (`MailHeaderSyncService.kt:56-57`).
- Architecture: mail still reaches links only through `LinkService` (core). No new cross-module coupling, no schema
  change, no secrets, and no logging of message data.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | One old cached message makes the narrowing useless again: the window is anchored on `min(receivedAt)` of the cache**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt:228` (anchor), `:152`, and the KDoc at `:61-62` and `:217-221`
    - Category: Correctness (performance / resilience, AC #2)
    - Problem: `newestMissing` starts its window at `store.oldestReceivedAt(folder) - 7 days` and only grows it
      further into the past. Every missing message between that anchor and now is a candidate, and its INTERNALDATE
      is fetched on every page. The anchor is the oldest arrival of *any* located row, and three paths routinely cache
      rows far older than the cached frontier:
      (a) the incremental sync caches every new UID whatever its date (`incremental`, lines 311-316). Moving an old
      message into Archive, the case the `loadOlder` KDoc names, gives it a new UID with its old INTERNALDATE;
      (b) `searchServer` caches found messages of any age;
      (c) `retakeLinked` caches linked messages older than the window after a resync.
      Example: an Archive with a 12-month sync window, into which the user moves one 3-year-old message. From then
      on, every `loadOlder` page reads the dates of all missing messages from the last 3 years, which is about
      2 years of uncached mail. That is the CR-7 cost the task was meant to remove. The result stays correct; only the
      cost reverts. The KDoc ("only ... the missing messages that arrived shortly before the oldest cached one")
      assumes the oldest cached row is the edge of a contiguous cache, and that assumption does not hold.
    - Why it matters: AC #2 asks for paging through old mail without reading INTERNALDATE of (nearly) every uncached
      message on every page. In archive folders, where `loadOlder` matters most, a single moved or searched old
      message quietly brings back the old behaviour, and the folder lock is held the whole time. No test covers an
      outlier anchor.
    - Suggested fix: Don't take the anchor from `min(receivedAt)`. One option: grow the window back from `now` (or
      from the start of the sync window) until it holds at least `limit` missing UIDs. That is always correct by the
      same argument and needs no DB query. It costs about log2(age/7 days) `UID SEARCH SINCE` calls. Another option:
      anchor on something an outlier cannot move, such as the oldest arrival of the rows cached by the window/loadOlder
      passes. Add a unit test with one cached row far older than the rest, and update both KDocs to match.

## P3 Low

- [ ] **CR-2 | `existsTouching` is a `count(...) > 0`, not an exists query**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/items/infra/LinkRepository.kt:25-28`
    - Category: Quality (AC #1 wording)
    - Problem: AC #1 asks for `hasLinks` "backed by an `exists` query". `select count(l) > 0 ... where (...) or (...)`
      counts every matching link instead of stopping at the first. It is functionally right. The cost is small for
      emails, which have few links, but it grows with heavily linked items (projects, notes) if `hasLinks` gets reused.
    - Why it matters: it is a small deviation from the stated criterion and from what the KDoc implies ("resolves
      nothing", cheap).
    - Suggested fix: use `select case when exists (select 1 from Link l where ...) then true else false end`, or two
      derived `existsBySourceTypeAndSourceId` / `existsByTargetTypeAndTargetId` calls, each of which uses its own
      index with `limit 1`.

- [ ] **CR-3 | The KDoc's ordering guarantee assumes the server applies one timezone to every INTERNALDATE**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt:218-220`
    - Category: Correctness (edge case)
    - Problem: RFC 3501 §6.4.4 defines `SINCE` as comparing the internal date "disregarding time and timezone". A
      server that takes the date in each message's own stored offset is possible, for example with APPENDed or
      migrated mail that carries different offsets. With such a server, the cut is not monotonic within about a day.
      Example: A is `2026-03-01T23:30-05:00` (day 03-01, outside) and B is `2026-03-02T01:00+02:00` (day 03-02,
      inside), yet A arrived after B. When the candidates near the window's start make it into the page (the candidate
      count is close to `limit`), a page can then skip a message that is up to about 26 h newer than one it loads.
      That message is loaded by a later page. The KDoc states the guarantee without this condition. The unit fake
      models only a UTC-day server.
    - Why it matters: the arrival order of pages can be off by about a day at the window edge on such servers. The
      impact is low.
    - Suggested fix: the dates of the candidates are read anyway. After sorting, accept the window only if the
      `limit`-th newest date is at least about 2 days after `since`; otherwise double again. Or narrow the KDoc claim
      to "for a server that maps INTERNALDATE to days in one timezone".

## Review conclusion

Material issues found; fix before completing the task. CR-1 (P2) partly undoes the performance goal of AC #2 in the
archive scenario. CR-2 and CR-3 are low priority. Separately, AC #1 (the Postgres tests of `hasLinks`, `isLinked` and
`oldestReceivedAt`) and AC #4 (the `external` GreenMail sync tests, the only end-to-end check of the real
`SEARCH SINCE` narrowing) have no run evidence here, because Docker is unavailable. They must be run where Docker
exists, or be recorded as skipped with that reason. They must not be claimed as verified.
