# Code Review

Date: 2026-10-21

Task: and-mail-list-read - Android mail list and reading

Scope: committed changes of the task under `android/` (`git diff e9618713..0c6ea8bb -- android`), commits d5588471 and 0c6ea8bb

Requirements:
- tasks/and-mail-list-read.md acceptance criteria and recorded assumptions (hand-written DTOs; WebView blocks all network; swipe right = archive, left = delete; opening marks read)
- SPEC.md §3 "HTML email is untrusted", §7.3, §7.4, §9.2 item 5
- docs/ARCHITECTURE.md, AGENTS.md project notes
- Server contract: server/.../mail/api/MailMessageController.kt, MailActionController.kt, mail/gtd/MailGtdController.kt

Git range:
- Branch: main
- HEAD: 0c6ea8bb8a22f1f3baab5c074a19d005d19b641d
- Base/start: e96187139740b222870e0bc93a2498b29167e4e3
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 3 P2, 3 P3

Verified as correct: the DTO field names and nullability match the server responses (`MailMessageSummaryResponse`, `MailMessageResponse`, `MailBodyResponse`, `MailAttachmentResponse`, `MailActionResponse`, `MailGtdItemResponse`, folder and account responses). The Retrofit JSON instance uses `ignoreUnknownKeys = true`. The endpoint paths and methods match the controllers, including `POST /messages/{id}/to-task|to-waiting` and `POST /messages/actions` with READ/ARCHIVE/DELETE. The WebView settings disable JavaScript, file access, content access, network, DOM storage and multiple windows. The page around the mail adds a CSP (`default-src 'none'`). `shouldOverrideUrlLoading` always returns true and hands only http/https/mailto links to other apps. No logging of mail content was added. The Hilt or placeholder switch in `MailScreen` follows the same pattern as the other feature screens.

