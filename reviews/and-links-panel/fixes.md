# Fix Report

Date: 2026-11-08

Task: and-links-panel - Android: shared linked-items panel with add, remove, create linked

Scope: current task; findings CR-1, CR-2, CR-3 of `reviews/and-links-panel/review.md`, plus the extra lint item from the parent's `lintDebug` run (a DE typo, handled as part of CR-3).

## Fixed findings

### CR-1 - Contact detail opened from search, a linked item or a birthday lost its links and had dead tappable rows

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/search/SearchItemScreen.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/BirthdayContact.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsScreensTest.kt`

Problem:
`SearchContact` and `BirthdayContact` called the bare `ContactDetailContent`. Those views showed no links panel, and their related rows had a click ripple but did nothing.

Fix:
Only the call sites changed. `SearchContact` and `BirthdayContact` now render `ContactDetailRoute`, so they get the links panel and related rows that open their items. `ContactDetailRoute` got an optional `viewModel` parameter (default `hiltViewModel()`). `BirthdayContact` passes its own view model through, so `BirthdayContactTest` still works with a fake. Without Hilt, the route now skips the links view model and panel, the same way `LinkedItemsSection` does. `ContactDetailCallbacks.openRelated` is now nullable and defaults to `null`. Without it, `RelatedRow` leaves out the `clickable` modifier (no ripple, no click action). New test: `without a way to open them the related rows are not tappable` (`assertHasNoClickAction`).

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.contacts.*' --tests 'ch.sonensei.cockpit.calendar.edit.BirthdayContactTest' --tests 'ch.sonensei.cockpit.search.*'` (in one combined run, see summary) - Pass (ContactsScreensTest 16, ContactsRouteInAppTest 9, BirthdayContactTest 4, SearchRouteInAppTest 10, all search suites 0 failures)

### CR-2 - Link search could show results of a stale query with a stuck progress bar

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/core/links/LinkedItemsViewModel.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/core/links/LinkedItemsViewModelTest.kt`

Problem:
`search()` returned early, when the trimmed query equalled `resultsQuery`, before cancelling `searchJob`. A newer search that was still running could then overwrite the results for the text in the field. Because `searching` is derived from `query` and `resultsQuery`, the progress bar then kept spinning.

Fix:
`searchJob?.cancel()` now runs before the early return. `searching` is derived (`query.trim() != resultsQuery`), so it is false again right away and no separate reset is needed. The test-writer's file already had a test for the debounce-window case (`typing back to the shown text while a newer search waits keeps the shown results`). I added a regression test for the in-flight case: `typing back to the shown text while a newer search is answering drops that answer`. In this test the request for "plans" has been sent and is answered after the text is back to "plan". The test checks that the results stay those of "plan" and that the progress bar stops.

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.links.*'` - Pass (LinkedItemsViewModelTest 38, LinkedItemsPanelTest 27, LinksRepositoryTest 16; 0 failures)

### CR-3 - Superseded link loading and strings left behind (duplicate requests, lint)

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepository.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsRepository.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsModels.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/core/links/LinkedItemsPanel.kt`
- `android/app/src/main/res/values/strings_task_detail.xml`, `android/app/src/main/res/values-de/strings_task_detail.xml`
- `android/app/src/main/res/values/strings_contacts.xml`, `android/app/src/main/res/values-de/strings_contacts.xml`
- `android/app/src/main/res/values-de/strings_links.xml`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/gtd/task/TaskDetailContentTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsModelsTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/contacts/ContactsScreensTest.kt`, `ContactsRouteInAppTest.kt`, `ContactsViewModelsTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/BirthdayContactTest.kt`

Problem:
Task and contact detail still fetched `GET /api/v1/links` on every load, but nothing rendered the result, and the panel fetched the links a second time. Nine strings had no references left, and lint (`UnusedResources`, warnings as errors) failed on them. The parent's lint run also reported `links_close` = "Schliessen" in DE as a Typos error.

Fix:
- Task: removed `TaskWriteApi.listTaskLinks`, `TaskLink`, `TaskDetail.links`, the links request in `load()` and `LinkResponse.otherEnd`, plus the now unused imports.
- Contacts: removed `ContactLinksApi` and its provider, the links request in `related()`, `RelatedLink`, `ContactRelated.links` and `toRelatedLink`. `toRelated()` no longer takes links.
- Tests: the load tests now check that no `/api/v1/links` request is made (task) and that `related()` makes exactly one request (contacts). Tests that only covered the removed link loading were deleted: the two links-failure tests in TaskDetailRepositoryTest and the link-direction test in ContactsModelsTest. The other tests only lost the removed `links` argument or fixtures.
- Strings: grep found no reference outside the string files. Deleted from EN and DE alike: `task_detail_no_browser`, `task_detail_links`, `task_detail_link_open`, `task_detail_link_untitled`, `task_detail_link_unavailable`, `contacts_links`, `contacts_link_deleted`, `contacts_untitled`.
- `links_searching` is now the content description of the search progress bar while a search runs. When an add is running (busy), the bar has no description, as before.
- DE `links_close`: "Schliessen" changed to "Schließen".

Validation:
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.TaskDetailRepositoryTest' --tests 'ch.sonensei.cockpit.gtd.task.TaskDetailContentTest' --tests 'ch.sonensei.cockpit.contacts.*'` (in the combined run) - Pass (TaskDetailRepositoryTest 24, TaskDetailContentTest 10, ContactsRepositoryTest 5, ContactsModelsTest 6, ContactsViewModelsTest 7)
- `./gradlew ktlintCheck lintDebug` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `./gradlew ktlintFormat` - ran. It also reformatted `= {` spacing in the test-writer's untracked `LinkedItemsViewModelTest.kt`.
- `./gradlew testDebugUnitTest` with `--tests` for `core.links.*`, `contacts.*`, `calendar.edit.BirthdayContactTest`, `gtd.task.TaskDetailRepositoryTest`, `gtd.task.TaskDetailContentTest`, `search.*`. First run: 231 tests, 3 failed. All 3 were in the test-writer's concurrently edited `core/links` tests (`a failed removal keeps the link and says why`, `creating a linked task ...`, `a failed action is shown below the links`). They test remove, create and action-error paths that this fix did not touch. All other suites had 0 failures.
- Rerun of `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.core.links.*'` - Pass (81 tests, 0 failures). The test-writer's file had changed on disk meanwhile.

Full test suite:
- Not run (left to the parent with `.taskcheck`).

Lint/type-check/build:
- `./gradlew ktlintCheck lintDebug` - Pass (compiles the debug variant)

`.taskcheck`:
- Not run (the parent runs `sh scripts/check.sh`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
