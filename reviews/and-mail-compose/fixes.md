# Fix Report

Date: 2026-10-22

Task: and-mail-compose - Android mail compose

Scope: current task; findings CR-1 .. CR-7 of `reviews/and-mail-compose/review.md` (all accepted as in scope by the parent worker), plus the 3 test-writer tests that failed because of CR-2/CR-3.

## Fixed findings

### CR-1 — Opening a compose with the same mode a second time closes it at once

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModel.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeScreen.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRouteTest.kt`

Problem:
The route decided "closed" from the shared view model's state, which still held the previous compose (same mode, phase SENT/CLOSED) in the first composition, so the route called `onClose()` right after it opened.

Fix:
Every compose now has a session id (`MailComposeUiState.session`), and `start(mode)` returns it. The route remembers the id that its own `start` returned. It shows and closes only on the state of that session. Before `start` has answered, it accepts only an open compose of the same mode, which `start` keeps (configuration change). A closed compose of an earlier route can no longer close the new one.

Validation:
- New Robolectric test `a compose opened again with the same mode after sending stays open` (route shown/hidden like MailScreen: send, close, reopen with the same `ComposeMode.New`). It passes. With the old close condition temporarily restored, it fails (8 tests, 1 failed).
- `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` — Pass

### CR-2 — Work left over from a closed compose writes into the next compose

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt` (new)
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModel.kt`

Problem:
`start()` reset shared fields (`stored`, `edits`, state), but coroutines of the previous compose kept running against them. A late upload or reply result then wrote into the new compose and could overwrite the earlier draft.

Fix:
The per-compose state (stored draft, edit counters, autosave job, write mutex, UI state) moved into a `ComposeSession` object, one per `start`. Every coroutine works on its own session. A session publishes its state to the view model's `uiState` only while it is the current one. Work of a closed compose (a save when leaving, an upload) finishes with that compose's own draft and never reaches the next compose. A reply/forward that the server prepares after the user left during STARTING is discarded once it arrives, following the recorded assumption that an untouched reply is discarded. A late accounts result of a left new mail is ignored. After a successful discard, queued uploads are dropped. The view model's public API did not change, except that `start` now returns the session id.

Validation:
- Test-writer tests `a reply left while the server prepares it is discarded once it exists` and `a reply answering after the user left and opened a new mail does not fill the new mail` fail on the old code and pass now (unchanged).
- New test `an upload of a left compose ends in its own draft, not in the next compose` (the review's scenario: attach, leave mid-upload, start a new compose, let the upload finish; the new compose has no attachments, its save creates a new draft, no `update` of the old one).

### CR-3 — Edits made while an autosave is running are not autosaved

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt`

Problem:
`scheduleAutosave()` returned early while the autosave job was active. That job stayed active during its own `store()`, so edits made during the request scheduled nothing.

Fix:
The autosave job clears its handle (`autosave = null`) once its 10 s delay is over and before it calls `store()`. Edits during that save, and the `if (dirty) scheduleAutosave()` after it, schedule the next autosave.

Validation:
- Test-writer test `an edit during an autosave is autosaved again` fails on the old code and passes now (unchanged).

### CR-4 — Removing an attachment is undone by a save or upload that is already running

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeReviewFixesTest.kt` (new)

Problem:
A successful save or upload replaced the screen's attachments with the server's list, which brought back an attachment the user had removed meanwhile. An upload into an existing draft did not save a pending removal first.

Fix:
1. `upload()` now saves first when the screen is dirty, not only when no draft exists. The upload then builds on what is on screen.
2. When a save or upload result is applied, the attachments removed on screen since the write started are dropped from the server's list (`withoutRemoved`). Part ids change between versions, so a removed attachment is matched by name, size and inline flag, one per removal. The removal stays a dirty edit, so the next save sends `keep` without it.

Validation:
- New tests `an attachment removed while a save runs stays removed and is dropped by the next save` (the server renumbers parts) and `attaching after removing an attachment saves the removal first` (`coVerifyOrder`: update with empty `keep`, then attach with the new version).

### CR-5 — Cancelling a running autosave can leave an orphan or stale draft

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt`

