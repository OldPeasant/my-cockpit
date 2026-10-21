# Fix Report

Date: 2026-10-21

Task: contacts-aggregate - Contact detail aggregation and autocomplete

Scope: current task; findings CR-1..CR-3 from `reviews/contacts-aggregate/review.md`, plus the parent's note on the
blank-query check in `ContactAggregateWebTests`. Changes stay inside `server/src/main/kotlin/ch/sonensei/cockpit/contacts/aggregate/`
and `server/src/test/kotlin/ch/sonensei/cockpit/contacts/aggregate/`.

## Fixed findings

### CR-1 — Autocomplete ranks name-prefix matches only within the first 50 alphabetical matches

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/aggregate/ContactAggregateService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/aggregate/ContactAggregateEdgeCaseTests.kt`

Problem:
`autocomplete` read a single page of 50 matching contacts (ordered by display name) and re-ranked only that page. So a
contact whose name starts with `q` but sorts after 50 other matches was never suggested.

Fix:
A new private method `matchingContacts` pages through `ContactService.list(q, ...)` in pages of `CursorRequest.MAX_LIMIT`
and stops at `CONTACT_SCAN`, which is now 1000 contacts. It also stops early when the prefix-matching contacts found so
far already fill `limit` entries. Contacts that come later can only rank behind them, so stopping there does not change
the result. The results are then split with a stable `partition`: prefix matches first, then the rest, each in name
order. When the contact entries already fill `limit`, the mail query is skipped. The KDoc and the OpenAPI description
now say that both groups are ordered by name.

Regression test: `autocomplete finds contacts whose name starts with the query behind more than a page of other matches`.
It creates 205 contacts named "Aa xm NNN", which contain "m" and sort before "Martin", plus "Martin". This is more than
one `MAX_LIMIT` page and more than the old scan of 50. With q=m and limit=2 it expects Martin first, then "Aa xm 000".

Validation:
- `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.aggregate.*'` — Pass

### CR-3 — A correspondent who is a known contact was suggested as a non-contact

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/aggregate/ContactAggregateService.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/aggregate/ContactAggregateController.kt` (OpenAPI description)
- `server/src/main/kotlin/ch/sonensei/cockpit/contacts/aggregate/ContactAggregateDtos.kt` (`contactId` schema description)
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/aggregate/ContactAggregateEdgeCaseTests.kt`

Problem:
Correspondents were deduplicated only against the addresses of contacts in the suggestion list. A correspondent whose
address belongs to a contact that `q` does not match was returned with `contactId: null`.

Fix:
After the existing dedupe against suggested addresses, only the correspondents that still fit into `limit` are
resolved. A new private method `contactWithAddress` pages through `ContactService.list(address, ...)` and returns the
first contact by name that has exactly this address (case-insensitive). The exact check is needed because `list` matches
substrings. A resolved entry carries that contact and its display name. Correspondent addresses are already unique, and
every address of a suggested contact is already in the dedupe set, so a resolved entry cannot duplicate one already
suggested.

Regression test: `autocomplete links a correspondent to the contact owning its address`. Contact "Bob" owns
`X@Example.test`. Contact "Abe" owns `max@example.test`: it sorts first and contains `x@example.test` as a substring, but
is not that address. With the mail header `"Hannah Smith" <x@example.test>` and q=hannah, the test expects Bob's
`contactId` and the name "Bob". Two other correspondents, including `ax@example.test`, stay unlinked.

Validation:
- `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.aggregate.*'` — Pass

### CR-2 — Documented exclusions not tested (remaining gaps)

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/aggregate/ContactAggregateEdgeCaseTests.kt`

Problem:
The test-writer's `ContactAggregateEdgeCaseTests` already covers these: own-address exclusion (account and identity),
Drafts/Spam, detached messages, cancelled events and the 20-mail limit. Two gaps remained: a contact in the Trash, and
the 20-event limit.

Fix:
Two tests were added:
- `related of a contact in the Trash is 404`: DELETE with csrf and `If-Match: "0"` returns 204, then `/related` returns 404.
- `related returns only the next 20 occurrences`: creates 23 hourly events with the contact as attendee and expects
  exactly the first 20, in start order.

Validation:
- `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.aggregate.*'` — Pass

### PARENT-4 — Blank-query check in ContactAggregateWebTests was likely double-encoded

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/contacts/aggregate/ContactAggregateWebTests.kt`

Problem:
`get("$CONTACTS/autocomplete?q=%20")` passes a URI template string to MockMvc, so `%20` may be encoded again. The test
would then send the literal text `%20` instead of a blank query.

Fix:
The test now passes the parameter with `mockMvc.get("$CONTACTS/autocomplete") { param("q", " ") }` and still asserts
an empty list.

Validation:
- `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.aggregate.*'` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.contacts.aggregate.*'` — Pass. ktlint is clean.
  ContactAggregateWebTests: 5 passed. ContactAggregateEdgeCaseTests: 14 passed (10 existing + 4 new).
  CorrespondenceQueryTest: 4 passed. Total: 23 passed, 0 failed, 0 skipped.

Full test suite:
- Not run by the fixer. The parent worker runs it.

Lint/type-check/build:
- ktlintCheck: Pass (as part of the command above). `api/openapi.yaml` must be regenerated because the descriptions of
  the autocomplete operation and of `ContactSuggestionDto.contactId` changed. The parent regenerates it and runs
  OpenApiContractTest.

`.taskcheck`:
- Not run (the parent worker runs `sh scripts/check.sh`).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. The full suite, `.taskcheck` and the OpenAPI regeneration are left
to the parent worker.
