---
title: Robust plain text of note Markdown with backslash escapes
status: in_progress
owner: dell26/agent-2
deps: [mail-gtd]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteMarkdown.kt,server/src/test/kotlin/ch/sonensei/cockpit/notes,server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt,server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd]
prio: 2
lease_until: 2026-10-26T04:31:03Z
---
## Goal
`NoteMarkdown.plainText` (flexmark `TextCollectingVisitor`) miscounts Markdown backslash escapes next to bold text,
autolinks and hard line breaks: with JVM assertions on (tests) it throws `AssertionError: length:N != computeLength(): M`
(note create/update returns 500), without them it yields garbled search text/titles (e.g. `F:** [x`). Repro:
the Kotlin string `"**F:** \\[x <c@d.e>  \n**T:** \\[y <c@d.e>"` or a header like the one mail-gtd's to-note builds with `\[` in a
display name. Found in mail-gtd, which works around it by writing mail escapes as U+2216.

## Acceptance criteria
- [x] plainText/titleOf of Markdown with escapes mixed with bold, autolinks and hard breaks neither throws (tests run with -ea) nor drops/garbles text (unit tests with the repro cases)
  - verified (2026-10-26 04:50+0100 by dell26/agent-2): ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' (Gradle Test enableAssertions=true, -ea) -> NoteMarkdownTests 35 pass incl. repro '**F:** \[x <c@d.e> \n**T:** ...', backslash hard break, entities (&amp; &#91; &nbsp;) next to bold/autolinks/hard breaks, plainText and titleOf exact-text asserts
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*'` passes
  - verified (2026-10-26 04:50+0100 by dell26/agent-2): cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.notes.*' -> BUILD SUCCESSFUL, 122 tests, 0 failures
- [x] mail-gtd's workaround is removed: `MailGtdService.neutralizeInlineLinks` keeps the mail's backslash escapes verbatim (no U+2216), its escape tests in `MailGtdWebTests` expect the original text, and `./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*'` passes
  - verified (2026-10-26 04:50+0100 by dell26/agent-2): grep for U+2216 in server/src -> none; neutralizeInlineLinks only spaces '[[' pairs; MailGtdWebTests escape tests expect original backslashes; ./gradlew test --tests 'ch.sonensei.cockpit.mail.gtd.*' -> BUILD SUCCESSFUL, 52 tests, 0 failures

## Notes
- Also check whether entity/numeric references (`&amp;`, `&#91;`, `&nbsp;`) next to bold/autolinks/hard breaks trip the same assertion (mail-gtd final review note).
- ASSUMPTION (2026-10-26 04:12+0100 by dell26/agent-2): NoteMarkdown.plainText now uses its own AST walk instead of flexmark's TextCollectingVisitor (no flexmark option fixes the miscount). Side effects judged improvements: table cells are space-separated (was 'DayPlace'), undefined reference brackets like [x] stay literal text, mail autolink addresses are kept in the search text, entities resolve correctly (&#91; -> '[' instead of '[[]').
- ASSUMPTION (2026-10-26 04:48+0100 by dell26/agent-2): titleOf now derives the title from the first non-blank line of plainText of the whole body (fix of TW-1): titles of notes starting with a table are its header cells, text inside a leading multi-line HTML block is no longer the title (consistent with search text); whole-body parse per title accepted (CR-1, P3) since notes are small
