# Code Review

Date: 2026-10-20

Task: mail-header-sync - Header sync and threading

Scope: TFP task mail-header-sync, production code at 4c0da7f0 (`git diff 8cc71438..4c0da7f0`), plus the tests in
`server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/` as present in the working tree (including the uncommitted
`ImapHelpersTests.kt` the parallel test-writer was adding).

Requirements:
- tasks/mail-header-sync.md (acceptance criteria 1-3 and the four recorded assumptions)
- SPEC.md §7.2 (sync model, threading), §4.3 (EMAIL links survive moves, re-resolve by Message-ID)
- docs/ARCHITECTURE.md and AGENTS.md project notes (module boundaries, logging rules, Flyway, conventions)
- Parent instructions: focus on IMAP protocol use, transaction/persistence correctness, concurrency, logging,
  maintainability

Git range:
- Branch: main
- HEAD: 4c0da7f0ff43f34900567783b2800111b8a3e669
- Base/start: 8cc71438019634eff8bf2c56d888454ade504fd0
- Commits reviewed: 2 (51b6e4f5, 4c0da7f0)

Summary: 0 P0, 0 P1, 3 P2, 3 P3

Note on execution: running `./gradlew test` / `./gradlew integrationTest` was denied by the sandbox in this review
session, so test results were not observed here. The findings below come from reading the code at 4c0da7f0. The
parent worker has to record its own test runs as evidence (`tasks.py verify` lists no evidence yet).

Checked and found sound (no finding): raw `UID SEARCH` / `UID FETCH` construction (only numbers, IMAP dates and
BODYSTRUCTURE-derived sections, no injection), the `n:*` filtering (RFC 9051 §6.4.8) in `uidsFrom` and in open-ended
fetches, the `(CHANGEDSINCE m)` modifier placement, the forwarding of non-FETCH responses before `handleResult`,
the read-only `EXAMINE` with CONDSTORE/QRESYNC chosen by capability, the order of Hibernate auto-flushes in `upsert`
(new rows and reattached rows are flushed before the next thread/reattach query, so the partial unique index
`(folder_id, uid)` cannot be hit by duplicates within a batch), detach-before-resync committed in its own
transaction, `finish` reloading `MailFolder` fresh (no stale version), idempotent recovery when a sync fails half way
(`finish` not reached, so the next run repeats safely), and logging (only folder/account ids at INFO).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Threading splits a conversation when a reply arrives before a parent that itself refers to the root**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailThreading.kt:92`
    - Category: Correctness
    - Problem: When a message has parents, the key prefers an existing key that equals the message's *own*
      Message-ID (`provisional.firstOrNull { it in provisionalExisting }`, line 94) over its root (`parents.first()`).
      The conversation then stays keyed by an intermediate message, and a root that arrives later only looks for
      conversations keyed by its own Message-ID (line 85), so it never joins. Trace with the test helper `Mailbox` in
      `MailThreadingTests`: `receive("<c>", inReplyTo = "<b>")` -> key `<b>`; `receive("<b>", inReplyTo = "<a>",
      references = "<a>")` -> parents `[<a>]`, `<a>` unknown, existing `{<b>}` -> key stays `<b>`, nothing merged;
      `receive("<a>", "Unrelated subject")` -> no parents, `<a>` is no existing key -> `subject:unrelated subject`.
      a ends up apart from b and c. The existing test `a message that connects two conversations merges them` only
      passes because a arrives before b. This contradicts the KDoc ("a message joins ... the conversation of every
      cached message that refers to it") and criterion 2, and the result depends on arrival order (UID order within a
      folder, path order across folders in `syncAccount`, for example replies in INBOX before the root in Sent).
    - Why it matters: Conversations shown in the threaded view are split depending on sync order. Replies that only
      carry In-Reply-To (no References) are common enough to make this visible.
    - Suggested fix: When the message has parents and none of them is cached, key it by its root (`parents.first()`)
      and merge every `provisionalExisting` key (including its own Message-ID) into that key, instead of keeping the
      own-id key. Then later messages and the root find the root key. Add the c, b, a sequence above as a unit test,
      plus a variant where the root has no subject.

- [ ] **CR-2 | "Older on demand" by UID never loads old-dated messages whose UID is above the oldest cached UID**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt:119`
    - Category: Correctness
    - Problem: The full sync selects the window by INTERNALDATE (`uidsSince`, line 151), but `loadOlder` only takes
      UIDs below the lowest cached UID (lines 119-121). In folders whose UID order is not date order, messages that
      are outside the window but have a UID above the lowest cached one are never fetched. Neither `loadOlder` nor the
      incremental sync (which only fetches UIDs from the cached UIDNEXT on) reaches them. Typical case: an Archive
      folder. COPY/MOVE keep INTERNALDATE, so a message received two years ago and archived last week has a high UID
      and an old date. With one recently-dated message at a low UID in the cache, every such message stays missing
      from the cache for good.
    - Why it matters: SPEC.md §7.2 says "older loaded on demand when scrolling", and lists sort by `received_at`.
      Scrolling past the window silently skips mail, and the folder looks complete when it is not. The recorded
      assumption ("the next messages below the oldest cached UID") does not mention this consequence.
    - Suggested fix: Select older mail by what is missing from the cache, not by a UID bound. For example, `UID SEARCH
      BEFORE <oldest cached received date>` (or `UID SEARCH ALL` minus the cached UIDs), then fetch the newest
      `limit` of those by INTERNALDATE. Add a GreenMail test that appends a message with an old INTERNALDATE after
      newer ones. If the UID approach is kept on purpose, record this limitation in the assumption so a human can
      decide.

