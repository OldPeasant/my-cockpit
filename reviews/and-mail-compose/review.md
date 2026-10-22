# Code Review

Date: 2026-10-22

Task: and-mail-compose - Android mail compose

Scope: committed task changes `git diff c0a1a25bfeb161b34491d3e9c803a8899db0d984..f797bd11fa10b746128ded76ddf7134c7600ae20 -- android` (commits 14f06956, f4142c82, 5c578b7c, f797bd11). The focus is on the fix commit f797bd11 (new `ComposeSession.kt`, route session handling, repository changes) and on whether CR-1..CR-7 of the previous review are resolved. The untracked test-writer file `MailComposeSaveSendStatesTest.kt` was ignored.

Requirements:
- tasks/and-mail-compose.md acceptance criteria and recorded assumptions
- previous review findings CR-1..CR-7 and reviews/and-mail-compose/fixes.md
- server contract: server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt, DraftController.kt
- AGENTS.md "Project notes"

Git range:
- Branch: main
- HEAD: f797bd11fa10b746128ded76ddf7134c7600ae20
- Base/start: c0a1a25bfeb161b34491d3e9c803a8899db0d984
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## Verification of the previous findings

- CR-1 (reopening a compose with the same mode closes it): resolved. `MailComposeRoute` keeps the session id that `start` returned and closes only on the state of that session (`MailComposeScreen.kt:82-94`). The Robolectric test `a compose opened again with the same mode after sending stays open` covers this.
- CR-2 (work of a closed compose writes into the next one): resolved. Each `start` creates a new `ComposeSession`, and `publish` only shows the current one (`MailComposeViewModel.kt:116-123`). Each session's `stored`, edit counters and mutex belong to that session. A late reply is discarded in `prepared` (`ComposeSession.kt:87-91`). Tests in `MailComposeReviewFixesTest` and `MailComposeViewModelConcurrencyTest` cover this.
- CR-3 (edits during an autosave are not autosaved): resolved. The autosave job clears its handle before `store()` (`ComposeSession.kt:121-127`).
- CR-4 (a removed attachment comes back): resolved. The upload now saves first when dirty, and `withoutRemoved` filters write results by name, size and inline flag. Tests cover both sequences.
- CR-5 (cancelling a running autosave): resolved. `cancelAutosave` only cancels the delay. Discard handles 412 and 404.
- CR-6 (wrong reasons): resolved. The repository uses the `unreadable` flag, and send maps `Invalid` with recipients to `TOO_LARGE`.
- CR-7 (no recovery for `Conflict` on send): resolved functionally. The send refetches the draft, overwrites it and sends again once. See CR-1 below for a new problem in the attachment mapping this path uses.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Overwrite after 412 matches kept attachments by positional part id, and the new send-conflict path can now send the wrong attachments**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt:189-199`, `:303-316`
    - Category: Correctness
    - Problem: `overwrite` filters `content.keep` (part ids of the stale stored version, taken from the screen) against the part ids of the freshly fetched version, then sends them as `keep` for that version. The server's part ids are MIME part paths ("2", "3", ...) that change whenever a version is rebuilt (`DraftService.update` / `withAttachments` look them up with `MimeContent.findPart` on the current version). The fix report itself relies on this: `withoutRemoved` matches by name and size "because part ids change between versions". So a part id from the stale version can name a different attachment in the fresh one. Example: the stale version has [a="2", b="3"] and the user removed a, so `keep=["3"]`. Meanwhile the web client removed a and added c, so the fresh version is [b="2", c="3"]. The overwrite keeps c and drops b. Before f797bd11 this only affected the 412 save path, where the user then sees the server's list. Since CR-7, `sendStored` runs `overwrite` and sends that result at once, without showing it, so a wrong attachment can go out irreversibly.
    - Why it matters: In a multi-client conflict, a sent mail can contain an attachment the user never chose, or lose one they kept. Sending cannot be undone.
    - Suggested fix: In `overwrite`, map the screen's attachments to the fresh version by name, size and inline flag (the same rule as `withoutRemoved`) instead of by part id. Alternatively, on a send `Conflict`, do not send at once: apply the fresh version to the screen and return to EDITING with a problem, so the user confirms. Add a test where the fresh version renumbers its parts.

## P3 Low

- [ ] **CR-2 | Leaving does not lock the screen: a send or edit during the leave's save is overridden by `CLOSED`**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt:352-373`
    - Category: Correctness
    - Problem: `leave()` stays in phase EDITING while its `store()` runs, then sets `phase = CLOSED` without checking what happened in the meantime. If the user taps Send during that save, `send()` sets SENDING. The leave coroutine then overwrites it with CLOSED, and the route closes while the send is still running. If that send then fails (`EDITING` + problem), no screen shows the failure. Text typed during the leave's save also ends up dirty in a CLOSED session. The autosave skips it (`value.editable` is false), so those edits are never saved.
    - Why it matters: On a slow network the screen stays interactive for seconds after back is pressed. A failed send can go unnoticed (the draft stays in Drafts), and late edits are lost.
    - Suggested fix: While leaving, block edits and send (for example a LEAVING phase, or `editable == false` while the leave runs). Alternatively, after `store()` returns, close only if the phase is still EDITING and nothing is dirty, and otherwise keep the screen open.

- [ ] **CR-3 | A fast double tap on Retry while starting a reply creates two reply drafts**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt:50-52`, `:77-79`
    - Category: Correctness
    - Problem: `retry()` checks `stored == null && !value.ended` and calls `load()`. `load()` clears the problem only inside the launched coroutine, so two taps before the next recomposition both pass the check and both call `repository.start(mode)`. Each call creates a server-side reply draft. The second `prepared` overwrites `stored`, and the first draft remains in the Drafts folder as an orphan.
    - Why it matters: A stray duplicate draft in the user's Drafts folder.
    - Suggested fix: Keep an in-flight flag (or the load `Job`) in the session, and make `retry()` a no-op while a load runs. Alternatively, set `phase`/`problem` synchronously before launching.

## Notes (not findings)

- The CR-6 mapping (`SendOutcome.Invalid` with recipients to `TOO_LARGE`) matches the server: `DraftService.send` returns 400 only for a missing recipient or a draft that is too large.
- `discard()` sets `discarded = true` after the mutex is released. A queued upload that resumes in between can try to attach to the deleted draft. The server refuses that (404/412), so the result is at most an `ATTACH_FAILED` on a closed session. This is not reported as a finding.
- Evidence for criteria 1 and 2 is the parent worker's job (`tasks.py verify`). This review did not run tests or `tasks.py`.

## Review conclusion

Material issues found; fix before completing the task.

CR-1..CR-7 of the previous review are resolved. One medium finding remains (CR-1: wrong attachments on a send after a 412 conflict, introduced with the CR-7 fix), plus two low findings.
