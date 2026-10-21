# Code Review

Date: 2026-10-21

Task: mail-gtd - Mail to GTD integration

Scope: final review round of the whole task, committed changes only. `git diff 0cdd3a188e0a35bd13a5754275338e819792e097..bf7e4c0e9f93122f89cb093e565207e0cadd6cd0` covers production code in `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/`, tests in `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/`, `api/openapi.yaml` and the generated web client under `web/src/app/api/generated/`. Since the previous review (7b8461a1), production code changed only in ddf36a3c and af618c04, both in `MailGtdService.kt`. I read that delta line by line and re-read the current `MailGtdService.kt` and `MailGtdController.kt` in full.

Requirements:
- tasks/mail-gtd.md: acceptance criteria and recorded assumptions, including the 07:19 assumption that `[[` is written as `[ [`
- SPEC.md §7.3 and §4.2 (through the earlier rounds)
- AGENTS.md project notes
- The previous final review (CR-1, CR-2) and reviews/mail-gtd/fixes.md "Round 3"
- Existing code: `NoteMarkdown` (`INLINE_LINK`, `plainText`), `NoteService`

Git range:
- Branch: main
- HEAD: bf7e4c0e9f93122f89cb093e565207e0cadd6cd0
- Base/start: 0cdd3a188e0a35bd13a5754275338e819792e097
- Commits reviewed: 16. Fifteen are `mail-gtd:` commits (da0117cf through aaf3c30c). The sixteenth, bf7e4c0e, adds only `tasks/mail-connection-errors.md`. The last commit that changes production code is af618c04.
- Uncommitted changes: `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt` is modified in the working tree. The change adds the test `to-note of a mail with backslash escapes, brackets and autolinks next to the bold header creates the note`. It is not committed, so it is outside this review's scope (see Notes).

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## History of earlier findings

- Round 1: CR-1 (HTML-only mail lost its block structure) and CR-2 (inline `[[type:id]]` tokens in mail text created links) were fixed. CR-3 (duplicated `MailConnectionException` handler) was deferred.
- Re-review: CR-1 (source indentation turned into Markdown code blocks) and CR-2 (some block tags were not boundaries) were fixed. CR-3 stayed deferred.
- Previous final review: CR-1 (P3, display names in the note header were not neutralized) and CR-2 (P3, the deferred duplicated handler).

## Verification of previous findings

- Previous CR-1 (display names in the header): **fixed**. `noteBody` now runs `neutralizeInlineLinks` on the whole assembled body, header and text, before `truncate` (`MailGtdService.kt:145-156`). The generated header parts (`**From:**`, `<address>`, the date) contain no `[[`. `truncate` only removes characters from the end and appends `…`, so it cannot create a new `[[`. The lookahead regex `\[(?=\[)` with the replacement `"[ "` (`:190`, `:264`) turns every run of brackets into a run with spaces between them, for example `[[[` becomes `[ [ [`. `NoteMarkdown.INLINE_LINK` needs a literal `[[` (`NoteMarkdown.kt:19`), so it cannot match the result. `inlineLinks` reads only `bodyMd`, so the subject in the title cannot create links. The new committed test `inline link tokens in display names and the subject do not link the note to other items` (`MailGtdWebTests.kt:414-437`) checks that From, To and Cc names, including a triple bracket, are neutralized and that the only link is to the mail. The existing body-text test now expects `See [ [task:...]]`.
- The switch from `[\[` to `[ [` is recorded as an assumption. It changes how a mail containing `[[` looks: one visible space is added. It removes the Markdown escape that triggered the flexmark `TextCollectingVisitor` length assertion. This is an acceptable trade-off and it is documented in the KDoc (`:259-263`).
- Previous CR-2 (duplicated `MailConnectionException` handler): still present. It is deferred by a recorded assumption, and the follow-up task `tasks/mail-connection-errors.md` (deps `mail-gtd`) now exists. It is carried over below as CR-1.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `MailConnectionException` handler copied from `MailMessageController` (carried over, deferred, follow-up task exists)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt:87-96`
    - Category: Quality
    - Problem: This handler is the same as the one in `MailMessageController`, and `MailAccountController` has a third copy.
    - Why it matters: The mapping of mail server failures to Problem Details (502/503, `errorCode`) can drift between the mail endpoints.
    - Suggested fix: Leave it to `mail-connection-errors`, which is already planned. No change is needed in this task.

## Notes (no finding)

- AC1: `to-task`, `to-waiting` and `to-note` have not changed since the previous review, apart from neutralization. Each returns 201 with `Location` and `MailGtdItemResponse`, and creates its item and a MANUAL link in one transaction. Waiting-for uses the recipients for a sent mail and the sender for a received mail, as the assumption records. A gone message gives 404 and a malformed id gives 400.
- AC2: Links point at the row of the current copy and are re-resolved by Message-ID. Committed tests cover a move that creates a new row, a move that keeps the row, repeated moves, waiting-for and note links, and requests that use an old id (`MailGtdWebTests.kt:477-555`).
- AC4: The controller and DTO have not changed since the OpenAPI and the client were regenerated in 6b3f157c. No later commit touches `api/openapi.yaml` or `web/src/app/api/generated/`.
- Residual flexmark risk: `NoteMarkdown.plainText` (outside this task's touches) can hit the flexmark `TextCollectingVisitor` length assertion on other Markdown escapes. Display names or mail text with a backslash are examples. The assertion fires only with `-ea`, which is how the tests run. The recorded assumption already acknowledges this. The uncommitted test in the working tree appears to exercise exactly this case. If it passes, it is useful evidence and should be committed. If it fails, `to-note` can throw a 500 under `-ea` for such mail, and the parent should treat that as a new finding before `done`. This review did not run it and makes no claim about its outcome.
- `reviews/mail-gtd/tests.md` was last updated in 4121d8e0, before the code changes in ddf36a3c and af618c04. `docs/QUALITY.md` "Freshness" therefore requires a test-writer run on the final code.
- This review did not run Gradle or tests. The parent worker must record the evidence for AC3 and AC4 (the Testcontainers suite and `OpenApiContractTest`) with `tasks.py verify`.

## Review conclusion

No material issues found. The fix for the previous CR-1 is correct, complete and tested. The only remaining finding (CR-1, P3) is deferred to the existing follow-up task `mail-connection-errors`.
