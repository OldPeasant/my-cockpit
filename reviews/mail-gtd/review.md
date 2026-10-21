# Code Review

Date: 2026-10-21

Task: mail-gtd - Mail to GTD integration

Scope: re-review after fixes. `git diff 0952b8da..HEAD -- server` (production code in `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/` and tests in `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/`). The parent named base 4e2c9b78 and HEAD 3b9dc8dd. During the review the branch was rebased onto 0952b8da (new HEAD 0c3927e5). `git diff 3b9dc8dd 0c3927e5 -- server` is empty, so the reviewed server code is identical in both.

Requirements:
- tasks/mail-gtd.md acceptance criteria and recorded assumptions
- SPEC.md §7.3 (GTD integration), §4.2/§4.3 (conventions, links, Message-ID re-resolution)
- AGENTS.md project notes (module boundaries, Problem Details, logging rules)
- Previous review (CR-1..CR-3) and reviews/mail-gtd/fixes.md
- Existing code: HtmlMailSanitizer, NoteMarkdown, NoteService, LinkService, MailMessageLocator

Git range:
- Branch: main
- HEAD: 0c3927e5809f16d96c9d5713a72803e1926fa5bd (same server code as 3b9dc8dd3155b8ea8f4326fe0c68f21bd884f7dc)
- Base/start: 0952b8da8dc78e7bc2484b106be71402b8e4906d (before the rebase: 4e2c9b7834f3d24c1f6cee61d97ace9b50071c6b)
- Commits reviewed: 6 (d6802d66, 4a0c4dc2, 3a2ae031, 3ae6bf4f, 3abab30c, 0c3927e5). Only d6802d66, 3ae6bf4f and 3abab30c touch `server/`.

Summary: 0 P0, 0 P1, 1 P2, 2 P3

## Verification of previous findings

- CR-1 (HTML-only mails lose block structure): fixed for HTML written without whitespace. `htmlText` (`MailGtdService.kt:177-219`) adds blank lines around paragraph-level blocks, a line break around line-level blocks and `<br>`, and a space after table cells. It also collapses blank-line runs. The new test `to-note keeps the block structure of an HTML part written without whitespace` checks the exact output. One related gap remains, for HTML that does contain whitespace: see CR-1 below.
- CR-2 (inline `[[type:id]]` tokens in mail text create SYNC links): fixed. `neutralizeInlineLinks` (`MailGtdService.kt:225`) rewrites every `[[` as `[\[`. `NoteMarkdown.INLINE_LINK` (`NoteMarkdown.kt:19`) needs a literal `[[`, so it no longer matches. The fix covers plain and HTML-derived text. Truncation that happens afterwards cannot recreate `[[`. The new test covers both kinds of mail and `[[[`.
- CR-3 (duplicated `MailConnectionException` handler): not fixed. The parent ruled it out of scope. It is still present and is listed again below as P3 so it can be tracked.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Source indentation from HTML mail is kept verbatim and turns into Markdown code blocks**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt:198`
    - Category: Correctness
    - Problem: `htmlText` appends `node.wholeText` for every text node, outside `<pre>` too. `HtmlMailSanitizer` keeps the source whitespace: jsoup keeps whitespace text nodes, `Cleaner` copies the whole text, and output uses `prettyPrint(false)` (`HtmlMailSanitizer.kt:62-65`). So indentation from pretty-printed HTML ends up in the note, for example `<table>\n  <tr>\n    <td>\n      Hello there,\n    </td>…`. That HTML gives a line `      Hello there,` after the blank line that `table` adds. Only trailing blanks are removed (`TRAILING_BLANKS`); leading blanks are not. In the CommonMark body (`bodyMd`), a line indented 4 or more spaces after a blank line is an indented code block. So text from templated or newsletter HTML mail, the common case for HTML-only mail, shows as monospace code. In code blocks the `[\[` escapes from the CR-2 fix also show their backslash. Browsers collapse this whitespace; the converter does not.
    - Why it matters: The fix for the previous CR-1 aimed at readable note text from HTML mail. That goal is not met for HTML formatted with indentation. All HTML tests use input without whitespace (`<p>Hello <b>Ann</b></p>`, the new block-structure test), so this case is untested.
    - Suggested fix: Outside `<pre>`, collapse whitespace runs in text nodes the way HTML rendering does: replace `\s+` with a single space, or use `StringUtil.normaliseWhitespace`, and keep `wholeText` only when an ancestor is `pre`. Or strip leading blanks on each line after the traversal, except inside `pre`. Add a test with indented, multi-line HTML (`<table>`/`<td>` and `<p>` on their own indented lines) that checks no line starts with 4 or more spaces.

## P3 Low

- [ ] **CR-2 | Some block elements the sanitizer allows are not treated as block boundaries**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt:165-166`
    - Category: Correctness
    - Problem: `BLOCKS` and `LINES` leave out several block-level tags that `HtmlMailSanitizer`'s safelist keeps: `center`, `main`, `aside`, `details`, `summary` and `caption` (`HtmlMailSanitizer.kt:127-198`). HTML without whitespace between them runs words together, as in the original CR-1. For example, `<center>Offer</center><center>Details</center>` becomes `OfferDetails`. `<center>` is still common in older newsletter templates.
    - Why it matters: The block-structure fix is incomplete for tags that pass sanitization.
    - Suggested fix: Add `center`, `main`, `aside`, `details`, `summary` and `caption` to `LINES` (or `BLOCKS`), and add one of them to the existing block-structure test.

- [ ] **CR-3 | `MailConnectionException` handler copied verbatim from `MailMessageController` (carried over, deferred)**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdController.kt:87-96`
    - Category: Quality
    - Problem: The handler is the same as `MailMessageController`'s, and a third copy exists in `MailAccountController`. The parent deferred this as outside `touches`.
    - Why it matters: The mapping of mail server failures to Problem Details can drift between the endpoints.
    - Suggested fix: Add a follow-up task for a shared `@RestControllerAdvice` (or helper) for the mail controllers. No change is needed in this task if it stays deferred.

## Notes (no finding)

- AC1: `to-task`, `to-waiting` and `to-note` return 201 with `Location`. Each creates its item and a MANUAL link through `LinkService` in one transaction. For mail the user sent, waiting-for uses the recipients (To, else Cc, else Bcc). For received mail it uses the sender, as the recorded assumption says. A missing or gone message gives 404 with nothing created. A malformed id gives 400. A missing CSRF token is rejected. All of this is tested.
- AC2: links point at `mail_message.id`. The EMAIL resolver re-resolves them through `MailMessageLocator`. Tests cover a move that creates a new row (Message-ID re-resolution), a move that keeps the row, and requests that use an old id.
- `toNote` reads the body outside the transaction, then links `view.message.id` inside it. This keeps the mail-server call out of the DB transaction. Mail server failures map to 502/503 and create no note (tested in `MailGtdMailServerTests`).
- Module boundaries are respected: only `TaskService`, `NoteService`, `LinkService` and mail read APIs and repositories are used. Log statements contain no mail content.
- This review did not run tests. AC3/AC4 evidence is the parent worker's responsibility.

## Review conclusion

Material issues found; fix before completing the task. Fix CR-1 or explicitly accept it with an assumption. CR-2 is a small, low-priority follow-on. CR-3 remains deferred by decision.
