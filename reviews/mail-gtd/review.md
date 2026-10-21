# Code Review

Date: 2026-10-21

Task: mail-gtd - Mail to GTD integration

Scope: final re-review of the whole task. `git diff 5bfa17c657d5d6cc819cc4120eb145fe9a3880e4..71150acbbbc763a814ca75dc38c5ff5b4b1c3f4d`: production code in `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/`, tests in `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/`, `api/openapi.yaml` and the generated web client under `web/src/app/api/generated/`. Committed changes only. The working tree was clean when the review started.

Requirements:
- tasks/mail-gtd.md acceptance criteria and recorded assumptions (sent/received waiting-for, note body format, no request body / not idempotent, CR-3 deferral)
- SPEC.md §7.3 (GTD integration), §4.2 (API conventions)
- AGENTS.md project notes (module boundaries, Problem Details, logging rules, generated files)
- Previous review (re-review CR-1..CR-3), reviews/mail-gtd/fixes.md "Round 2", reviews/mail-gtd/tests.md
- Existing code: NoteMarkdown, NoteService, LinkService, MailAddress parsing (ImapRemoteMailbox, MailAddress)

Git range:
- Branch: main
- HEAD: 71150acbbbc763a814ca75dc38c5ff5b4b1c3f4d
- Base/start: 5bfa17c657d5d6cc819cc4120eb145fe9a3880e4 (`git merge-base HEAD origin/main`)
- Commits reviewed: 11 (b0ea7069, 4b19a330, d072b79c, f8db6d6f, 23db507e, 0931ece6, d8668bee, e139a5e3, cc5247ec, 61f4b71f, 71150acb). The commits that change production code are b0ea7069, 23db507e and cc5247ec. The OpenAPI and client were regenerated in 4b19a330, and the controller has not changed since then.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Verification of previous findings

- Re-review CR-1 (source indentation becomes Markdown code blocks): fixed. `htmlText` (`MailGtdService.kt:200-257`) keeps `wholeText` only for text nodes that have a `pre` ancestor (`:211-213`). Everywhere else, runs of HTML whitespace collapse to one space (`:215`), and the text is left-trimmed at a line start or after a space (`:208`, `:216`). Table cells only add a separating space when there is none yet (`:245-246`). I traced the indented-table, `<br>`-then-indented-line, whitespace-only-node and `&nbsp;` cases. No line outside `<pre>` starts with a blank. NBSP is not collapsed, which is fine: CommonMark does not count it as indentation. Two tests check this with exact output: `to-note collapses the source indentation of a pretty-printed HTML part but keeps pre text` and `to-note of indented HTML keeps nbsp, ...`.
- Re-review CR-2 (missing block tags): fixed. `details` was added to `BLOCKS`. `center`, `main`, `aside`, `summary` and `caption` were added to `LINES` (`:165-186`). The tests cover `center`, `caption`, `details`/`summary`, `main` and `aside`.
- Re-review CR-3 (duplicated `MailConnectionException` handler): still present. It was deferred by a recorded assumption and is carried over below as CR-2.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Sender and recipient display names in the note header are not neutralized for inline `[[type:id]]` links**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt:138-140`
    - Category: Security
    - Problem: `neutralizeInlineLinks` runs only on the mail text (`:148-152`). The header lines put each address through `full(address)`, which includes `MailAddress.name` unchanged. That name is the sender-controlled `personal` part of the From/To/Cc header (`ImapRemoteMailbox.kt:316`, `MailAddress.kt:27`). A display name such as `"[[task:<uuid>]]" <x@example.test>` (a valid quoted string) produces `**From:** [[task:<uuid>]] <x@example.test>`. That matches `NoteMarkdown.INLINE_LINK` (`NoteMarkdown.kt:19`), so `NoteService` creates a SYNC link from the new note (`NoteService.kt:106`). `LinkService.sync` does not check that the target exists (`LinkService.kt:120-135`), so a dangling link is stored too. The round-1 fix report says the header "holds only addresses and the date", but it holds display names as well. The test `inline link tokens in a mail's text do not link the note to other items` covers only body text.
    - Why it matters: The fix for the earlier CR-2 aims to stop a received mail from adding links to the user's items when it becomes a note. Through the header, an external sender can still add them. The impact is small: a real target needs a UUIDv7 the sender would have to know, and otherwise the link dangles. This is why it is P3.
    - Suggested fix: Run the assembled header through `neutralizeInlineLinks` too, or the whole `body` before truncation. The generated parts contain no `[[`, so this is safe. Extend the inline-link test with a From display name that contains a `[[task:<uuid>]]` token.

- [ ] **CR-2 | `MailConnectionException` handler copied from `MailMessageController` (carried over, deferred)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt:87-96`
    - Category: Quality
    - Problem: The handler is the same as the one in `MailMessageController`, and `MailAccountController` has a third copy. The parent deferred this with a recorded assumption, because the fix lies outside this task's `touches`.
    - Why it matters: The mapping of mail server failures to Problem Details (502/503, `errorCode`) can drift between the mail endpoints.
    - Suggested fix: Add a follow-up task for a shared `@RestControllerAdvice` (or helper) for the mail controllers. No change is needed in this task while it stays deferred.

## Notes (no finding)

- AC1: `to-task`, `to-waiting` and `to-note` return 201 with `Location` and `MailGtdItemResponse`. Each creates its item through `TaskService`/`NoteService` and a MANUAL link through `LinkService`, in one transaction. For a sent mail (Sent role, account address or identity, case-insensitive), waiting-for uses the recipients (To, else Cc, else Bcc). For a received mail it uses the sender, as the recorded assumption says. A gone message gives 404 and a malformed id gives 400; neither creates anything. A missing CSRF token is rejected. All of this is covered by `MailGtdWebTests`.
- AC2: links point at the message row id. The EMAIL resolver re-resolves them by Message-ID. Tests cover a move that creates a new row, a move that keeps the row, repeated moves, and requests that use an old id (for task, waiting-for and note).
- `toNote` reads the body outside the DB transaction, then links the current copy inside it. Mail server failures map to 502/503 and create no note (`MailGtdMailServerTests`). Log statements contain no mail content or credentials.
- `api/openapi.yaml` and the generated client (`mail-to-task/-waiting/-note`, `MailGtdItemResponse`) match the controller. `type` is documented as the full `ItemType` enum, with the description "TASK or NOTE". That is consistent and fine.
- Note rendering on the web: I found no `bypassSecurityTrust*` use outside tests. HTML entities decoded into the Markdown body do not add a new XSS path beyond plain-text mail and user-written notes.
- `reviews/mail-gtd/fixes.md` and `tests.md` cite commit hashes from before the last rebase (e.g. 21955657, 1efb5405). The parent should use the current hashes in `quality.json`.
- This review did not run tests or Gradle tasks. The evidence for AC3/AC4 (Testcontainers suite, `OpenApiContractTest`) is the parent worker's responsibility.

## Review conclusion

No material issues found. Both round-2 fixes are correct and tested. CR-1 is a small, optional hardening of the earlier inline-link fix. CR-2 stays deferred by a recorded decision.
