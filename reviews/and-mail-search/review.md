# Code Review

Date: 2026-11-01

Task: and-mail-search - Android mail: search box with 'search on server' option

Scope: `git diff 4f351d00b 99caa6310` (implementation commit 99caa6310; HEAD 7cc2dddb0 is the same code change after a rebase, `git diff 99caa6310 HEAD` touches only `tasks/`)

Requirements:
- tasks/and-mail-search.md acceptance criteria and recorded assumptions
- api/openapi.yaml `GET /api/v1/mail/search`, MailSearchResponse, MailServerSearchResponse, MailServerSearchFailureResponse
- AGENTS.md project notes, existing Android mail list conventions (MailListViewModel, MailScreen, MailRepository)
- web/src/app/features/mail/list/ (parity reference)

Git range:
- Branch: main
- HEAD: 7cc2dddb0994ed346bd1086a2770dcfa437b77d7
- Base/start: 4f351d00b
- Commits reviewed: 1 (99caa6310)

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Returning from the read view resets the search results to the top**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailSearchContent.kt:160`
    - Category: Correctness
    - Problem: `Results` creates its `LazyColumn` without a hoisted `LazyListState`. `MailRoute` (MailScreen.kt:118-163) removes `MailListContent` from composition while a mail is open, so after tapping a result and going back, the results list is rebuilt from scroll position 0. The mailbox list avoids this on purpose: `MailRoute` hoists `rememberLazyListState()` and documents "back returns to the list where it was".
    - Why it matters: A user working through search results (up to 50) loses their place after each mail they open. That breaks the screen's own back-navigation pattern and the "tapping one opens the read view" flow.
    - Suggested fix: Hoist a second `rememberLazyListState()` (or a saveable one) in `MailRoute` for search results, pass it through `MailListContent` to `MailSearchContent`/`Results`, and reset it to the top when a new search is submitted. Add a Compose test for it or document why it is left out.

## P3 Low

- [ ] **CR-2 | "Search on server" can run a different query than the one in the box**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailSearchContent.kt:64-70` and `MailListViewModel.kt:233-238`
    - Category: Correctness
    - Problem: `searchOnServer()` repeats `search.query`, the last submitted query. The field text lives separately in `SearchBox` (`rememberSaveable` `text`). If the user edits the field after a cached search and taps "Search on server" without submitting, the button stays visible and the server search runs the old query while the box shows the new text.
    - Why it matters: The results shown do not match the visible query, which is confusing. It is minor because the user usually submits first.
    - Suggested fix: Hide or disable the server button while the field text (trimmed) differs from `search.query`, or have the button submit the current text with `server=true`.

- [ ] **CR-3 | Closing search with system Back is untested**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailSearchContent.kt:59`
    - Category: Tests
    - Problem: The recorded assumption says "Back or the X closes it". `MailSearchScreenTest` covers only the X button (`SEARCH_CLOSE`). No test covers the `BackHandler` path.
    - Why it matters: A later change to the back handling in the shell or in `MailRoute` could take over Back without any failing test.
    - Suggested fix: Add a Compose test that triggers back (for example through `LocalOnBackPressedDispatcherOwner` / `onBackPressedDispatcher.onBackPressed()` in the test activity) and checks that the `close` callback ran.

## Verified without findings

- API contract: query parameters `q`, `accountId`, `folderId`, `server` (sent only when true), and `limit=50` match the OpenAPI spec. A folder search sends both `accountId` and `folderId`; the spec accepts that (400 only for a folder of another account). The DTOs read `items`, `hasMore`, `server.failures[].accountId` and `server.truncated`. Unknown fields such as `message`, `loaded` and `searchedFolders` are tolerated by the shared serializer, and `MailSearchRepositoryTest` exercises this with real JSON.
- Out-of-order results: `searchGeneration` plus job cancellation ensures that an older or closed search never overwrites a newer one, including on failure. Both cases are tested. `mailCall` rethrows `CancellationException`.
- Loading and error states: every result path clears `loading`. Offline and Failed reuse `MailListMessage` and `ListMessage` with Retry, like the list. A failure during a server search keeps the cached results. A new query starts from a fresh state, so stale results of another query are never shown.
- Read view integration: `markSeen` and `removed` update search results as well as the mailbox list.
- Strings: all 10 new keys are present in `values` and `values-de`. The German text uses the informal form like the existing strings.
- No secrets, logging of mail content, or unsafe input handling were introduced. The query is trimmed and capped at the server maximum of 200.

## Review conclusion

Material issues found; fix before completing the task. Only CR-1 (P2) is material. CR-2 and CR-3 are low-priority improvements. No acceptance criterion is unmet.
