# Fix Report

Date: 2026-10-22

Task: and-contacts - Android contacts

Scope: Current task. Findings CR-1 and CR-2 from `reviews/and-contacts/review.md`, plus three failing tests in `ContactsRouteInAppTest` reported by the parent worker (test bugs; production behaviour is correct).

## Fixed findings

### CR-1 — The repository test for related items depends on request arrival order

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsRepositoryTest.kt`

Problem:
`related combines the contact's related items and its links` queued the `/related` body and the `/links` body on MockWebServer's FIFO queue. `ContactsRepository.related()` sends both requests at the same time, so either one can arrive first. When the links request arrived first, it got the wrong body and the test failed.

Fix:
For this test, the server uses a `mockwebserver3.Dispatcher` that answers by `request.url.encodedPath`: `/api/v1/contacts/{id}/related` gets the related JSON, `/api/v1/links` gets the links JSON, and anything else gets 404. The assertions are unchanged. They still check both request paths and queries as a set, so arrival order doesn't matter.

Validation:
- `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'` — Pass (ContactsRepositoryTest 5/5)

### CR-2 (a) — A failed new search showed "No contacts match <new query>"

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsViewModels.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsViewModelsTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsScreensTest.kt`

Problem:
`ContactsListUiState.loaded` was never reset when the query changed. A failed search for a new query could then show the empty-match text for that query, even though no answer for it had arrived.

Fix:
`search()` now sets `loaded = false` together with the new query. `loaded` becomes true again only when a successful answer arrives for the current query. `refresh()` with the same query keeps it unchanged. Two new tests: `a failed new search does not claim that nothing matches it` (view model: `loaded` is false after the switch and stays false after an Offline result) and `a failed new search shows no empty-match text` (screen: with `loaded = false`, the EMPTY text is not shown but the banner is).

Validation:
- `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'` — Pass (ContactsViewModelsTest 6/6, ContactsScreensTest 11/11)

### CR-2 (b) — The related banner said items "are not shown" while older items were shown

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt`
- `android/app/src/main/res/values/strings_contacts.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsViewModelsTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsScreensTest.kt`

Problem:
When a related-items refresh failed, the old `related` items stayed on screen, but the banner said "Mail, events and Waiting For items are not shown".

Fix:
I kept the current behaviour, where the last loaded items stay visible, which matches the offline-tolerant list. Only the banner wording now depends on whether items are shown. When `state.related != null`, the banner uses two new strings: `contacts_related_offline_stale` ("Can't reach the server. Mail, events and Waiting For items may be out of date.") and `contacts_related_failed_stale` ("... could not be reloaded and may be out of date."). When nothing has been loaded yet, the old strings are still used. There is only the default `values/` locale, so no translations were needed. New tests: `a failed refresh keeps the related items shown, with a message` (view model) and `related items kept from an earlier load are marked as possibly out of date` (screen: the stale text is present, "are not shown" is absent, and the item is still shown).

Validation:
- `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'` — Pass

### RT-1 — `Call opens the dialer with the first number, ready to dial` compared the encoded URI string

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsRouteInAppTest.kt`

Problem:
`Uri.fromParts("tel", ...)` encodes `+`, so `data.toString()` is `tel:%2B41441234567`, not `tel:+41441234567`. The production intent is correct.

Fix:
The test now compares `data.scheme == "tel"` and the decoded `data.schemeSpecificPart == "+41441234567"`.

Validation:
- `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'` — Pass (ContactsRouteInAppTest 9/9)

### RT-2 — `Email opens a mail app with the address` compared the encoded URI string

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsRouteInAppTest.kt`

Problem:
Same cause as RT-1: `mailto:anna%40example.org`.

Fix:
The test now compares `scheme == "mailto"` and `schemeSpecificPart == "anna@example.org"`.

Validation:
- same run as above — Pass

### RT-3 — `a number in the phone list dials that number` could not find the second phone row

Files changed:
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsRouteInAppTest.kt`

Problem:
The detail is a `LazyColumn`. In the full activity, the second phone row is below the fold and not composed, so `onNodeWithTag(contact-phone:044 765 43 21)` found no node.

Fix:
The test first scrolls to the row with `onNodeWithTag(ContactsTags.DETAIL).performScrollToNode(hasTestTag(ContactsTags.phone(...)))`, as ContactsScreensTest does, and then clicks it. The URI assertion uses scheme and schemeSpecificPart, the same as RT-1.

Validation:
- same run as above — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'` — Pass. The fresh JUnit XML results (2026-10-22 10:30Z) show 42 tests and 0 failures: ContactsRouteInAppTest 9, ContactsModelsTest 7, ContactIntentsTest 4, ContactsScreensTest 11, ContactsViewModelsTest 6, ContactsRepositoryTest 5. ktlintCheck was part of the same successful run.

Full test suite:
- Not run by the fixer. Further Gradle invocations were denied in this sub-agent session. The parent worker should run `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh`.

Lint/type-check/build:
- ktlintCheck: Pass (see above). Kotlin compilation: Pass (implied by the test run). `lintDebug`: Not run (permission denied); needed for the two new string resources.

`.taskcheck`:
- Not run (permission denied); left to the parent worker.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and focused validation passed. Android lint, repeated runs of ContactsRepositoryTest and `.taskcheck` are still to be run by the parent worker.