Problem:
`send`/`discard`/`leave` cancelled the autosave job even while its HTTP write was running. The server could commit the write while the client lost the result, which led to a duplicate draft or a stale ETag. Discard did not recover from 412.

Fix:
`cancelAutosave()` cancels only an autosave that is still waiting for its delay: once the save itself runs, the job is no longer referenced (see CR-3), and send/discard/leave wait for it through the session's write mutex. Discard now handles a 412: it refetches the draft and deletes the newest version. A draft that is gone by then (404) counts as discarded. The same discard is used when leaving an untouched reply and for a reply that arrives after leaving.

Validation:
- New tests `leaving while an autosave creates the draft waits for it instead of creating a second one` (one `create`, then one `update` with the later text), `a discard of a draft changed meanwhile deletes the newest version`, `a discard of a draft deleted meanwhile counts as discarded`.

### CR-6 — An unreadable picked file and a too-large send are reported with the wrong reason

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeRepository.kt`
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModelTest.kt`

Problem:
A file that could not be opened surfaced as `IOException`, which became `Offline` ("Can't reach the server"). A send 400 always showed "Add at least one recipient".

Fix:
`PickedFileBody` flags the open failure before it throws the `IOException` that OkHttp requires. `attach()` then returns `Failed(null)`, which the view model shows as `ATTACH_FAILED`. A non-IO exception from `writeTo` would escape OkHttp's call, so the flag is safer than a custom exception. On send, `SendOutcome.Invalid` maps to `TOO_LARGE` when the screen has recipients (always the case, because the client blocks a send without any), and to `NO_RECIPIENT` otherwise. The Problem Details field is not read because `mailCall` (in mail/list, outside this task) drops the body.

Test changed: in `MailComposeViewModelTest` "a failed send returns to editing with the reason, the draft stays", the expected problem for `SendOutcome.Invalid` changed from `NO_RECIPIENT` to `TOO_LARGE`. The test has a recipient on screen, so the old expectation is the wrong guidance that CR-6 describes. The same test also stubs `repository.get` (returning `Failed(null)`), because a `Conflict` now refetches the version (CR-7). Its expectation for `Conflict` (`SEND_FAILED` when that recovery fails) is unchanged.

Validation:
- New repository test `a picked file that can no longer be read fails the upload, it is not reported as offline` (MockWebServer, `Failed(null)`).
- New view model tests `a send refused as invalid with recipients says the mail is too large` and `an upload of a file that cannot be read says the file could not be attached`.

### CR-7 — `SendOutcome.Conflict` has no recovery path

Files changed:
- `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt`

Problem:
A 412 on send showed `SEND_FAILED`. Every retry sent the same stale ETag again, because no save happened while the screen was not dirty.

Fix:
On `Conflict`, the send fetches the newest version. It stores the screen content over that version (same `overwrite` as the 412 save path: phone content wins, `keep` filtered to existing parts), updates the stored version and sends again once. If the refetch fails, the user sees `OFFLINE` or `SEND_FAILED`, and the stale version is not kept as if it were current.

Validation:
- New test `a send of a draft changed meanwhile stores the screen over the newest version and sends that` (get, update with the fresh ETag, send with the version the update returned, phase SENT).

## Not fixed

None.

## Validation summary

Focused tests:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` — Pass: 114 tests, 0 failed, 0 skipped (counted from the JUnit XML results). This includes the 3 test-writer tests that failed before.
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.MailComposeRouteTest'` with the old close condition temporarily restored — 8 tests, 1 failed (the new CR-1 test); the fix was then put back.

Full test suite:
- `sh scripts/check.sh` (server, web and android lint + unit tests) — Pass ("== all checks passed"). On this shared machine the run took over 10 minutes. The web build printed its existing initial bundle budget warning, which is unrelated to this task.

Lint/type-check/build:
- `cd android && ./gradlew ktlintFormat`, then `./gradlew ktlintCheck lintDebug` — BUILD SUCCESSFUL, no warnings or errors reported.

`.taskcheck`:
- Pass (`sh scripts/check.sh`, see above).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
