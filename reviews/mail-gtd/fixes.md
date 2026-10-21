# Fix Report

Date: 2026-10-21

Task: mail-gtd - Mail to GTD integration

Scope: current task; findings CR-1 and CR-2 from `reviews/mail-gtd/review.md`, changes limited to `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd` and `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd`. Code fix commit: eed92ccf.

## Fixed findings

### CR-1 — HTML-only mails lose paragraph and line structure in the note body

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt`

Problem:
For mail with no plain part, the note text came from `Jsoup.parse(html).wholeText()`. That adds no separator at block boundaries, so `<p>Hello</p><p>World</p>` became `HelloWorld`.

Fix:
A new `htmlText` walks the sanitized HTML body with a jsoup `NodeTraversor`. It ensures a blank line before and after `p`, `h1`-`h6`, `blockquote`, `pre`, `ul`, `ol`, `dl`, `table` and `hr`. It ensures a line break before and after `div`, `li`, `tr`, `dt`, `dd`, `section`, `article`, `header`, `footer`, `address`, `figure` and `figcaption`. Each `<br>` becomes a line break, and a space follows each `td`/`th`. Trailing blanks on each line are removed, and runs of 3 or more newlines become one blank line. New test `to-note keeps the block structure of an HTML part written without whitespace` covers `p`, `div`, `br`, `ul/li` and `table` with no whitespace in the source. It checks the exact text and that no triple newline is left.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' ktlintCheck` — Pass (42 tests, 0 failures)

