# Code Review

Date: 2026-11-08

Task: and-links-panel - Android: shared linked-items panel with add, remove, create linked

Scope: `git diff 79e60ee80 94276d088 -- android` (task commits df137aae4..94276d088). Tests that the test-writer is adding in parallel under `android/app/src/test/java/ch/sonensei/cockpit/core/links` are out of scope.

Requirements:
- tasks/and-links-panel.md acceptance criteria and recorded assumptions
- SPEC.md §4.3 (links), docs/PARITY.md
- Web reference `web/src/app/shared/links` (linked-items.ts)
- AGENTS.md project conventions (lint is part of the contract, no secret logging, feature folders)

Git range:
- Branch: main (task commits merged from origin/wip/and-links-panel at e52751d6f; android sources identical to 94276d088)
- HEAD: 94276d088 (reviewed revision)
- Base/start: 79e60ee80
- Commits reviewed: 6 (df137aae4, ea1825596, 5474f9f66, a9d03acc2, 54168a1ca, 94276d088)

Summary: 0 P0, 1 P1, 2 P2, 0 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Contact detail opened from search, from a linked item or from a birthday lost its links and has dead "tappable" rows**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/search/SearchItemScreen.kt:92`, `android/app/src/main/java/ch/sonensei/cockpit/calendar/edit/BirthdayContact.kt:40` (caused by `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt:392` and the removed `related.links` rendering in `related()`)
    - Category: Correctness
    - Problem: The task removed the read-only links list from the shared `ContactDetailContent`/`related()` and moved links into the new `linksPanel` slot, and made the related rows clickable via `ContactDetailCallbacks.openRelated`. Only `ContactDetailRoute` passes `linksPanel` and `openRelated`. The two other callers of `ContactDetailContent`, `SearchContact` (global search, and every CONTACT opened in place via `SearchItemScreen`, including a contact tapped in a links panel or from `ContactDetailRoute` itself) and `BirthdayContact` (calendar), pass neither. On those paths the contact now shows no links at all (before this task it showed them read-only), and the waiting-for, mail and event rows show a click ripple but do nothing (default `openRelated = { _, _ -> }`).
    - Why it matters: Criterion 2 ("used in ... contact detail; contact related rows become tappable") is only met for the Contacts tab; the other contact-detail entry points regressed (links disappeared) and present controls that silently do nothing.
    - Suggested fix: Make `SearchContact` and `BirthdayContact` render `ContactDetailRoute(id, onClose = ...)` (it already handles in-place opening and the panel), or at minimum pass `linksPanel` and `openRelated`. `search/` and `calendar/edit/` are outside the task's `touches`, so if that is not acceptable, keep the read-only links in `related()` for callers without a panel and only render the clickable modifier when a real `openRelated` is supplied, and record the follow-up with `tasks.py assume`/a new task.

## P2 Medium

- [ ] **CR-2 | Link search can show results of a stale query with a stuck progress bar**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/core/links/LinkedItemsViewModel.kt:141`
    - Category: Correctness
    - Problem: `search()` returns early when the trimmed query equals `resultsQuery` (and there is no search error) before cancelling `searchJob`. Sequence: results are shown for "ab"; the user types "abc" (a job for "abc" starts its debounce/request); the user deletes back to "ab" before it finishes. The early return keeps the "abc" job alive; when it completes it sets `resultsQuery = "abc"` and the "abc" hits while the field shows "ab". `searching` (`query.trim() != resultsQuery`) then stays true, so the progress bar keeps spinning and no new search starts until the user types again.
    - Why it matters: An older request overwrites the result for the newer input (out-of-order async result), leaving wrong hits that can be linked by mistake and a loading indicator that never ends.
    - Suggested fix: Cancel `searchJob` before the early return (or only short-circuit when no job is active), and add a view-model test for type-then-revert within the debounce window.

- [ ] **CR-3 | Superseded link loading and strings left behind (duplicate requests, lint risk)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailRepository.kt:271`, `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsRepository.kt:84`, `android/app/src/main/res/values/strings_task_detail.xml:13`, `android/app/src/main/res/values/strings_task_detail.xml:61`, `android/app/src/main/res/values/strings_contacts.xml:28`, `android/app/src/main/res/values/strings_links.xml:14`
    - Category: Quality
    - Problem: The task replaced the old link lists, but `TaskDetailRepository` still fetches `GET /api/v1/links?type=TASK` and `ContactsRepository` still fetches `GET /api/v1/links?type=CONTACT` on every detail load; nothing renders `TaskDetail.links` (outside tests) and, apart from CR-1's lost callers, `ContactRelated.links` is no longer rendered either. The panel issues its own list call, so every task or contact detail open now requests the links twice. In addition these strings have no references left in `android/` (EN and DE): `task_detail_no_browser`, `task_detail_links`, `task_detail_link_open`, `task_detail_link_untitled`, `task_detail_link_unavailable`, `contacts_links`, `contacts_link_deleted`, `contacts_untitled`, and the new `links_searching` is never used.
    - Why it matters: Wasted network calls and dead code paths that can drift. Lint's default-enabled `UnusedResources` check runs under `warningsAsErrors = true` (`android/app/build.gradle.kts:80`); the only lint report in the clone predates these commits, so criterion 4 (`lintDebug` passes) must be confirmed on the final revision.
    - Suggested fix: Drop the TaskLink/RelatedLink loading (and their tests) or, if CR-1 is fixed by keeping read-only links for some callers, keep only what is still rendered; remove the unreferenced strings from both locales; run `./gradlew lintDebug` on the final code.

## P3 Low

None.

## Review conclusion

Material issues found; fix before completing the task.

Notes (verified, no finding): the panel lists links in both directions, opens tasks in-app and other in-app types through the screens' `open` handler, opens web targets in the browser with a fallback toast, adds via the global search API, removes only manual links (404 counts as removed), and creates a linked task/note through a title dialog (recorded assumption). Stale results after an item change are guarded by `itemSeq`. EN and DE strings match one-to-one. No logging was added, so no secrets or contents are logged. Task detail does not disable the panel for read-only tasks, which matches the web panel.
