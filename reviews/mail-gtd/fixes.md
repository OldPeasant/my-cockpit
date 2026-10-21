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
