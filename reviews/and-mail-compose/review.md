# Code Review

Date: 2026-10-22

Task: and-mail-compose - Android mail compose

Scope: committed changes of the task, `git diff 55951a9a..d4df0c42 -- android` (commits 44e0dc39, d4df0c42); uncommitted test-writer changes under android/app/src/test were ignored.

Requirements:
- tasks/and-mail-compose.md acceptance criteria and recorded assumptions
- SPEC.md §7.3/§7.4, §9.2 (as summarised by the parent worker)
- server contract: server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftController.kt, DraftService.kt
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions

Git range:
- Branch: main
- HEAD: d4df0c42148374f38e0baafe220d5f292ae23a74
- Base/start: 55951a9a0833272753b32ca93d80595f8bca3cd2
- Commits reviewed: 2

Summary: 0 P0, 2 P1, 3 P2, 2 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Opening a compose with the same mode a second time closes it at once**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeScreen.kt:80`, `:89-90`
    - Category: Correctness
    - Problem: The view model is shared across composes (hiltViewModel scoped to the Mail screen), and its state keeps the previous compose's `mode` with phase `SENT`/`CLOSED` after that compose ends. When `MailComposeRoute` enters composition again with an equal mode (for example compose, send, then compose again from the same folder: `ComposeMode.New(sameAccountId)`, or reply to the same mail twice), `collectAsStateWithLifecycle` starts with that old state, so `closed` is `true` in the first composition. Both `LaunchedEffect`s are launched in that composition. The first one calls `start(mode)`, which resets the state. The second one runs its lambda, which captured `closed == true`, and calls `onClose()`. MailRoute sets `composing = null`, so the screen disappears right after it opens. The guard comment at line 88 only handles a *different* mode.
    - Why it matters: Compose (AC1) breaks for the most common repeat flow: writing a second mail from the same folder after sending or leaving the first one. No route-level test covers it, because the screen tests use `MailComposeContent` and the view model tests do not use the route.
    - Suggested fix: Do not derive "closed" from state that existed before this route instance started. Options: give each compose its own view model (`hiltViewModel(key = mode.encode() + a per-open nonce)`), add a session id or generation to the UI state and only close when it matches the one this route started, or have `start()`/the view model expose a one-shot close event. Add a Robolectric test that opens, closes and reopens the route with the same mode.

- [ ] **CR-2 | Work left over from a closed compose writes into the next compose and can overwrite the earlier draft**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModel.kt:123-134`, `:333-358`, `:166-189`, `:419-428`
    - Category: Correctness
    - Problem: `start()` resets `stored`, `edits` and the state, but it does not cancel or fence coroutines from the previous compose. `leave()` does not wait for uploads. When the content is not dirty, it sets `CLOSED` immediately. Here is one path: attach a large file to draft A, press back while the upload is running (screen closes), then tap Compose. `start(New)` sets `stored = null`. When A's upload returns, `upload()` sets `stored = A` (new ETag) and `state.attachments = A's attachments` in the new compose. The next autosave of the new mail then runs `update(A.id, ...)` with the new content and `keep` = A's attachments. Draft A's content is replaced, so the earlier draft is lost, and the new mail carries A's files. The same applies to the remaining files of the upload loop. A file uploaded after `start()` reset `stored` goes through `store()` and creates a draft from the new compose's content. It also applies to a reply/forward `load()` that returns after `leave()` in `STARTING`. That path sets `stored`, `phase = EDITING` and the reply fields on whatever compose is current, and it leaves the server-created reply draft orphaned when nothing else is open.
    - Why it matters: Data loss: a saved draft is overwritten. A new mail can also silently get the wrong recipients, subject or attachments.
    - Suggested fix: Tie every launched job to a compose session: capture a generation or session id in `load`/`attach`/`send`/`discard`/`leave` and drop results whose session is no longer current, or cancel a per-session `Job` in `start()`. Also consider blocking `leave()` while uploads run, or a per-compose view model (see CR-1). If a reply draft created after an early leave arrives late, discard it. Add a view model test: attach, leave mid-upload, start a new compose, let the upload finish, and assert the new compose has no `stored`/attachments from the old one.

## P2 Medium

- [ ] **CR-3 | Edits made while an autosave is running are not autosaved**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModel.kt:229-236`, `:273`
    - Category: Correctness
    - Problem: `scheduleAutosave()` returns while `autosave?.isActive == true`. When the autosave job itself runs `store()`, two calls are no-ops because that job is still active: the `if (dirty) scheduleAutosave()` at line 273, and every `edit()` made during the request. After the job finishes, no autosave is pending, so text typed during an autosave request stays `UNSAVED` until the next keystroke, a manual save, or leave.
    - Why it matters: SPEC §7.3 requires autosave every 10 s. If the user stops typing during a save and the process is killed, those last edits are lost, while the status line keeps showing "unsaved" with no save coming.
    - Suggested fix: Reschedule from the autosave coroutine after `store()` returns (for example, loop while `dirty`), or clear `autosave` before calling `store()` so `scheduleAutosave()` can start a new job. Add a test that edits during a slow autosave and asserts that a second save follows within 10 s.

