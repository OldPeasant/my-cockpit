# Code Review

Date: 2026-10-20

Task: mail-header-sync - Header sync and threading

Scope: TFP task mail-header-sync, final revision cde8b0b2 (task diff `7982562b..cde8b0b2`). This round checks that
the fixes for the earlier findings CR-1..CR-5 and TW-1 hold, and looks for new problems in the final code. Test files
the parallel test-writer changed after cde8b0b2 are not reviewed.

Requirements:
- tasks/mail-header-sync.md (acceptance criteria 1-3 and the six recorded assumptions)
- SPEC.md §7.2 (sync model, threading), §4.3 (EMAIL links survive moves and UID changes)
- docs/ARCHITECTURE.md and AGENTS.md project notes (module boundaries, logging rules, conventions)
- reviews/mail-header-sync/fixes.md (rounds 1 and 2) and the previous review.md (CR-1..CR-6)
- Parent instructions: review the final revision, check the earlier fixes, keep the CR-6 status

Git range:
- Branch: main
- HEAD: cde8b0b2f6b4ee3b4e895aed5f4b7c4aa995bbf9 (read from `.git/refs/heads/main`)
- Base/start: 7982562bfa14d1979d9b8b2c884ce23f4df04b57
- Commits reviewed: the task commits in 7982562b..cde8b0b2

Summary: 0 P0, 0 P1, 0 P2, 3 P3 (CR-6 deferred from the earlier round, CR-7 and CR-8 new)

Limits of this review session: the sandbox denied every shell command. That ruled out `git diff`, `git log`,
running tests and `tasks.py verify`. So the review reads the final content of every production file in the task's
touches (`server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/`: `MailThreading.kt`, `MailHeaderSyncService.kt`,
`MailHeaderStore.kt`, `MailSyncRepository.kt`, `ImapRemoteMailbox.kt`, `RemoteFolder.kt`) and parts of the tests,
at HEAD = cde8b0b2. I could not check the exact diff hunks, the commit list, or changes outside those files (if
any). The parent worker must base its `tasks.py verify` evidence on its own test runs (the unit suites, the GreenMail
and Dovecot `integrationTest` runs). This review did not see any of those runs.

Earlier findings checked in the final code:
- CR-1 (reply before a parent that refers to the root): fixed. In `MailThreading.assign` (lines 87-101) a reply with
  no cached parent is keyed by its root (`parents.firstOrNull()`), and every existing provisional key (its own id,
  intermediate parents) is merged into it. Hand trace of c(irt b), b(refs a), a: `<b>` -> `<a>` with `<b>` merged ->
  `subject:...` with `<a>` merged. The result is one conversation.
- TW-1 (order-dependent subject fallback): fixed. A message without parents always takes `default` (its subject key,
  else its own Message-ID, else `id:<row>`), whatever arrived before it. Provisional keys equal to its Message-ID are
  merged in (line 100). I traced the orders b,a,d / b,d,a / d,b,a: all end in `subject:budget`. The KDoc (lines
  21-31) matches the behaviour. Thread keys cannot collide across kinds (`<...>`, `subject:`, `id:`).
- CR-2 (older on demand by UID): fixed. `loadOlder` loads the missing UIDs ranked by INTERNALDATE (lines 126-137),
  and assumption 5 records this.
- CR-3 (CONDSTORE/QRESYNC and the DB merge path untested): addressed by `HeaderSyncDovecotTests` and the GreenMail
  merge tests, per fixes.md. I could not run them here (see limits above).
- CR-4 (linked rows older than the window stay detached after a resync): fixed. `retakeLinked` (lines 181-193) uses
  a batched, quoted `UID SEARCH OR HEADER Message-ID ...` limited to printable ASCII, keeps only exact Message-ID
  matches, and runs before `pruneDetached`.
- CR-5 (`loadOlder` on `\Noselect` folders, lost change event): fixed (lines 121 and 145).

Checked and found sound (no finding): the raw IMAP commands (`UID SEARCH`/`UID FETCH`) contain only numbers, IMAP
dates, BODYSTRUCTURE sections and quoted printable-ASCII Message-IDs, so no injection is possible. Also sound: the
`n:*` filtering, the `(CHANGEDSINCE m)` modifier, the read-only `EXAMINE` with QRESYNC only when a known state
exists, and how the incremental sync picks QRESYNC, CONDSTORE or a full flag fetch. UIDNEXT and HIGHESTMODSEQ are
taken from the open-time status, so messages and flag changes that arrive during a sync are picked up again by the
next sync, and the `!in known` filter prevents duplicates. Detaching happens before the resync, and `finish` reloads
the folder. Per-folder locking is in place. The INFO logs contain only folder and account ids.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | `isLinked` resolves every linked item through all module resolvers just to test for existence**
  (status: deferred, out of the task's touches, recorded as assumption 6 in the task file)
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt:150`
    - Category: Quality
    - Problem: The code is unchanged since the earlier round. `isLinked` calls `LinkService.linksOf`, which loads the
      links and resolves both ends of each one. This runs once per vanished or detached row in `reconcile`,
      `linkedDetachedMessageIds` and `pruneDetached`, all inside the sync transaction.
    - Why it matters: It is correct but slow on large resyncs, and resolver failures add WARN noise.
    - Suggested fix: Make it a follow-up task (it is outside this task's touches): add
      `LinkService.hasLinks(ItemRef)` backed by an `exists` query and use it here. The parent worker should add the
      follow-up with `tasks.py new` if none exists yet, so the deferral is not lost.

- [ ] **CR-7 | Each `loadOlder` call reads INTERNALDATE for every uncached message in the folder**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt:127`
    - Category: Correctness (resilience/performance)
    - Problem: When more than `limit` messages are missing, `remote.receivedDates(missing)` (line 132) fetches
      `UID INTERNALDATE` for all of them, one `UID FETCH` per 500 UIDs, and also runs `UID SEARCH UID 1:*` (line 127).
      This repeats on every page. For example, a 60,000-message archive with a 12-month window of 5,000 cached
      messages costs about 110 FETCH round trips per page of 100. Scrolling to the end costs O(N²/limit) in total.
      fixes.md mentions this under "Notes", but no assumption records it.
    - Why it matters: "Older on demand when scrolling" (SPEC.md §7.2) becomes slow on exactly the large folders where
      it is used most. The folder lock is held the whole time, which also blocks the background sync of that folder.
    - Suggested fix: Narrow the candidates before reading dates. For example, use `UID SEARCH BEFORE <oldest cached
      received_at + 1 day>` minus the cached UIDs, and fall back to the full missing set only when that comes back
      empty. Or record the cost as an assumption so a human can accept it.

- [ ] **CR-8 | The service KDoc puts the resync "retake linked rows" step under the incremental-sync bullet**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt:47`
    - Category: Quality
    - Problem: The sentence "Linked rows the window pass did not find are then looked up by Message-ID before the
      rest is removed." is the last line of the **Incremental sync** bullet (lines 44-47). The behaviour it describes
      (`retakeLinked`, line 170) only runs in the full sync after a UIDVALIDITY change.
    - Why it matters: Readers of the class contract will believe the incremental sync searches by Message-ID, and it
      does not.
    - Suggested fix: Move the sentence into the **Full sync** bullet (after "found again by Message-ID").

## Review conclusion

No material issues found. The earlier findings CR-1..CR-5 and TW-1 are resolved in the final code. CR-6 remains
deferred as outside the task's touches. CR-7 and CR-8 are optional low-priority improvements. Because the sandbox
blocked shell access, this conclusion rests on reading the final files, not on the exact diff or on observed test
runs (see "Limits of this review session").
