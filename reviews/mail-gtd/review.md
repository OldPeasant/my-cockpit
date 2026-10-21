# Code Review

Date: 2026-10-21

Task: mail-gtd - Mail to GTD integration

Scope: `git diff 4e8162c6..HEAD -- server` (production code in `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/`, plus `MailGtdWebTests.kt`); `api/openapi.yaml` and the generated web client were checked for presence only.

Requirements:
- tasks/mail-gtd.md acceptance criteria and recorded assumptions
- SPEC.md §7.3 (GTD integration), §4.3 (links, Message-ID re-resolution), §4.2 (conventions)
- AGENTS.md project notes (module boundaries, Problem Details, logging rules)
- Existing code: MailMessageLocator, MailReadService, LinkService, TaskService, NoteService, HtmlMailSanitizer, MailMessageController

Git range:
- Branch: main
- HEAD: 6c05bea13c1bebed0de60e9d38592bc3c26d640e
- Base/start: 4e8162c650b76635a309dfd3698a478eda5b7948
- Commits reviewed: 2 (15dee1c6, 6c05bea1)

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | HTML-only mails lose paragraph and line structure in the note body**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt:92`
    - Category: Correctness
    - Problem: When there is no plain part, the note text is `Jsoup.parse(view.body.html).wholeText()`. `view.body.html` comes from `HtmlMailSanitizer`, which writes with `prettyPrint(false)` (`HtmlMailSanitizer.kt:65`), so the only whitespace left is whatever the source HTML had. `wholeText()` joins the raw text nodes and puts no separator at block boundaries (`p`, `div`, `li`, `tr`/`td`, headings). HTML written without newlines between elements (common in generated and newsletter mail), such as `<p>Hello</p><p>World</p>` or `<div>a</div><div>b</div>`, ends up as `HelloWorld`, with words run together.
    - Why it matters: The recorded assumption says the HTML part is "converted to text", and a note whose text has run-together words is hard to read and search. The only test (`to-note uses the HTML part as text...`) uses a single `<p>`, so this case is not tested.
    - Suggested fix: Convert block-level boundaries to newlines before taking the text: for example, walk the document with a `NodeVisitor` that adds `\n` after block elements and `<br>`, or put a newline after each block element and then call `wholeText()`. Then add a test with several `<p>`/`<div>` elements and no source whitespace.

## P3 Low

- [ ] **CR-2 | Mail text is parsed for inline `[[type:id]]` links when the note is created**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt:98`
    - Category: Correctness
    - Problem: The untrusted mail text goes into `bodyMd` unchanged. `NoteService.create` then runs `links.sync(ref(saved), NoteMarkdown.inlineLinks(saved.bodyMd))` (`NoteService.kt:106`). So any `[[task:<uuid>]]`-style token in a received mail creates SYNC links from the new note to whatever items the token names. Sync targets are not checked against resolvers, so they can be arbitrary ids.
    - Why it matters: A mail's sender can add entries to the user's Linked items panel without the user's intent. The impact is low in a single-user app, but this was not a deliberate choice: the assumption says the mail link is MANUAL, not inline.
    - Suggested fix: Escape or neutralize `[[` in the imported text (for example, `\[[` or a zero-width break) before building the note body, or record an assumption that inline tokens in mail text are kept on purpose.

- [ ] **CR-3 | `MailConnectionException` handler copied verbatim from `MailMessageController`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt:87`
    - Category: Quality
    - Problem: `handleConnectionFailure` matches `MailMessageController.kt:233-242` line for line, and a third copy exists in `MailAccountController.kt:287`. Any change to how mail server failures are mapped (status, `errorCode`, logging) now has to be made in three places.
    - Why it matters: The copies will drift, and clients will see inconsistent Problem Details for the same failure.
    - Suggested fix: Move the mapping into one `@RestControllerAdvice` scoped to the mail controllers (or a shared helper function), either now or in a follow-up cleanup task.

## Notes (no finding)

- AC1: all three endpoints exist, return 201 with `Location`, and create a MANUAL link through `LinkService` within one transaction. A missing or gone message gives 404 with nothing created (tested). For mail the user sent, waiting-for uses the recipients (To, else Cc, else Bcc). For received mail it falls back to the sender, as recorded in the assumption.
- AC2: links point at `mail_message.id` and are re-resolved through `MailMessageLocator`. Tests cover a move that creates a new row (Message-ID re-resolution) and a move that keeps the row.
- The module boundary is respected: the service uses `TaskService`, `NoteService` and `LinkService` and does not touch their repositories. Log statements contain no mail content.
- This review did not run the tests. AC3/AC4 evidence is the parent worker's responsibility.

## Review conclusion

Material issues found; fix before completing the task. (CR-1 should be fixed or explicitly accepted. CR-2 and CR-3 are low priority.)
