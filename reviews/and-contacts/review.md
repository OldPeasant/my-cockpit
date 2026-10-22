# Code Review

Date: 2026-10-22

Task: and-contacts - Android contacts

Scope: Final code of the task at HEAD da27e7802 on top of base 988b870fe: implementation commit 5bbedf2f8 (rebased from 301e45f33, reviewed earlier) and fix commit da27e7802 (fixes for the earlier CR-1 and CR-2, the new tests ContactsModelsTest and ContactsRouteInAppTest, and URI/scroll fixes in ContactsRouteInAppTest). Paths: android/app/src/main/java/ch/sonensei/cockpit/contacts/, android/app/src/main/res/values/strings_contacts.xml, android/app/src/test/java/ch/sonensei/cockpit/contacts/. Shell and git commands were denied in this session. The files listed in the scope were therefore read in full at HEAD, and fixes.md was used as the list of what changed in da27e7802.

Requirements:
- tasks/and-contacts.md acceptance criteria and recorded assumptions (read-only contacts; related items shown but not tappable; ACTION_DIAL / ACTION_SENDTO)
- SPEC.md §8.3 (Contacts), §9.2 item 7 (Android: Contacts view, call/mail intents)
- AGENTS.md project notes; existing Android feature conventions
- reviews/and-contacts/fixes.md (fix claims checked against the code)

Git range:
- Branch: main
- HEAD: da27e78021df336ba2e11cef8a27f911c28e21fd
- Base/start: 988b870feaed4377f2966acc56e8874ffa6f3035
- Commits reviewed: 2 code commits (5bbedf2f8, da27e7802), plus commits that touch only tasks/

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Earlier findings (review of 301e45f33) and their resolution

- [x] **CR-1 (P2, Tests) | The repository test for related items depended on request arrival order.** Resolved. `ContactsRepositoryTest.kt:104-112` now uses a `mockwebserver3.Dispatcher` that answers by `request.url.encodedPath` (`/api/v1/contacts/{id}/related` or `/api/v1/links`, anything else 404). The test still compares the two recorded requests as a set (`:119-120`), so it no longer depends on which concurrent request arrives first. The parent reports a separate rerun of ContactsRepositoryTest as passing.
- [x] **CR-2 (P3, Correctness) | After a failed reload, the messages could contradict the screen.** Resolved.
    - (a) `ContactsListViewModel.search()` (`ContactsViewModels.kt:46-51`) now resets `loaded = false` together with the new query. `loaded` becomes true again only after an `Ok` result (`:64`), so the empty-match text (`ContactsScreen.kt:188`) shows only after a successful answer for the current query. `refresh()` with the same query keeps `loaded`. Covered by `ContactsViewModelsTest` "a failed new search does not claim that nothing matches it" and `ContactsScreensTest` "a failed new search shows no empty-match text".
    - (b) The related-items banner (`ContactsScreen.kt:316-328`) uses the new `*_stale` strings ("may be out of date") when `state.related != null`. When nothing was loaded, it keeps the original "are not shown" / "could not be loaded" strings. Both new strings are in `strings_contacts.xml:34-35`. All four combinations (offline or failed, with or without earlier items) are covered in ContactsScreensTest (`:163-194`), and the view model case is covered by "a failed refresh keeps the related items shown, with a message".

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | A failed reload of the contact itself is not shown once the contact is on screen**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt:306-312`
    - Category: Correctness
    - Problem: `ContactDetailViewModel.refresh()` sets `message` from the `get()` result and keeps the earlier contact (`ContactsViewModels.kt:121,124`). `ContactDetailContent` renders `state.message` only when `contact == null`. Two cases follow. A retry from the related-items banner where `get()` fails and `related()` succeeds shows the earlier contact with no banner. Reopening a contact that was deleted on the web (both calls fail with 404) shows the old contact data with only the related-items banner. In both cases the user is not told that the contact data itself may be out of date.
    - Why it matters: This is a rare offline or deleted-contact case. The screen stays usable and retry is still possible, but it quietly shows contact data that may be stale, which is the same kind of mismatch as the earlier CR-2.
    - Suggested fix: When `contact != null && message != null`, also show a `Banner` (for example with a "may be out of date" variant of `contacts_offline` / `contacts_not_loaded`) above the detail list, and add a screen test for it. This can be fixed or recorded as accepted; it does not block the criteria.

## Review conclusion

No material issues found.

What was checked and found sound in the final code:
- Criterion 1 (list/search, detail with call/mail intents and related items):
    - The list loads every page (page size 200, at most 50 pages), searches through `q` after a 300 ms debounce, and a blank search lists everything.
    - The detail shows phones, emails, addresses, birthday and notes. The header has Call/Email buttons and each number and address can be tapped.
    - `ContactIntents.dial` builds `ACTION_DIAL` with `tel:` and the dialable digits. `ContactIntents.mail` builds `ACTION_SENDTO` with `mailto:` plus `EXTRA_EMAIL`. When no app handles the intent, a toast says so.
    - Related Waiting For tasks, mails, events and links are combined from `/contacts/{id}/related` and `/links?type=CONTACT&id=`, and each link shows its other end.
- Loading and async behaviour:
    - A single `loadJob` per view model is cancelled before each new load, so an older search cannot overwrite a newer one.
    - `CancellationException` is rethrown.
    - Every success or failure path clears `loading`, and Retry stays available.
    - Opening another contact clears the one shown.
- Security: no secrets and no logging of contact data. The HTTP failure message carries only the status code. The new tests use fictitious data and tokens.
- Criterion 2, test coverage:
    - Intent construction and launch: ContactIntentsTest (Robolectric).
    - Real dialer and mail intents started from the Hilt `MainActivity` through the Contacts screen, including the no-app toast, back navigation and a configuration change: ContactsRouteInAppTest.
    - Compose screen states: ContactsScreensTest.
    - View model behaviour: ContactsViewModelsTest.
    - Mapping of server answers: ContactsModelsTest.
    - HTTP calls and error mapping: ContactsRepositoryTest.
    - The test fixes in ContactsRouteInAppTest compare the decoded `scheme` / `schemeSpecificPart`. This is the right correction, because `Uri.fromParts` encodes `+` and `@`, and the production intents were already correct. It does not weaken the assertions.
- Gradle could not be run in this session. Per the parent worker's report on da27e7802: `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*'` ran 42 tests with 0 failures, ContactsRepositoryTest passed when rerun separately, `./gradlew ktlintCheck lintDebug` was clean, and the full Android unit suite with coverage passed.
