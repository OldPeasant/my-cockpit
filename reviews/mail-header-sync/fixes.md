# Fix Report

Date: 2026-10-20

Task: mail-header-sync - Header sync and threading

Scope: current task (findings CR-1..CR-6 of `reviews/mail-header-sync/review.md`). Production fixes for CR-1, CR-2,
CR-4 and CR-5 and most tests were made in an earlier, interrupted fixer session and committed as 6384ec09 (same
content as 3bee5cd5, rebased onto newer `tasks/` commits). This session reviewed that WIP against each finding,
traced the threading cases by hand, and finished the one missing piece: the Dovecot test did not pass because its
CONDSTORE-only server config was never applied. It also removed a leftover debug line from the WIP.

## Fixed findings

### CR-1 — Threading splits a conversation when a reply arrives before a parent that refers to the root

Files changed (6384ec09):
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailThreading.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailThreadingTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStoreTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/HeaderSyncGreenMailTests.kt`

Problem:
A message with parents but none of them cached kept an existing provisional key equal to its own Message-ID, so
the root that arrived later did not find the conversation.

Fix:
The key is now the conversation of the first cached parent, else the root (`parents.firstOrNull()`), before any
provisional key. Provisional keys (own id, intermediate parents) are merged into it. Hand trace c(irt b) -> `<b>`;
b(refs a) -> key `<a>`, merged {`<b>`}; a -> its own key `<a>` already exists -> `<a>`. Tests: unit tests
`a reply before its parent that refers to the root keeps the conversation together when the root arrives last`
(c, b, a order) and `a root without subject arriving after its replies joins them`; store test across three
folders/batches; GreenMail test with c and b in INBOX and the root last in Sent.

Validation:
- `cd server && ./gradlew ktlintFormat check` — Pass (MailThreadingTests 33/33 among the sync unit suites)
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*'` — Pass

### CR-2 — "Older on demand" by UID skips old-dated messages with high UIDs

Files changed (6384ec09):
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/RemoteFolder.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/ImapRemoteMailbox.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/HeaderSyncGreenMailTests.kt`

Problem:
`loadOlder` only took UIDs below the lowest cached UID.

Fix:
`loadOlder` takes every server UID missing from the cache (`uidsFrom(1)` minus cached UIDs). When more are missing
than `limit`, it reads their INTERNALDATE (`receivedDates`, replaces `uidsBelow`) and loads the `limit` most
recently arrived ones. `hasMore` = more missing than loaded. Tests: service unit tests (ranking, no date read when
everything fits, ties by UID) and GreenMail test `older mail is picked by arrival date, not by UID` (an old-dated
message appended after newer ones is loaded first).

Validation:
- `cd server && ./gradlew ktlintFormat check` — Pass
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*'` — Pass

### CR-3 — CONDSTORE/QRESYNC IMAP code and the DB thread-merge path never run in tests

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/HeaderSyncDovecotTests.kt` (added in 6384ec09, fixed in this session)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/HeaderSyncGreenMailTests.kt` (6384ec09)

Problem:
GreenMail has neither CONDSTORE nor QRESYNC, and no Postgres test merged two existing conversations.

Fix:
`HeaderSyncDovecotTests` (`@ExternalTest`, Dovecot 2.3.21 Testcontainer) runs flag change, expunge and new mail
once through the QRESYNC open (asserts the VANISHED UIDs and the changed UIDs/flags/MODSEQ from
`qresyncChanges`) and once against a server that announces only CONDSTORE (asserts `qresync == false`,
`flagsChangedSince` with `CHANGEDSINCE`, the UID search for expunges, HIGHESTMODSEQ). Both then check the
`syncFolder` result and the cache.
In this session: the WIP test failed (`remote.status.qresync` was true on the CONDSTORE server) because the
override config copied to `/etc/dovecot/conf.d/90-test.conf` never got there. The image has no `conf.d` folder and
the local engine (Podman 5.8.9) does not create missing parents on copy. The config is now copied to
`/tmp/90-test.conf` and the container command creates `conf.d`, copies the file and execs `dovecot -F`. That works
on Docker and Podman. The leftover `DEBUGCAP` debug print was removed.
DB merge path: GreenMail tests `a reply that connects two cached conversations merges them in the database` and
`a reply that arrives before its parent stays in the conversation when the root arrives last` (reply cached
before its parent, so `existingThreadKeys` and the merged-key rewrite run against Postgres).