- [ ] **CR-3 | The CONDSTORE/QRESYNC IMAP code and the DB thread-merge path are never executed in tests**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/ImapRemoteMailbox.kt:71` (also `:90`, `:142`;
      `MailHeaderStore.kt:143`)
    - Category: Tests
    - Problem: GreenMail offers neither CONDSTORE nor QRESYNC (the test KDoc says so). `MailHeaderSyncServiceTests`
      replaces `RemoteFolder` and `MailHeaderStore` with fakes and mocks. As a result these paths never run anywhere:
      - the `ResyncData` open;
      - the conversion of Angus `MessageVanishedEvent`/`MessageChangedEvent` into `QresyncChanges`, including
        `folder.getUID` on the open-time messages;
      - `UID FETCH n:* (UID FLAGS MODSEQ) (CHANGEDSINCE m)` and MODSEQ parsing;
      - the HIGHESTMODSEQ read.

      Only the service's branch selection is tested. Likewise no GreenMail/Postgres test makes a message connect two
      existing conversations. So `RepositoryThreadIndex` (`threadKeys`/`existingThreadKeys` JPQL with the
      interface projection) and the `findByAccountIdAndThreadKeyIn` rewrite of merged keys are untested against the
      database. The GreenMail tests only cover root-before-reply.
    - Why it matters: Criterion 1 names CONDSTORE/QRESYNC. These are the most protocol-sensitive lines (Angus event
      semantics, response shapes), and only a real server can show a mistake there. Recording "verified" for that part
      of criterion 1 on the strength of fakes would not be evidence.
    - Suggested fix: Add an `external` test against a Dovecot Testcontainer (it supports CONDSTORE and QRESYNC). Cover
      flag change, expunge, and new mail through both the CONDSTORE and the QRESYNC path. Add a GreenMail test where a
      reply arrives before its parent, so the merge rewrite runs against Postgres. If Dovecot is out of reach now,
      mark that part of criterion 1 as skipped with the reason instead of claiming it.

## P3 Low

- [ ] **CR-4 | After a UIDVALIDITY resync, linked messages older than the window stay detached although still on the server**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt:142`
    - Category: Correctness
    - Problem: `full()` detaches every row (line 142) but only fetches the sync window (line 151). A linked message
      older than the window (for example one loaded earlier via `loadOlder`) is still on the server, but stays
      detached until someone calls `loadOlder` far enough back. Until then `MailMessageLocator.current` finds no
      located copy, and the "Linked items" panel shows the email as gone.
    - Why it matters: SPEC.md §4.3 wants email links to survive UID changes. This case is rare (UIDVALIDITY changes
      seldom), but the link breaks without anything telling the user.
    - Suggested fix: After the window pass of a resync, look up the remaining linked detached rows by Message-ID
      (`UID SEARCH HEADER Message-ID <id>`, batched) and re-take them before `pruneDetached`.

- [ ] **CR-5 | `loadOlder` opens `\Noselect` folders and drops the result of the sync it runs first**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt:115`
    - Category: Quality
    - Problem: Unlike `syncFolder` (which checks `folder.selectable`), `loadOlder` opens any folder. For a
      `\Noselect` folder the Angus open failure is turned into a `MailConnectionException`, which reports a
      connection problem that is not one. When `loadOlder` runs `full()` first (line 117), the created/removed counts
      are discarded. So if no older message is loaded afterwards, no `MAIL_FOLDER` change event is published even
      though the cache changed (line 130).
    - Why it matters: The future mail-read-api endpoint will show a misleading error, and clients miss the refresh
      after an implicit full sync.
    - Suggested fix: Return `OlderMessagesResult(0, false)` for non-selectable folders, and publish the event when
      either the `full()` result or the older batch changed something.

- [ ] **CR-6 | `isLinked` resolves every linked item through all module resolvers just to test for existence**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt:141`
    - Category: Quality
    - Problem: `isLinked` calls `LinkService.linksOf`, which loads the links and then calls the `ItemResolver` of
      both ends of every link (tasks, notes, the email itself through `MailMessageLocator`). It runs once per vanished
      message in `reconcile` and once per detached row in `pruneDetached`, inside the sync transaction.
    - Why it matters: The cost is a query per removed row plus resolver work for every linked item, all to answer a
      yes/no question. A resolver failure there is only logged at WARN, so the extra coupling adds noise and slows
      large resyncs.
    - Suggested fix: Add a cheap existence query to the link service (for example `hasLinks(ItemRef)` backed by an
      `exists` query on `link`), or one batched query for all candidate ids, and use it here.

## Review conclusion

Material issues found; fix before completing the task.
