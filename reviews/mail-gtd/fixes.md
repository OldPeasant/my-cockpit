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

## Final status

All validated findings fixed and validation passed.