Validation: I was not allowed to run Gradle in this review session (permission denied). The existing results in `android/app/build/test-results/testDebugUnitTest` (2026-10-21T16:16Z) show MailListScreenTest 8/8, MailListViewModelTest 6/6 and MailWebViewTest 3/3 passing. The parent must re-run `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' ktlintCheck lintDebug` as evidence.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A failed swipe puts the mail back into a different mailbox, or a reload brings back an archived mail**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailListViewModel.kt:101`
    - Category: Correctness
    - Problem: `act()` remembers `index` and `mail` and launches the server call. Nothing ties that call to the mailbox or the load that was showing. If the user switches folders while the call runs (`select()` at :69 or `load()` at :148) and the server refuses, the `!done` branch at :112 inserts the old mailbox's mail into the newly selected folder's list at the old index, and shows "could not be archived/deleted" there. The reverse can also happen. A `load()` that started before the swipe finished (for example the refresh from `LifecycleResumeEffect` on resume) replaces `messages` with a server page that may still contain the mail. A successful `act` then never removes it again, so the archived or deleted mail stays visible until the next reload.
    - Why it matters: The list shows mail that does not belong to the selected folder, or mail that is no longer there. Opening such a row then gives "no longer on the mail server". This is the overlapping write / out-of-order case: `load()` and `act()` write the same `messages` state with no shared request identity.
    - Suggested fix: Capture `state.value.mailbox` (or a load generation counter that `load()` bumps) in `act()`. Re-insert only when the mailbox or generation is unchanged. On success, also remove the id from the current list (`filterNot`) so that a reload that overtook the call cannot bring the mail back. Add a view-model test for "swipe, select another folder, server refuses".

- [ ] **CR-2 | Every opened mail leaves a view model (with its full HTML body) on the Mail tab's back-stack entry**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadScreen.kt:61`, `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadViewModel.kt:62`
    - Category: Correctness
    - Problem: `hiltViewModel(key = "mail-read:$id")` creates one `MailReadViewModel` per message id in the `ViewModelStore` of the Mail `NavBackStackEntry` (CockpitShell's NavHost). Nothing clears it when the read view closes. The shell's `saveState = true` also keeps that store across tab switches. So every mail opened in a session stays in memory with its sanitized HTML. When the same mail is opened again, `open()` returns early (`if (id == messageId) return`), so the user sees the content from the first opening. It is not reloaded or marked read again, even after the mail changed on the server.
    - Why it matters: Memory grows without bound in normal use (newsletters often carry 100 KB+ of HTML), and a reopened mail can show stale content.
    - Suggested fix: Scope the read view model to the read view so it is cleared on close. Options: a nested `ViewModelStoreOwner` that is cleared when `open` becomes null, or a single unkeyed read view model whose `open(id)` resets its state for a new id. Alternatively, reload in `open()` when the mail is opened again.

- [ ] **CR-3 | Read view model, repository mapping and read screen have no tests**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadViewModel.kt:1`, `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailReadRepository.kt:1`, `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailRepository.kt:1`
    - Category: Tests
    - Problem: Criterion 1 includes to-task/to-waiting, and the read view also handles mark-as-read, 404 → GONE, and archive/delete from the read view closing it. None of this is tested in the committed code. The only committed read test is `MailWebViewTest`. There are also no tests for `mailCall` (IOException/5xx → Offline, 4xx → Failed, malformed UUID → Failed), the DTO mappers (`toSummary`, `toMessage` with null subject/date/body, inline attachments filtered), `folderOrder`/`depth`, or `MailReadContent` (HTML vs. text body, error/retry, notice).
    - Why it matters: These are the error and integration paths most likely to break against the real server, and the to-task/to-waiting part of criterion 1 has no automated evidence.
    - Suggested fix: Add `MailReadViewModelTest` (load ok marks read; 404 → GONE; Offline → retry; gtd success/failure notices; busy prevents a double submit; act success sets `removed`). Add `MailRepositoryTest` against a MockWebServer or a mocked `MailApi` for the mailCall mapping and DTO parsing with nulls. Add a small Compose test for `MailReadContent`. (A concurrent test-writer may already be adding these; review them once committed.)

## P3 Low

- [ ] **CR-4 | Infinite scroll can stop: the load-more trigger fires only when "near end" changes**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt:205`
    - Category: Correctness
    - Problem: `snapshotFlow { nearEnd }.distinctUntilChanged()` calls `loadMore()` only on a false → true change. If `loadMore()` is a no-op at that moment (`loading` is true, for example during the refresh on resume) or the page load fails, `nearEnd` stays true. No further page loads until the user scrolls more than 10 rows back up and down again. `hasMore` changes do not re-trigger either.
    - Why it matters: The user at the end of the list sees no more mail and gets no feedback.
    - Suggested fix: Include `hasMore`, `loading` and `loadingMore` in the snapshot (for example emit `nearEnd && hasMore && !loading && !loadingMore`), or call `loadMore()` again after a successful load while still near the end.

- [ ] **CR-5 | Every resume reloads only the first page and drops the pages already loaded**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailScreen.kt:103`
    - Category: Correctness
    - Problem: `LifecycleResumeEffect` calls `refresh()`, and `load()` replaces `messages` with page 1 only. The effect stays active while a mail is open. Tapping a link in the read view opens the browser (pause/resume), so the list behind it is reduced to the first 50 mails. Going back then does not return "to the list where it was" (KDoc at :93) when the user had paged further.
    - Why it matters: The user loses their scroll position in long folders after any app switch.
    - Suggested fix: On resume, merge the new first page into the loaded list (keep the later pages and the cursor), or skip the refresh while a mail is open.

- [ ] **CR-6 | Links from mail are opened in other apps without a user gesture check**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/read/MailBodyView.kt:45`
    - Category: Security
    - Problem: `MailWebViewClient` passes every http/https/mailto navigation to `onLink`, including navigations the page starts itself (for example `<meta http-equiv="refresh">`, or a sub-frame navigation). The CSP does not restrict navigation. Today the server sanitizer is expected to strip such elements, so this is defense in depth only.
    - Why it matters: If the sanitizer ever lets an auto-navigation through, opening a mail would launch the browser to an attacker URL without a tap, which is a tracking or phishing vector. SPEC §3 asks for a second wall around untrusted HTML.
    - Suggested fix: Open externally only when `request.hasGesture() && request.isForMainFrame`, and drop everything else. Add the case to `MailWebViewTest`.

## Review conclusion

Material issues found; fix before completing the task.
