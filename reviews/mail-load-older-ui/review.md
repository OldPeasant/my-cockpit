# Code Review

Date: 2026-11-08

Task: mail-load-older-ui - Load mail older than the sync window (web + Android)

Scope: task commits aff8220c4 (web) and 1094d9ff0 (Android) on top of base ff797183e (`git diff ff797183e 1094d9ff0 -- web android`). The shell tool was denied in this review session, so the diff could not be printed. Instead, the reviewer read the current versions of the files the task touches in full:
- web: `mail-list-store.ts`, `mail-list-page.ts`, `mail-list-page.html`, `mail.service.ts`;
- Android: `MailListViewModel.kt`, `MailRepository.kt`, `MailApi.kt`, `MailScreen.kt` (list part, lines 1-720);
- `strings_mail.xml` (en and de).

The regenerated `web/src/locale/messages.xlf` was treated as expected churn and not reviewed.

Requirements:
- `tasks/mail-load-older-ui.md`: the acceptance criteria and the ASSUMPTION about offering the button at the end of every folder list and re-reading the folder's first (shown + loaded) messages.
- SPEC.md §7.2 (sync window, load-older endpoint), as summarised in the task goal.
- `api/openapi.yaml` `POST /api/v1/mail/folders/{id}/load-older`, as consumed by the generated `loadOlderMail` (web) and the hand-written `MailApi.loadOlder` / `MailLoadOlderDto(loaded, hasMore)` (Android).
- `AGENTS.md` project notes: i18n in en and de, feature folders and `touches`, lint.
- The `MailListStore` class contract (a refetch must not overwrite a running action's optimistic state).

Git range:
- Branch: main
- HEAD: 1094d9ff0
- Base/start: ff797183e61538afeb87cb4dd3f827e744ecca01
- Commits reviewed: 2 (aff8220c4, 1094d9ff0)

Summary: 0 P0, 1 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Web: "loading older" never ends when the list is replaced while the request runs**
    - File: `web/src/app/features/mail/list/mail-list-store.ts:268` and `:288-289`, together with `load()` at `:146-162` and `restore()` at `:228-242`
    - Category: Correctness
    - Problem: `loadOlder()` sets `loadingOlder` to true. It resets it only `if (seq === this.loadSeq)`, in the `finally` block. Several actions increment `loadSeq` while the request runs:
        - switching to another folder (the page is kept across folders through `mailListMatcher`);
        - toggling Messages/Conversations;
        - starting a search;
        - "Try again".

      In each case `load()` runs, increments `loadSeq` and resets `loadError`, `moreError` and `olderError`, but it never resets `loadingOlder`. The flag therefore stays `true` for good. In the newly shown folder, `mail-list-page.html:291-300` keeps showing "Loading older messages from the server…" with the button disabled. `loadOlder()` returns early on `this.loadingOlder()`, so the feature is dead until the page is left. (`loadMore()` avoids this problem: it resets `loadingMore` unconditionally.)
    - Why it matters: This breaks AC1 ("shows the progress, then the newly cached messages") and AC2 (errors non-blocking, retry possible). A load-older request goes to IMAP and can be slow, so switching folders while it runs is a normal action. The result is a stuck progress state with no way to recover.
    - Suggested fix: Reset `loadingOlder` to false in `load()` and `restore()`, as the Android `load()` does. Alternatively, reset it unconditionally in `finally`, but only if no newer `loadOlder` call has started (use a dedicated sequence number). Add a store test: start `loadOlder`, call `load()` for another folder, then resolve or reject the pending request, and check that `loadingOlder()` is false and that `loadOlder()` works again.

## P2 Medium

- [ ] **CR-2 | Web: loadOlder replaces the list without the action/refetch guards the store promises**
    - File: `web/src/app/features/mail/list/mail-list-store.ts:275-283`
    - Category: Correctness
    - Problem: After the POST, `loadOlder()` re-reads the folder (`fetchUpTo`) and calls `this.items.set(page.items)`. It does not check `this.running` or whether `mutationSeq` changed while the read was in flight. `refresh()` does both (`:343-357`). The class documentation says: "A refetch that would overwrite a running action's optimistic state waits until the action is done." Suppose an archive, delete, move or flag action starts while load-older runs. Its optimistic change is then overwritten by a server read that may come from before the action was committed: an archived mail reappears, or a star flips back. Nothing reapplies the change, because `apply()` only rolls back on failure and queues a refresh only on failure. The display is corrected only later, if a `MAIL_FOLDER` event triggers a refresh. The Android view model does not have this problem: `perform()` reapplies `changed(done, action)` on top of whatever list is current.
    - Why it matters: Rows the user just acted on come back for a while, and the store's own documented consistency rule is broken.
    - Suggested fix: Do as `refresh()` does. Record `mutationSeq` before `fetchUpTo`. If an action is running or the sequence changed, either re-run the fetch once the actions settle, or apply the page and then set `refreshQueued = true` and call `runQueuedRefresh()`. Add a test for an archive started while `loadOlder` is pending.

## P3 Low

- [ ] **CR-3 | Web: "nothing older" is shown as an error with a redundant prefix**
    - File: `web/src/app/features/mail/list/mail-list-page.html:286-289` and `mail-list-store.ts:270-272`
    - Category: Quality
    - Problem: The "nothing older" case is stored in `olderError` and rendered as `<p class="error">Nothing older loaded: There are no older messages on the server.</p>`. This reads as a failure and repeats itself. Android shows the same case as a neutral message (`onSurfaceVariant`) and colours only real failures as errors.
    - Why it matters: A normal outcome looks like a failure, and the two clients behave differently on the same event.
    - Suggested fix: Keep "nothing older" separate from failures, for example with an `olderNotice` signal or a kind field. Render it without the error class and without the "Nothing older loaded:" prefix.

## Notes (not findings)

- Android (`MailListViewModel.loadOlder`):
    - `load()` cancels `loadJob` and resets `loadingOlder` and `olderMessage`, so a refresh, a folder switch or a reload after undo ends the progress.
    - A mailbox change while the coroutine runs is also guarded (`s.mailbox != folder`).
    - Failures map to OFFLINE, FAILED or NOTHING_OLDER, shown inline without blocking, and the button stays available for a retry.
    - The selection is pruned to the re-read list.
    - `canLoadOlder` requires a folder, no search, a loaded list, no cursor and no failed page load, which matches the ASSUMPTION. The button is also offered for an empty folder.
- Contract:
    - Both clients send `POST /api/v1/mail/folders/{id}/load-older?limit=100` and read `loaded` (with `hasMore` defaulting to false).
    - Both re-read `shown + loaded` messages, in pages capped at 200 (`MAIL_MAX_PAGE_SIZE` / `MAX_PAGE_SIZE`).
    - Calls are silent on the web (`SILENT_ERRORS`), and errors are shown through `ApiError.userMessage`.
- Strings:
    - Android: the five new strings exist in both `values/` and `values-de/`.
    - Web: the new ids are `mail.loadOlderFromServer`, `mail.loadingOlder`, `mail.olderFailed` and `mail.list.nothingOlder`. Their German targets in `web/src/locale/messages.de.xlf` could not be confirmed, because the file could not be searched without the shell. The parent should confirm this with the project's i18n check (`npm run check`).
- Limitations: the shell was denied in this session. The reviewer could not run `git diff`/`git log`, the tests, `sh scripts/check.sh` or `python tasks.py verify mail-load-older-ui`. The findings above come from reading the current code. Test coverage for the new behaviour, and the evidence for AC4, are left to the concurrent test-writer and the parent worker. CR-1 needs a regression test.

## Review conclusion

Material issues found; fix before completing the task.