### CR-2 — Mail text is parsed for inline `[[type:id]]` links when the note is created

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt`

Problem:
Untrusted mail text went into `bodyMd` unchanged. `NoteService.create` then synced every `[[type:uuid]]` token as a SYNC link (`NoteMarkdown.inlineLinks`).

Fix:
Before the text goes into the body, `neutralizeInlineLinks` puts a backslash between every pair of adjacent `[` (regex `\[(?=\[)`), so `[[` becomes `[\[` and `[[[` becomes `[\[\[`. `NoteMarkdown.INLINE_LINK` needs a literal `[[`, so it no longer matches. Markdown renders `\[` as `[`, so the note still displays the original text. This applies to both plain and HTML-derived text. It does not apply to the generated header, which holds only addresses and the date. New test `inline link tokens in a mail's text do not link the note to other items` covers a plain mail and an HTML mail that contain `[[task:<uuid>]]` (and `[[[note:<uuid>]]]`). It checks that the note has only the one link, to the mail, and that the body contains no `[[`.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' ktlintCheck` — Pass

## Not fixed

### CR-3 — `MailConnectionException` handler copied verbatim from `MailMessageController`

Status:
Out of scope

Reason:
The parent worker ruled it out of scope for this fix pass. The fix would mean a shared advice across mail controllers outside `mail/gtd`.

## Validation summary

Focused tests:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' ktlintCheck` — Pass (MailGtdWebTests 37, MailGtdMailServerTests 5, 0 failures)

Full test suite:
- `cd server && ./gradlew check` — 2845 tests, 1 failure in `ClockConfigurationTests > the application clock is the system clock in UTC`. It is an unrelated timing flake: a microsecond-truncated instant was compared against a nanosecond one. Rerun with `./gradlew test --tests '*ClockConfigurationTests'` — Pass.

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` — Pass (after `ktlintFormat`)

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed", exit 0). Note: on this machine the run took more than 10 minutes.

Remaining unresolved findings:
1 (CR-3, out of scope)

## Round 1 status

All validated findings fixed and validation passed.

## Round 2 (re-review findings)

Scope: re-review findings re-review CR-1 and re-review CR-2 from `reviews/mail-gtd/review.md` (re-review). Changes limited to `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt` and `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt`. Code fix commit: 21955657.

### Fixed findings

#### re-review CR-1 — Source indentation from HTML mail is kept verbatim and turns into Markdown code blocks

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt`

Problem:
`htmlText` appended `TextNode.wholeText` everywhere. Source indentation of pretty-printed HTML (for example `<td>` content on its own indented line) was kept, so lines started with 4 or more spaces after a blank line and rendered as indented code blocks. Confirmed in the code before the change.

Fix:
Text nodes with a `pre` ancestor still use `wholeText`. Elsewhere, runs of HTML whitespace (space, tab, CR, LF, FF; not U+00A0) collapse to one space, as a browser renders them. The text is left-trimmed when the output is at a line start or already ends with a space. The space after a table cell is only added when the output does not already end with a space or newline. As a result, no line outside `<pre>` starts with a blank. New test `to-note collapses the source indentation of a pretty-printed HTML part but keeps pre text` uses an indented, multi-line `<table>`/`<td>` and `<div>`/`<p>` part with inline `<b>`/`<i>` and a `<pre>`. It checks the exact text (`Hello there, welcome aboard`, `First paragraph continues here.`, `Second bold words.`), that no line except the `<pre>` line starts with a space, that the `<pre>` indentation is kept, and that there are no triple newlines.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' ktlintCheck` — Pass

#### re-review CR-2 — Some block elements the sanitizer allows are not treated as block boundaries

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt`

Problem:
`center`, `main`, `aside`, `details`, `summary` and `caption` were not in `BLOCKS`/`LINES`. Words ran together in HTML without whitespace, for example `<center>Offer</center><center>Details</center>` became `OfferDetails`.

Fix:
`details` goes into `BLOCKS` (a blank line around it). `center`, `main`, `aside`, `summary` and `caption` go into `LINES` (a line of their own). The existing test `to-note keeps the block structure of an HTML part written without whitespace` now ends with `<center>Offer</center><center>Details</center>` and expects `...\n\nOffer\nDetails`.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' ktlintCheck` — Pass

### Not fixed

#### CR-3 (re-review CR-3) — duplicated `MailConnectionException` handler

Status:
Out of scope

Reason:
Still deferred by the parent worker, as in round 1.

### Validation summary (round 2)

Focused tests:
- `cd server && ./gradlew ktlintFormat` then `./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' ktlintCheck` — Pass (MailGtdWebTests 41, MailGtdMailServerTests 5, 0 failures, 0 skipped)

Full test suite:
- `cd server && ./gradlew check` — Pass (BUILD SUCCESSFUL)

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` — Pass. The server build has no detekt task; `ktlintCheck` is its lint.

`.taskcheck`:
- `sh scripts/check.sh` — Pass ("all checks passed", exit 0)

Remaining unresolved findings:
1 (CR-3, out of scope)

## Final status

Round 1 (CR-1, CR-2) and round 2 (re-review CR-1, re-review CR-2): all validated findings fixed and validation passed. CR-3 stays deferred as out of scope.

## Round 3 (final review findings)

Date: 2026-10-21

Findings source: `reviews/mail-gtd/review.md` (final review), CR-1 and CR-2.

### Fixed findings

#### CR-1 — display names in the to-note header are not neutralized for inline `[[type:id]]` links

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt`

Problem:
`noteBody` neutralized only the mail text. The header lines (`**From:**`, `**To:**`, `**Cc:**`) embed display names that the sender controls. A display name such as `[[task:UUID]]` token therefore reached the note body unchanged, and `NoteService` synced it as an inline link to the user's item.

Fix (parent worker commits 4849bb8b and a79e4c5e, validated here; the fixer changed no code in this round):
- `noteBody` now runs `neutralizeInlineLinks` on the whole assembled body (header and text) before `truncate`. The generated parts of the header never contain `[[`, so this changes nothing else. Truncation only shortens the text, so it cannot create a new `[[`.
- `neutralizeInlineLinks` now writes each `[[` as `[ [` instead of the Markdown escape `[\[`. The lookahead regex `\[(?=\[)` also handles runs such as `[[[` (giving `[ [ [`). Reason: with the escape form, flexmark's `TextCollectingVisitor` (used by `NoteMarkdown.plainText`) threw `AssertionError: length:174 != computeLength(): 176` under `-ea` (as in tests). It happened on a header that combined bold labels, escapes, angle-bracket address autolinks and hard line breaks. The space form has no escape for flexmark to miscount, and `NoteMarkdown.INLINE_LINK` cannot match it.
- The inline-link test assertion for the body text now expects `See [ [task:UUID]]` (UUID = the target id). New regression test: `inline link tokens in display names and the subject do not link the note to other items`. It uses `[[...]]` tokens in the From, To and Cc display names and in the subject, plus a triple bracket in Cc. It asserts that the note links only to the mail, that the header shows the neutralized names, and that the body contains no `[[`.

Completeness check: `NoteMarkdown.inlineLinks` is called only on `bodyMd` (`NoteService.kt:106`, `:225`), so the note title (subject) cannot create links. The whole body is now covered.

Validation:
- `cd server && ./gradlew ktlintCheck test --rerun-tasks --tests 'ch.sonensei.cockpit.mail.gtd.*'` — Pass (BUILD SUCCESSFUL; MailGtdWebTests 43, MailGtdMailServerTests 5, 0 failures, 0 errors, 0 skipped; the new CR-1 test ran and passed)

### Not fixed

#### CR-2 — `MailConnectionException` handler copied from `MailMessageController`

Status:
Out of scope

Reason:
A shared handler would have to change `MailMessageController` and `MailAccountController`, which are outside this task's touches. The parent worker deferred it with a recorded assumption, and the final review agrees that this task needs no change while it stays deferred.

### Validation summary (round 3)

Focused tests:
- `cd server && ./gradlew ktlintCheck test --rerun-tasks --tests 'ch.sonensei.cockpit.mail.gtd.*'` — Pass (48 tests, 0 failures)

Full test suite:
- Not run by the fixer in round 3; the parent worker runs `.taskcheck` before `done`.

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` — Pass (part of the command above)

`.taskcheck`:
- Not run by the fixer in round 3; the parent worker runs it.

Remaining unresolved findings:
1 (CR-2, out of scope)

### Final status (round 3)

All validated findings fixed and validation passed. CR-2 stays deferred as out of scope.

## Round 4 (test-writer finding TW-1)

Date: 2026-10-21

Findings source: `reviews/mail-gtd/tests.md` ("Final round: bugs found"), TW-1. Base d12d5eb4a2e21d90cc8289fd5a1abfc2707357f1; task commits `git log d12d5eb4..HEAD`.

### Fixed findings

#### TW-1 — to-note returns 500 when the mail's text or a display name contains Markdown backslash escapes next to bold, autolinks and hard breaks

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt`

Problem:
Backslash escapes written by the sender (in display names or mail text, for example `Grace \[[x]]`) were copied into the note Markdown as they were. With bold header labels, `<address>` autolinks and hard line breaks next to them, flexmark's `TextCollectingVisitor` in `NoteMarkdown.plainText` threw `AssertionError: length:N != computeLength(): M` under `-ea`, so to-note returned 500. Without `-ea` it gave garbled search and title text instead.

Fix (parent worker commit 93acb541, validated here; the fixer changed no code in this round):
- `neutralizeInlineLinks` first writes `[[` as `[ [`, then writes every backslash followed by ASCII punctuation (`ESCAPE = \\(?=\p{Punct})`) as U+2216 `∖`. On the JVM without `UNICODE_CHARACTER_CLASS`, `\p{Punct}` is exactly the CommonMark set of escapable characters, so the note body has no Markdown backslash escapes left. A backslash before a letter, digit, space, non-ASCII character or line end is not an escape and stays unchanged.
- Order is safe. The second step only swaps `\` for `∖` and never removes a character, so it cannot create a new `[[`; `[\[` becomes `[∖[`. The lookahead does not consume, so runs work (`\\[[` becomes `∖∖[ [`). The generated header has no backslash, and `truncate` runs afterwards and appends `…`, which is not punctuation, so it cannot form a new escape.
- The two TW-1 tests keep their behavior assertions (201, a note linked only to the mail, no `[[`). Only their expected body strings changed from `\[` to `∖[`, which matches the new neutralization. They were not weakened.
- Assumption #6 for mail-gtd records the lossy trade-off. The root fix in `NoteMarkdown.plainText` is the separate task `notes-markdown-escapes`, which per commit 89260240 also covers removing this workaround.

Completeness check: TW-1 is about backslash escapes, and every CommonMark backslash escape is now removed from the body, so TW-1 is fully resolved. One residual risk is not part of TW-1 and was not verified: entity and numeric character references (`&amp;`, `&#91;`) are also decoded text that differs from the source and might make flexmark miscount in the same way. They cannot create inline links, because `INLINE_LINK` matches only a literal `[[` in the raw Markdown. I did not add a probe, because other helpers were running Gradle on the same clone at the time. Like the final review ("Other escape forms"), I leave it for the parent or `notes-markdown-escapes` to settle, for example with `NoteMarkdown.plainText("**F:** a &amp; b <c@d.e>  \n**T:** &#91;x <c@d.e>")` under `-ea`.

Validation:
- `cd server && ./gradlew -Pkotlin.daemon.jvmargs=-Xmx3g ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.mail.gtd.*'` — Pass (BUILD SUCCESSFUL; MailGtdWebTests 46, MailGtdMailServerTests 5, 0 failures, 0 errors, 0 skipped; results written 2026-10-21 08:03). Both TW-1 tests ran and passed. The 46 includes one more test that another helper had not yet committed (`to-note keeps escapes in the subject as the title and neutralizes them in To and every Cc name`), and it passed too. Without it the count is the parent's 45 + 5 = 50. Two earlier tries of the same command stopped with `java.io.EOFException` in the Gradle `:test` task before any test result was written. That happened while another process was using the same `build/test-results` directory. It was not a test failure, and the next run passed.

### Not fixed

#### CR-1 (final review) — `MailConnectionException` handler copied from `MailMessageController`

Status:
Out of scope

Reason:
Still deferred to the follow-up task `mail-connection-errors`, as in earlier rounds.

### Validation summary (round 4)

Focused tests:
- `cd server && ./gradlew -Pkotlin.daemon.jvmargs=-Xmx3g ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.mail.gtd.*'` — Pass (51 tests with the uncommitted extra test, 0 failures)

Full test suite:
- Not run by the fixer in round 4; the parent worker runs `.taskcheck` before `done`.

Lint/type-check/build:
- `ktlintCheck` (main, test and script source sets) — Pass (part of the command above)

`.taskcheck`:
- Not run by the fixer in round 4 (other helpers were using the same clone's Gradle build); the parent worker runs it.

Remaining unresolved findings:
1 (CR-1 of the final review, out of scope)

### Final status (round 4)

All validated findings fixed and validation passed. TW-1 is fully resolved by 93acb541, and the fixer needed no code change. Entity references remain an unverified residual risk, outside TW-1.
