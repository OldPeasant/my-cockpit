# Code Review

Date: 2026-10-21

Task: contacts-aggregate - Contact detail aggregation and autocomplete

Scope: Re-review of the final code at `314a570ad8202e202a89f9f0ad0e377d2018a0d0` against base
`03f7b79b876a3375eeacf7f2daca88ce965929b4`: production code in `server/src/main/kotlin/ch/sonensei/cockpit/contacts/aggregate/`
and tests in `server/src/test/kotlin/ch/sonensei/cockpit/contacts/aggregate/`. The previous review covered
`fe802ca84838678c8912cac845568883c4c35c4c`, which included the fixes for CR-1..CR-3 (`reviews/contacts-aggregate/fixes.md`).
Since then, commit `314a570ad` (test-writer) added one test,
`related lists each occurrence of recurring timed and all-day series with the contact, by start`, in
`ContactAggregateEdgeCaseTests.kt`. The parent reports that no production code changed. Bash/Git was not available to this
reviewer, so the files were read directly at the working tree. The working tree matches HEAD `314a570ad` except for the
untracked `reviews/contacts-aggregate/`. The reviewer re-read `ContactAggregateService.kt` and `ContactAggregateDtos.kt` at
this revision to check the new test against them. Generated files (`api/openapi.yaml`, `web/src/app/api/generated/**`)
were not re-read. The parent reports that they were regenerated and that `OpenApiContractTest` passes.

Reviewed revision: 314a570ad8202e202a89f9f0ad0e377d2018a0d0

Requirements:
- tasks/contacts-aggregate.md: acceptance criteria and the three recorded assumptions
- SPEC.md §8.3 (contact detail: Waiting For items, recent emails from/to, upcoming events)
- AGENTS.md project notes (module boundaries, conventions)
- Parent-supplied validation on 314a570ad: `./gradlew test --tests 'ch.sonensei.cockpit.contacts.aggregate.*'` -> 24/24
  pass (Web 5, EdgeCase 15, CorrespondenceQuery 4). On fe802ca84: ktlintCheck clean, OpenApiContractTest passes. The
  reviewer did not re-run these because Bash and Gradle are not available to it.

Git range:
- Branch: main
- HEAD: 314a570ad8202e202a89f9f0ad0e377d2018a0d0
- Base/start: 03f7b79b876a3375eeacf7f2daca88ce965929b4
- Commits reviewed: 3 code/test commits (53d6d7185, fe802ca84, 314a570ad), plus `assume:` commits that change only the
  task file

Files reviewed in full: `ContactAggregateService.kt`, `CorrespondenceQuery.kt`, `ContactAggregateController.kt`,
`ContactAggregateDtos.kt`, `ContactAggregateWebTests.kt`, `ContactAggregateEdgeCaseTests.kt`. Context:
`contacts/domain/ContactService.kt`, for the `list` semantics: substring match on the display name or an email
address, ordered by `displayName, id`, keyset cursor.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Review of the new test (314a570ad)

`ContactAggregateEdgeCaseTests.kt:272-300` creates two series:
- a weekly timed series, `FREQ=WEEKLY;COUNT=3`, starting `2026-03-02T08:00Z` in `Europe/Zurich`;
- a daily all-day series, `FREQ=DAILY;COUNT=2`, starting `2026-03-04`. Its attendee address is in mixed case
  (`Ann.Doe@Example.test`).

The test then asserts the five occurrences in order, with their `title`, `seriesId`, `occurrence`, `start`,
`startDate`, `endDate` and `allDay` values. It is consistent with the production code:
- **Order.** `ContactAggregateService.kt:80` sorts by `start`, or by the all-day `startDate` at UTC midnight. That gives
  03-02 08:00Z, 03-04, 03-05, 03-09 08:00Z, 03-16 08:00Z, which is the order the test expects. All three weekly instants
  are before the 29 March DST change, so 08:00Z is 09:00 Zurich time for each of them.
- **Fields.** `ContactEventDto.of` (`ContactAggregateDtos.kt:101-116`) writes `occurrence` from `occurrenceId.at`
  (an instant) or `occurrenceId.date`. It leaves `start` null for all-day occurrences, and `endDate` is exclusive. The
  expected values (`"2026-03-04"`, `endDate` `"2026-03-05"`, and so on) match this.
- **Case-insensitive match.** The mixed-case attendee address checks the `lowercase()` comparison at line 79 for events.
  Before this test, only mail addresses had a case-insensitive test.
- **Edge cases.** The test interleaves all-day and timed occurrences, so it would catch a sort that puts all-day items
  first or last, or one that drops them because `start` is null.

The test asserts behavior through the HTTP response, not implementation details, and uses fictitious data. It adds
useful coverage of acceptance criterion "upcoming events" for recurring and all-day series, and it needed no
production change. It does not assert `eventId` for the occurrences. That is acceptable, because `seriesId` identifies
the series, and the single-event test (`related returns only the next 20 occurrences`) already checks `eventId`. No new
findings.

## Status of prior findings