- [ ] **CR-4 | Removing an attachment is undone by a save or upload that is already running**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModel.kt:267`, `:341-349`
    - Category: Correctness
    - Problem: After a successful save, `state.attachments` is replaced with the server's list even when the user removed a chip while the save was running (`dirty` is still true). The removed attachment comes back, and the next save sends it in `keep`. `upload()` does not save first when a draft exists (`stored == null && !store()`), so it uploads into the stored version, which still has the attachment the user removed locally. `attachments = result.value.attachments` then brings it back. The comment at line 348 says the removed ones "stay removed", but the code does the opposite. No test covers either sequence. The committed attach test removes the attachment only after the uploads finish.
    - Why it matters: An attachment the user removed can be sent anyway.
    - Suggested fix: Save first when `dirty` before uploading (as the KDoc of `attach` says), and/or remove the partIds the user dropped since the request started when you apply a server result. PartIds are per version, so map them by name or index, or save the removal before applying. Add tests for "remove during save" and "remove then attach".

- [ ] **CR-5 | Cancelling a running autosave can leave an orphan or stale draft**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModel.kt:369`, `:398`, `:429`
    - Category: Correctness
    - Problem: `send()`, `discard()` and `leave()` call `autosave?.cancel()`, which also cancels a `store()` whose HTTP request is in flight, not just the 10 s delay. The server may already have committed that write. Three outcomes are possible. (a) A first save (`create`) commits, but the client keeps `stored == null`. Then `send`/`leave` create a second draft, and the first stays in Drafts as an orphan; `discard` closes and leaves the committed draft on the server. (b) An `update` commits a new UID. `discard()` then sends the old ETag, gets 412 and shows `DISCARD_FAILED` every time, because discard does not refetch the ETag like `update()` does. (c) `send()` maps a 412 to `SEND_FAILED`, and a retry sends the same stale ETag again, because `store()` sees no dirty edits.
    - Why it matters: Drafts end up duplicated in the Drafts folder, or a discard or send cannot succeed without editing the text.
    - Suggested fix: Cancel only the pending delay, not a running write: send, discard and leave already wait on `writes`, so let a running `store()` finish. Also handle 412 in discard and send by refetching the ETag, as `update()` does.

## P3 Low

- [ ] **CR-6 | An unreadable picked file and a too-large send are reported with the wrong reason**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeRepository.kt:199`, `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModel.kt:383`
    - Category: Quality
    - Problem: `PickedFileBody.writeTo` throws `IOException` when the file can no longer be opened, and `mailCall` maps every `IOException` to `Offline`, so the user sees "Can't reach the server". On send, a 400 always becomes `NO_RECIPIENT`. The client already blocks an empty recipient list, so the remaining 400 case is a draft that is too large (server field `attachments`), and that one is shown as "Add at least one recipient".
    - Why it matters: Wrong guidance; the user retries something that cannot work.
    - Suggested fix: Use a dedicated exception (or check `open()` before the call) mapped to `ATTACH_FAILED`. Map send 400 to `TOO_LARGE` when recipients are present, or read the Problem Details field.

- [ ] **CR-7 | `SendOutcome.Conflict` has no recovery path**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModel.kt:386`
    - Category: Correctness
    - Problem: A 412 on send shows `SEND_FAILED`. The next tap on send skips the save (not dirty) and sends the same stale ETag, so the user cannot send until they edit something. This is the user-facing part of CR-5 (c); it is listed separately because it can also happen without a cancelled autosave, for example when another client touches the draft.
    - Why it matters: A send that fails every time with a generic message.
    - Suggested fix: On `Conflict`, refetch the draft's ETag (and re-store the screen content, as the 412 save assumption says), then send again or let the user retry.

## Notes (not findings)

- `markupToHtml` escapes `& < > "` before it adds only `<p>`, `<br>`, `<ul>/<li>`, `<strong>` and `<em>`. Typed text cannot inject markup. The quoted HTML is the server's sanitized output, sent back verbatim; `cid:` references are kept by the server's sanitizer, and inline parts are kept through `keep`. No XSS issue found.
- Multipart streaming: `PickedFileBody` opens the stream inside `writeTo` (repeatable on OkHttp retries) with an unknown length (chunked), and no body-logging interceptor exists. The file is not buffered on the client. The server reads `file.bytes`, which is outside this task.
- ETag chaining under `writes`: create, update, attach, send and discard each use the ETag the previous write returned, and the 412 path refetches the draft and filters `keep` against the fresh partIds, as the recorded assumption says.
- `tasks.py verify and-mail-compose` shows no evidence recorded yet for criteria 1 and 2. The parent worker must still record it, and criterion 2 needs the named `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` run.

## Review conclusion

Material issues found; fix before completing the task.