Validation:
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.sync.HeaderSyncDovecotTests'` — Fail
  before the fix (`expected:<false> but was:<true>` at the `qresync` assertion), Pass after (2 tests, 0 failures)
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*'` — Pass (GreenMail 13/13, Dovecot 2/2)

### CR-4 — After a UIDVALIDITY resync, linked messages older than the window stay detached

Files changed (6384ec09):
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/RemoteFolder.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/ImapRemoteMailbox.kt`
- tests in `MailHeaderStoreTests.kt`, `MailHeaderSyncServiceTests.kt`, `HeaderSyncGreenMailTests.kt`

Problem:
A resync only fetched the window, so linked rows older than the window stayed detached.

Fix:
After the window pass of a resync, `retakeLinked` reads the Message-IDs of linked detached rows
(`MailHeaderStore.linkedDetachedMessageIds`) and asks the server for them in batches of 20
(`UID SEARCH OR HEADER Message-ID "..." ...`, quoted, printable ASCII only). Only headers whose Message-ID matches
exactly are taken (the server matches substrings). Then `pruneDetached` runs. GreenMail test
`a resync finds linked messages older than the window again by Message-ID` checks that the same row id is located
again.

Validation:
- `cd server && ./gradlew ktlintFormat check` — Pass
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*'` — Pass

### CR-5 — `loadOlder` opens `\Noselect` folders and drops the result of its implicit full sync

Files changed (6384ec09):
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt`

Problem:
A non-selectable folder raised a misleading connection error, and no change event was sent when only the
implicit full sync changed the cache.

Fix:
`loadOlder` returns `OlderMessagesResult(0, false)` for a non-selectable folder without opening it, and publishes
the `MAIL_FOLDER` event when the full sync or the older batch changed something. Unit tests cover both.

Validation:
- `cd server && ./gradlew ktlintFormat check` — Pass

## Not fixed

### CR-6 — `isLinked` resolves every linked item just to test for existence

Status:
Out of scope (deferred)

Reason:
A cheap fix needs an existence query on links (`LinkService.hasLinks(ItemRef)` backed by an `exists` query in
`LinkRepository`), which lives in `core/items`, outside this task's touches (`server/.../mail/sync`). Calling
`core.items.infra.LinkRepository` or querying the `link` table from the mail module would break the
module-boundary rule (modules talk only through services). The current code is correct, only slower on large
resyncs. Suggested follow-up task: add `LinkService.hasLinks` and use it in `MailHeaderStore.isLinked`.

## Validation summary

Focused tests:
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.sync.HeaderSyncDovecotTests'` — Pass (2/2)
  (before the fix: 1 failed)
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*'` — Pass
  (HeaderSyncGreenMailTests 13/13, HeaderSyncDovecotTests 2/2)

Full test suite:
- `cd server && ./gradlew ktlintFormat check` — BUILD SUCCESSFUL. Sync unit suites: 18, 33, 20 and 28 tests,
  0 failures

Lint/type-check/build:
- ktlint is part of `./gradlew check` above — Pass (ktlintFormat changed nothing)

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("== all checks passed": server, web, android)

Remaining unresolved findings:
1 (CR-6, deferred as out of scope)

Notes:
- `loadOlder` lists all server UIDs and, when more than `limit` are missing, reads INTERNALDATE for every missing
  one. That is correct but grows with the number of uncached old messages (one FETCH per 500 UIDs). A possible
  later narrowing is `UID SEARCH BEFORE <oldest cached date + 1 day>`. No finding asks for this, so it was left as is.

## Final status

Some findings remain blocked. All validated in-scope findings (CR-1..CR-5) are fixed and validation passed. CR-6 is
deferred as outside the task's touches.
