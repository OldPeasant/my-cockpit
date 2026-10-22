# Code Review

Date: 2026-10-22

Task: and-contacts - Android contacts

Scope: Final code of the task at HEAD cb9ff834e on top of base 988b870fe. Code commits: implementation 5bbedf2f8 (rebased from 301e45f33, reviewed earlier), fix commit da27e7802 (fixes for the earlier CR-1 and CR-2, the new tests ContactsModelsTest and ContactsRouteInAppTest, and URI/scroll fixes in ContactsRouteInAppTest), and final-pass test commit cb9ff834e. Paths: android/app/src/main/java/ch/sonensei/cockpit/contacts/, android/app/src/main/res/values/strings_contacts.xml, android/app/src/test/java/ch/sonensei/cockpit/contacts/. `git show --stat cb9ff834e` confirms that this commit changes only ContactsScreensTest.kt, ContactsViewModelsTest.kt, reviews/and-contacts/review.md and reviews/and-contacts/tests.md. Production code is identical to da27e7802, which was reviewed in full earlier. The review of da27e7802 had no shell access; the files in scope were read in full at that HEAD, and fixes.md was used as the list of what changed in da27e7802.

Requirements:
- tasks/and-contacts.md acceptance criteria and recorded assumptions (read-only contacts; related items shown but not tappable; ACTION_DIAL / ACTION_SENDTO)
- SPEC.md §8.3 (Contacts), §9.2 item 7 (Android: Contacts view, call/mail intents)
- AGENTS.md project notes; existing Android feature conventions
- reviews/and-contacts/fixes.md (fix claims checked against the code)

Git range:
- Branch: main
- HEAD: cb9ff834e7be6d807dbc69c1ea698581f0e125d6
- Base/start: 988b870feaed4377f2966acc56e8874ffa6f3035
- Commits reviewed: 3 code commits (5bbedf2f8, da27e7802, cb9ff834e; the last one changes only tests), plus commits that touch only tasks/

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Earlier findings (review of 301e45f33) and their resolution

- [x] **CR-1 (P2, Tests) | The repository test for related items depended on request arrival order.** Resolved. `ContactsRepositoryTest.kt:104-112` now uses a `mockwebserver3.Dispatcher` that answers by `request.url.encodedPath` (`/api/v1/contacts/{id}/related` or `/api/v1/links`, anything else 404). The test still compares the two recorded requests as a set (`:119-120`), so it no longer depends on which concurrent request arrives first. The parent reports a separate rerun of ContactsRepositoryTest as passing.
- [x] **CR-2 (P3, Correctness) | After a failed reload, the messages could contradict the screen.** Resolved.
    - (a) `ContactsListViewModel.search()` (`ContactsViewModels.kt:46-51`) now resets `loaded = false` together with the new query. `loaded` becomes true again only after an `Ok` result (`:64`), so the empty-match text (`ContactsScreen.kt:188`) shows only after a successful answer for the current query. `refresh()` with the same query keeps `loaded`. Covered by `ContactsViewModelsTest` "a failed new search does not claim that nothing matches it" and `ContactsScreensTest` "a failed new search shows no empty-match text".
    - (b) The related-items banner (`ContactsScreen.kt:316-328`) uses the new `*_stale` strings ("may be out of date") when `state.related != null`. When nothing was loaded, it keeps the original "are not shown" / "could not be loaded" strings. Both new strings are in `strings_contacts.xml:34-35`. All four combinations (offline or failed, with or without earlier items) are covered in ContactsScreensTest, and the view model case is covered by "a failed refresh keeps the related items shown, with a message".

## Final-pass test additions (cb9ff834e)

Checked against the production code at HEAD. All of them are correct and not flaky:
- `ContactsScreensTest` "related items kept from an earlier load whose reload failed say they may be out of date": the asserted text equals `contacts_related_failed_stale` (`strings_contacts.xml:35`), the non-stale text is asserted absent, and the earlier item stays shown. This matches `ContactsScreen.kt:318-324`.
- `ContactsScreensTest` "related items never loaded while offline say they are not shown": the asserted text equals `contacts_related_offline` (`strings_contacts.xml:32`). It also checks that no "may be out of date" text appears and that the "nothing related" text is not shown when `related == null`. Both match the screen code.
- `ContactsScreensTest` "a sent mail and a timed event show when they happen": "To them, " matches `contacts_mail_to` with a non-empty time, because `trimEnd` only removes a trailing separator when `at` is empty (`ContactsScreen.kt:427-431`). Time zone and locale do not make it flaky. The instants are at noon UTC on 2026-10-03 and 2026-11-02, so the year stays 2026 in every zone. `ofLocalizedDateTime` uses ISO chronology and ASCII digits by default (`DecimalStyle.STANDARD`), so "2026" appears in any locale. The comment says the date part is the same in every time zone. That is not true for UTC+12 to +14, but the test asserts only the year, so this has no effect. The event assertion checks only the year. It shows that a date is rendered, but not that a time-of-day is included. This is acceptable for a smoke check and is not reported as a finding.
- `ContactsViewModelsTest` "retrying a failed new search that then succeeds shows its result": `loaded` stays false after the Offline result, then becomes true with `message == null` and an empty list after `refresh()`. `coVerify(exactly = 2)` on `list("Bob")` holds: the debounced search makes one call and `refresh()` makes the other. This matches `ContactsViewModels.kt:43-67`. It covers the recovery path, where retry clears the error and a real empty result then shows the empty-match text.
- The extra assertion in "a failed load keeps the last list and says why" (`loaded` stays true after a failed refresh of the same query) matches `loaded = it.loaded || result is Ok`.
- No production behaviour was weakened, and no test was deleted or skipped. Test data is fictitious.

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
    - Suggested fix: When `contact != null && message != null`, also show a `Banner` (for example with a "may be out of date" variant of `contacts_offline` / `contacts_not_loaded`) above the detail list, and add a screen test for it. This can be fixed or recorded as accepted; it does not block the criteria. Still open at cb9ff834e, because production code is unchanged.

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
    - Compose screen states, now including the stale and never-loaded related banners and the mail/event time rendering: ContactsScreensTest.
    - View model behaviour, now including recovery after a failed new search: ContactsViewModelsTest.
    - Mapping of server answers: ContactsModelsTest.
    - HTTP calls and error mapping: ContactsRepositoryTest.
    - The test fixes in ContactsRouteInAppTest compare the decoded `scheme` / `schemeSpecificPart`. This is the right correction, because `Uri.fromParts` encodes `+` and `@`, and the production intents were already correct. It does not weaken the assertions.
- Gradle was not run by the reviewer. The parent worker reports, on cb9ff834e: `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*' --rerun` ran 46 tests with 0 failures, and ktlint was clean. On da27e7802 the parent also reported `./gradlew ktlintCheck lintDebug` clean, and the full Android unit suite with coverage passing.
