# Code Review

Date: 2026-11-08

Task: and-links-panel - Android: shared linked-items panel with add, remove, create linked

Scope: re-review of the final revision ae6701993. Full task diff `git diff 79e60ee80 ae6701993 -- android`, with focus on the fix commit ae6701993 (fixes for CR-1..CR-3 of the first review, plus the new tests under `android/app/src/test/java/ch/sonensei/cockpit/core/links`).

Requirements:
- tasks/and-links-panel.md acceptance criteria and recorded assumptions
- SPEC.md §4.3 (links), docs/PARITY.md
- Web reference `web/src/app/shared/links`
- AGENTS.md project conventions (lint is part of the contract, no secret logging, feature folders)
- Previous review (CR-1..CR-3), reviews/and-links-panel/fixes.md, reviews/and-links-panel/tests.md

Git range:
- Branch: main
- HEAD: 7ace6f535 (adds only the test report on top of ae6701993; no android changes after ae6701993)
- Reviewed revision: ae6701993
- Base/start: 79e60ee80
- Commits reviewed: 7 (df137aae4, ea1825596, 5474f9f66, a9d03acc2, 54168a1ca, 94276d088, ae6701993)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of prior findings

- **CR-1 (P1) Contact detail from search / linked item / birthday lost links and had dead rows: Resolved.** `SearchContact` (`search/SearchItemScreen.kt:66`) and `BirthdayContact` (`calendar/edit/BirthdayContact.kt:21`) now render `ContactDetailRoute`, so they get the links panel and openable related rows. `ContactDetailRoute` takes an optional `viewModel` (BirthdayContact passes its own, so `BirthdayContactTest` keeps its fake) and skips the links view model without Hilt. `ContactDetailCallbacks.openRelated` is nullable and `RelatedRow` only adds `clickable` when a handler is supplied (`contacts/ContactsScreen.kt:211`, `:597`); covered by the new test `without a way to open them the related rows are not tappable`. The call-site edits outside `touches` are recorded as an assumption in the task file.
- **CR-2 (P2) Stale link search result with a stuck progress bar: Resolved.** `searchJob?.cancel()` now runs before the early return (`core/links/LinkedItemsViewModel.kt:142`), so a newer in-flight search can no longer overwrite the results when the text is typed back. Regression tests cover both the debounce-window and the in-flight case in `LinkedItemsViewModelTest`.
- **CR-3 (P2) Superseded link loading and unused strings: Resolved.** `TaskWriteApi.listTaskLinks`, `TaskLink`, `TaskDetail.links`, `ContactLinksApi`, `RelatedLink` and `ContactRelated.links` are gone, so each detail open makes one links request (the panel's); repository tests assert no `/api/v1/links` request for tasks and exactly one request for contact related items. `git grep` at ae6701993 finds no remaining reference to the removed types or strings. The unreferenced strings were removed in EN and DE; `links_searching` is now the progress bar's content description; DE `links_close` is spelled "Schließen". The fixer reports `./gradlew ktlintCheck lintDebug` passing.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-4 | Nested contact details share one unkeyed ContactDetailViewModel**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt:126`
    - Category: Quality
    - Problem: `ContactDetailRoute` obtains `ContactDetailViewModel` with an unkeyed `hiltViewModel()`. Since the fix, a contact opened from a contact (a CONTACT link in the panel opens `SearchItemScreen`, whose `SearchContact` renders another `ContactDetailRoute`) gets the same view model instance as the outer contact, in the same store owner (Contacts tab, calendar birthday, search). Viewing contact B replaces A's state; on back, A's route recomposes for one frame with B's state until `LaunchedEffect(id)` calls `open(A)`, which clears and reloads A. The links view model does not have this problem because it is keyed per item (`linksViewModelKey`).
    - Why it matters: Brief flash of the wrong contact and a full reload (scroll position lost) when returning from a linked contact. Correct state is restored, so the impact is cosmetic.
    - Suggested fix: Key the default, e.g. `hiltViewModel(key = "contact-detail-$id")`, as the links panel already does; or accept as is.

## Review conclusion

No material issues found.

Notes (verified, no finding): the panel and view model behavior noted in the first review is unchanged apart from the CR-2 fix. The new core/links tests (LinkedItemsViewModelTest, LinkedItemsPanelTest, LinksRepositoryTest) cover listing, opening in-app vs. web targets, add via global search, remove (manual only, 404 counts as removed, double tap), create linked task/note, item-change races, stale search answers and error paths, and they assert behavior through a fake source and MockWebServer rather than internals. Removed tests only covered the deleted link loading. No logging was added. Test and lint runs were not repeated by this reviewer (sub-agents cannot run Gradle here); the parent's recorded runs are the evidence for criterion 4.