- **CR-1 (P2) Prefix ranking limited to the first 50 matches: fixed** (in fe802ca84, unchanged since). `matchingContacts`
  (`ContactAggregateService.kt:121-139`) pages through `ContactService.list` in pages of `CursorRequest.MAX_LIMIT`, up to
  `CONTACT_SCAN` = 1000 contacts. Then a stable `partition` puts prefix matches first, and each group stays in name
  order. The early stop is sound: it stops once the prefix entries found so far (counted exactly as `entries()` would
  emit them) reach `limit`. Any contact fetched later ranks behind them, both inside the prefix group (stable DB order)
  and as a non-prefix match. The loop ends at `nextCursor == null` or at the scan cap. Regression test:
  `autocomplete finds contacts whose name starts with the query behind more than a page of other matches`
  (205 + 1 contacts). It would fail with the old 50-contact scan. The KDoc and the OpenAPI description match the
  behavior.
- **CR-2 (P2) Documented exclusions not tested: fixed** (in fe802ca84). `ContactAggregateEdgeCaseTests` covers these
  cases:
  - the owner's account and identity addresses are left out. The test uses an upper-case variant of the account
    address, and a non-owner address that contains "owner", so the test proves the filter is needed;
  - Spam, Drafts and detached (`uid is null`) rows are excluded, and folders without a role are kept;
  - cancelled events are excluded;
  - a contact in the Trash -> 404;
  - only the newest 20 mails and the next 20 occurrences are returned;
  - Waiting For items are collected across pages;
  - LIKE wildcards are escaped and regex metacharacters are matched literally;
  - the default limit of 10 applies;
  - since 314a570ad: recurring timed and all-day occurrences, see above.
  The blank-query test in `ContactAggregateWebTests.kt:248-254` sends the parameter with `param("q", " ")`.
- **CR-3 (P3) A known contact's correspondent was suggested as a non-contact: fixed** (in fe802ca84). `contactWithAddress`
  (`ContactAggregateService.kt:150-161`) resolves each correspondent that fits into `limit`, with an exact,
  case-insensitive address check after the substring `list` match. The test
  `autocomplete links a correspondent to the contact owning its address` covers the case-insensitive match and the
  substring look-alike (`max@` vs `x@`, `ax@`). No duplicate entries can result. When contact entries reach `limit`, the
  method returns before this lookup. Otherwise every address of a suggested contact is in `known`, so a resolved
  correspondent always belongs to a contact that is not suggested. The DTO and OpenAPI descriptions were updated.
- **CR-4 (P3): still open**, unchanged at 314a570ad (see below).
- **CR-5 (P3): still open**, unchanged at 314a570ad (see below). The new test does not touch autocomplete.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-4 | Autocomplete resolves each correspondent with its own unindexed contact query (N+1 per keystroke)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/aggregate/ContactAggregateService.kt:110-113`, `:150-161`
    - Category: Quality
    - Problem: For each correspondent kept after the dedupe, `contactWithAddress` calls
      `contacts.list(address, MAX_LIMIT page)`. Each call is a separate read-only transaction and query. The query runs
      `lower(display_name) like` and the jsonb function `contacts_email_matches` over the whole contact table. With the
      default limit, one autocomplete request can make up to 10 of these queries, and up to 50 with `limit=50`. They come
      in addition to the up to 5 pages in `matchingContacts`, the `ownAddresses` query and the correspondent scan.
      Autocomplete runs on every keystroke.
    - Why it matters: For a single-user address book of a few hundred contacts, each query is cheap and the overall
      latency stays acceptable, so this is not a defect today. But the cost grows with `limit` times the contact count,
      in the request that is most sensitive to latency.
    - Suggested fix: Resolve all remaining correspondent addresses in one step. Options: load the active contacts once
      (`ContactService.all()`) only when correspondents remain, and build a lower-cased address -> contact map; or add a
      batch lookup by exact address to `ContactService` in a follow-up task, since `contacts/domain` is outside this
      task's touches. If neither is done, record the trade-off.

- [ ] **CR-5 | The early stop in `matchingContacts` has no test**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/contacts/aggregate/ContactAggregateService.kt:133-136`
    - Category: Tests
    - Problem: The regression test for CR-1 ends because the cursor runs out (`nextCursor == null` on the second page).
      No test stops on `prefixEntries >= limit` while more pages remain, and no test hits the `CONTACT_SCAN` cap. For
      example, the stop could count non-prefix contacts, or ignore that a contact without email gives one entry. Contacts
      would then be missing or ranked wrongly, and no current test would detect it.
    - Why it matters: The stop condition is the subtle part of the CR-1 fix. A later "simplification" could break it
      without any failing test.
    - Suggested fix: Add a case with at least `limit` prefix contacts on page 1 plus later pages that hold more prefix
      matches. Assert the exact first `limit` entries. Optionally, include a contact without email and one with several
      emails among the prefix matches.

## Review conclusion

No material issues found.

All four acceptance criteria are covered by the implementation. The parent's reported validation on 314a570ad
(24/24 aggregate tests) is consistent with the tests read here. The test added in 314a570ad is correct against the
production code and needs no change. CR-1, CR-2 and CR-3 are fixed. The paging loops always end: `matchingContacts` is
capped at `CONTACT_SCAN`, and `contactWithAddress` and `waitingFor` stop when the cursor is exhausted. No security,
integration or module-boundary regressions were found. The two remaining P3 findings (CR-4, CR-5) are optional
improvements and do not block completion.
