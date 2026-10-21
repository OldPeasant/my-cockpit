---
title: Robust plain text of note Markdown with backslash escapes
status: todo
owner:
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteMarkdown.kt,server/src/test/kotlin/ch/sonensei/cockpit/notes]
prio: 2
---
## Goal
`NoteMarkdown.plainText` (flexmark `TextCollectingVisitor`) miscounts Markdown backslash escapes next to bold text,
autolinks and hard line breaks: with JVM assertions on (tests) it throws `AssertionError: length:N != computeLength(): M`
(note create/update returns 500), without them it yields garbled search text/titles (e.g. `F:** [x`). Repro:
the Kotlin string `"**F:** \\[x <c@d.e>  \n**T:** \\[y <c@d.e>"` or a header like the one mail-gtd's to-note builds with `\[` in a
display name. Found in mail-gtd, which works around it by writing mail escapes as U+2216.

## Acceptance criteria
- [ ] plainText/titleOf of Markdown with escapes mixed with bold, autolinks and hard breaks neither throws (tests run with -ea) nor drops/garbles text (unit tests with the repro cases)
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*'` passes

## Notes
