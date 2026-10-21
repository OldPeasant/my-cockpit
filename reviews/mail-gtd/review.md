# Code Review

Date: 2026-10-21

Task: mail-gtd - Mail to GTD integration

Scope: final review of the whole task at the current revision, committed changes only. The task commits are `git log d12d5eb4..HEAD` (base supplied by the parent). The focus of this round is commit 93acb541, which fixes test-writer finding TW-1: `neutralizeInlineLinks` now also writes a backslash that precedes ASCII punctuation as U+2216 (`∖`). Production files of the task: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt` and `MailGtdController.kt`. I re-read `MailGtdService.kt` in full, together with `NoteMarkdown.kt` (the consumer that triggered TW-1) and the escape and inline-link tests in `MailGtdWebTests.kt:394-487`.

Limitation: shell access was denied in this review session, so I could not run `git diff`, `git log` or Gradle. I resolved HEAD from `.git/refs/heads/main` and reviewed the current file contents directly, not the diff of 93acb541 line by line. The commit list and the claim that 93acb541 touches only `MailGtdService.kt` and the tests come from the parent's description and the task notes. I did not verify them against Git.

Requirements:
- tasks/mail-gtd.md: acceptance criteria and recorded assumptions, in particular #6 (07:39, backslash before ASCII punctuation written as U+2216) and the 07:19 assumption (`[[` written as `[ [`)
- tasks/notes-markdown-escapes.md (follow-up task with the root fix)
- reviews/mail-gtd/tests.md (TW-1), reviews/mail-gtd/fixes.md, the previous reviews/mail-gtd/review.md
- SPEC.md §7.3 and §4.2 (through earlier rounds), AGENTS.md project notes

Git range:
- Branch: main
- HEAD: 75ac48d5318b4defecb665c0e90a8591ab72b1cf
- Base/start: d12d5eb4a2e21d90cc8289fd5a1abfc2707357f1
- Commits reviewed: the task commits in `d12d5eb4..HEAD` as given by the parent; the last commit that changes code is 93acb541. Not counted here, because `git log` could not be run.
- Uncommitted changes: the session-start git status reported a clean working tree.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Verification of TW-1 fix (93acb541)

- Mechanism (`MailGtdService.kt:191`, `:267`): `ESCAPE = \\(?=\p{Punct})` and the replacement `∖`. On the JVM without `UNICODE_CHARACTER_CLASS`, `\p{Punct}` is exactly the ASCII punctuation set ``!"#$%&'()*+,-./:;<=>?@[\]^_`{|}~``. That is the CommonMark set of escapable characters, so every Markdown backslash escape in the body is removed. The lookahead does not consume, so runs work: `\\[` becomes `∖∖[`. A backslash before a letter, a digit, a space or a newline (for example `C:\Users`, or a trailing `\` used as a hard break) is not an escape and stays as it is.
- Order and interaction: `[[` is replaced with `[ [` first, then the escapes. Replacing `\` between two `[` gives `[∖[`, so the second step cannot create a new `[[`. The generated header (`**From:**`, `<address>`, the date) contains no backslash. `truncate` runs after neutralization and appends `…`, which is not ASCII punctuation, so a cut that leaves a trailing `\` cannot form an escape.
- Tests: `to-note of an HTML mail with backslashes, brackets and a link creates the note` and `to-note of a mail with backslash escapes, brackets and autolinks next to the bold header creates the note` (`MailGtdWebTests.kt:439-487`) check for 201, a single link to the mail, the exact neutralized header and body lines, and no `[[`. Their inputs include escapes in display names, `\\[[`, `\*` inside bold, an escape inside an autolink, a trailing backslash, escaped backticks and `\\` at the end. The latest local results in `server/build/test-results/test/` (MailGtdWebTests 45 tests, MailGtdMailServerTests 5, 0 failures/errors, written 07:56-07:58 +02:00, after assumption #6 was recorded at 07:39) are consistent with a passing suite. Those files do not name a commit, so the parent's own `tasks.py verify` evidence for AC3 remains authoritative.
- Data fidelity: the change is lossy. A plain-text mail's `\.` or `\[` is stored as `∖.`/`∖[`, so copying a path, regex or code snippet from the note gives a different character, and searching the note for `\` will not find it. The previous behaviour was also lossy, because Markdown hid such backslashes as escapes. The new form looks closer to the original mail. Assumption #6 records the trade-off, the KDoc (`:260-266`) explains it, and it only affects to-note bodies. I accept it as a temporary workaround. CR-2 below covers the missing follow-up to remove it.
- Other escape forms: backslash escapes are fully covered (see above). CommonMark has one other escape mechanism, entity and numeric character references (`&amp;`, `&#91;`, `&nbsp;`), and these are not neutralized. They cannot create inline links, because `NoteMarkdown.INLINE_LINK` matches only a literal `[[` in the raw Markdown. Whether flexmark's `TextCollectingVisitor` miscounts them in the same way as escapes is **not verified**, because no Gradle or probe run was possible in this session. Both kinds of node are decoded text that differs from the source, so it is a plausible residual trigger. It is recorded as a note, not a finding. A quick probe (`NoteMarkdown.plainText("**F:** a &amp; b <c@d.e>  \n**T:** &#91;x <c@d.e>")` under `-ea`) would settle it. If the probe fails, it belongs in `notes-markdown-escapes` (root cause), and an entity in mail text would be a TW-1-class 500 for to-note.

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
    - Suggested fix: Leave it to the planned task `mail-connection-errors`. No change is needed in this task.

- [ ] **CR-2 | Removing the U+2216 workaround is not tracked by the root-fix task**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt:191,267`; `tasks/notes-markdown-escapes.md`
    - Category: Quality
    - Problem: Assumption #6 says that after `notes-markdown-escapes`, mail-gtd "could keep the mail's escapes verbatim". However, the acceptance criteria and touches of `notes-markdown-escapes` cover only `NoteMarkdown.kt` and the notes tests. No task brings to-note back to a faithful representation once the root cause is fixed. For plain text, that means escaping the mail's backslashes as `\\` rather than keeping them verbatim, because verbatim backslashes would be read as escapes.
    - Why it matters: The lossy replacement of a mail's backslashes with a look-alike character would silently become permanent behaviour. The project rules say assumptions should not turn into product decisions without anyone noticing.
    - Suggested fix: In `tasks/` only (this does not affect freshness), add a note or criterion to `notes-markdown-escapes`, or create a small follow-up task that depends on it, to replace the U+2216 workaround in `MailGtdService.neutralizeInlineLinks` and update the two escape tests. No code change is needed in this task.

## Notes (no finding)

- AC1: `to-task`, `to-waiting` and `to-note` return 201 with the item and create a MANUAL link to the current copy in one transaction. Waiting-for uses the recipients of a sent mail (To, else Cc, else Bcc) and the sender of a received mail, as the assumption records. Neutralization now covers the whole note body: inline-link tokens (`[ [`) and Markdown escapes (`∖`). With 93acb541, sender-controlled backslash escapes no longer make to-note return 500 in the tested forms.
- AC2: Message-ID re-resolution on moves is unchanged and covered by tests (`MailGtdWebTests.kt:489-567`).
- AC4: by the parent's description, 93acb541 changes only `MailGtdService` internals and tests. The controller, the DTO and the API contract are unchanged, so the regenerated `api/openapi.yaml` stays current. The parent should still record an explicit `OpenApiContractTest` run.
- Naming: `neutralizeInlineLinks` now also removes Markdown escapes. The KDoc says so, but the opening sentence still says "Mail text", although it now applies to the whole body including the header. This is cosmetic, and I do not report it as a finding.
- Freshness: 93acb541 changes code after the previous test-writer round. `reviews/mail-gtd/tests.md` still describes TW-1 as failing, and its coverage comes from the failing run. Under `docs/QUALITY.md` "Freshness", a test-writer run on this revision is needed, and a `fixes.md` entry for TW-1 if the parent records it as a fix.
- This review ran no Gradle or tests, because shell access was denied. The parent must record the evidence for AC3 (Testcontainers suite) and AC4 with `tasks.py verify`.

## Review conclusion

No material issues found. The TW-1 fix is correct for every CommonMark backslash escape, cannot reintroduce inline links, and is tested. Its loss of fidelity is recorded and acceptable as a temporary workaround. The two remaining P3 findings need no code change in this task: CR-1 is deferred to `mail-connection-errors`, and CR-2 is a task-board note. Entity references are an unverified residual risk; see "Other escape forms".
