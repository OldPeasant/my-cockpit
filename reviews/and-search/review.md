# Code Review

Date: 2026-11-01

Task: and-search - Android: global search

Scope: `git diff 05a5084d45a8e8b87687455df17dbdbf7c04b9ee..862308208d0cd8070905a48a9eab8c06aedfc8e3 -- android/` (committed changes only)

Requirements:
- tasks/and-search.md acceptance criteria 1-5
- SPEC.md §4.4 (global search)
- api/openapi.yaml `GET /api/v1/search`, `SearchResponse`, `SearchGroupResponse`, `SearchItemResponse`
- docs/ARCHITECTURE.md and AGENTS.md project notes (Android conventions, strings in EN/DE, lint)
- Server item resolvers (deep link shapes) for the security check of opened URLs

Git range:
- Branch: main
- HEAD: 862308208d0cd8070905a48a9eab8c06aedfc8e3
- Base/start: 05a5084d45a8e8b87687455df17dbdbf7c04b9ee
- Commits reviewed: 3 (e580f21c7, afd0b11ba, 862308208)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Checks performed

- Criterion 1: `CockpitShellFrame` shows the search action for every shell destination (`current != null`) next to the capture action, which is kept (task note on and-capture-button). The search route debounces typing (300 ms, `SearchViewModel.setQuery`), uses `GET /api/v1/search` through the generated `SearchApi`, and offers the nine filter chips. "Files" maps to `FILE` and `FOLDER`. All type names match the OpenAPI enum. `q` is trimmed and cut to 200 characters (`maxLength: 200`); a blank `q` never reaches the server (`minLength: 1`); `limit = 50` is within 1..50.
- Criterion 2: rows show the type icon (with a content description), the title and the optional subtitle. Notes, emails and contacts open their Android views; other types resolve the server's relative deep link below the configured server URL (normalized to end in `/`, so path prefixes are kept) and open in the browser, with a toast when no browser exists; when there is no link or no server, the list screen for that type opens.
- Criterion 3: loading bar, "Nothing found" (only for the key the answer belongs to), offline and failure states with retry, and IME `Search` action that submits and hides the keyboard.
- Criterion 4: `strings_search.xml` exists in `values` and `values-de` with the same 34 keys; a test asserts that every German string is present and not blank.
- Async correctness: one `searchJob` is cancelled for every new search, so an older answer (success or failure) cannot replace a newer one, and clearing the text while a search runs resets loading. Tests cover all three cases (`SearchViewModelTest` "an older search answering last...", "an older search failing last...", "clearing the text while a search runs...").
- Error mapping: 5xx is treated as offline, other non-2xx as failed, `IOException` as offline, unreadable or malformed bodies as failed. This follows the existing convention of the Android repositories (`SERVER_ERROR = 500` in Calendar, Contacts, Files, Notes, Mail, ...).
- Security: deep links come only from server-side item resolvers that build fixed relative paths (`/tasks/{id}`, `/notes/{id}`, ...). `HttpUrl.resolve` only produces http(s) URLs, and the base is the user's validated server URL. Navigation route arguments are server enum values and UUIDs; a malformed id is ignored. No secrets or item contents are logged.
- Tests: 72 focused tests (repository, view model, models, Compose screen, Hilt-backed route with fake API, shell integration) cover the criteria, including the German UI. Each criterion has a matching test. The test report states that `ktlintCheck` and `lintDebug` pass. I did not re-run them because this review is read-only.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Choice of which types open in the Android app is not recorded as an assumption**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/search/SearchModels.kt:81`
    - Category: Correctness
    - Problem: Criterion 2 says "opens the item in the matching Android screen where one exists". `SearchItemTypes.inApp` limits that to `NOTE`, `EMAIL` and `CONTACT`. Projects (`ProjectsScreen` has a project detail view) and events (`calendar/edit/EventEditorScreen`) do have Android detail UIs, but neither can be opened by id from outside its screen today. Tasks, projects and events therefore open in the browser. This is a reasonable reading of the criterion, but `python tasks.py assumptions` lists no assumption for and-search.
    - Why it matters: a human reviewing the task cannot see that projects and events deliberately go to the web app and not to their Android screens. This can be revisited once those screens accept an id.
    - Suggested fix: record it with `python tasks.py assume and-search "..."` (parent worker). No code change is needed.

## Review conclusion

No material issues found.
