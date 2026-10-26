# Test Report

Date: 2026-10-26

Task: notes-markdown-escapes - Robust plain text of note Markdown with backslash escapes

Scope: base `6c5a19ed8ba198ed0aa1dd900a4a8c79d36f800d` .. HEAD `5616eb9288a61c7d6d6626fcba2f924597e72186` (branch `main`), task commits `f51cf2c56` (code), `93e01d206` (tests and review reports), `5616eb928` (TW-1 fix: `titleOf` takes the first non-blank line of `plainText` of the whole body), as given by the parent worker and confirmed with `git log` / `git diff -U0 6c5a19ed8..HEAD -- server/src/main`. Production files changed: `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteMarkdown.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt`. Plus the uncommitted test additions of this re-run (see Coverage evidence). This report replaces the earlier one, which described the pre-fix revision.

Requirements:
- `tasks/notes-markdown-escapes.md` acceptance criteria and notes (entity references next to bold/autolinks/hard breaks)
- `reviews/notes-markdown-escapes/fixes.md` (TW-1 fix to verify for regressions)
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"
- Parent worker's regression list for `titleOf`: multi-line blocks, setext headings, fenced code at the start, lists, quotes, tables, long first lines

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteMarkdownTests.kt` (this re-run)
- `server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteMarkdownTests.kt`, `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt` (earlier run, committed in `93e01d206`)

## Tests added or updated

Count: 6 added in this re-run (all in `NoteMarkdownTests`); no existing test changed. The earlier run's 16 tests (15 in `NoteMarkdownTests`, 1 in `MailGtdWebTests`) stay unchanged.

Added (regression tests for the new `titleOf`):
- `the title of a multi-line block is its first line, with formatting spanning lines resolved` (emphasis and a link spanning two lines, a block quote with an escape, an ordered list with an entity)
- `the title of a setext heading is the heading text without its underline` (`===` and `---`, leading blank lines, escape)
- `the title of a body starting with a code block is the first code line, verbatim` (fenced with info string, empty fence followed by text, indented code; escapes/entities stay verbatim; the info string is not the title)
- `the title skips HTML comments, HTML blocks and reference definitions spanning lines` (multi-line HTML comment, reference definition then a paragraph using it)
- `the title of a table is its header row with cells separated`
- `a long first line of a multi-line body is cut, the following lines are not appended` (cut at a space is trimmed, exactly 100 characters is not cut, the second line never leaks into the title)

All tests run under Gradle's default `enableAssertions = true`, so a flexmark length `AssertionError` would fail them.

Behavior changes of the TW-1 fix observed while testing (not defects, consistent with `plainText`): a table's title is now its header cells (`Day Place`) instead of the raw `| Day | Place |` row; formatting spanning lines (`*a\nb*`, `[a\nb](url)`) no longer leaves stray `*`/`[` in the title; a reference used in the first line now resolves when its definition is further down; text inside a multi-line HTML block (a `div` opened on the first line and closed on the third, with `Hello` between) no longer becomes the title (it is also absent from the search text). The last one is not asserted by a test; the parent may want to note it.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: plainText/titleOf with escapes mixed with bold, autolinks and hard breaks neither throws (-ea) nor drops/garbles text | repro tests, all earlier `NoteMarkdownTests` additions incl. `the title of a line ending in a backslash hard break has no stray backslash` (TW-1), the 6 `titleOf` regression tests above; `MailGtdWebTests` `to-note with escapes and entities ... indexes ungarbled search text` | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.notes.*' --tests 'ch.sonensei.cockpit.mail.gtd.*' jacocoTestReport` | Pass |
| AC1 (notes): entity/numeric references next to bold/autolinks/hard breaks | `entity and numeric references ...`, `entities at the start and end of lines, blocks and the body resolve, unknown ones stay` | same | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.notes.*'` passes | whole `notes` package (8 classes, 122 tests incl. 35 in `NoteMarkdownTests`) | same | Pass |
| AC3: mail-gtd keeps escapes verbatim (no U+2216), tests expect the original text, `mail.gtd.*` passes | escape tests in `MailGtdWebTests` (47 tests), `MailGtdMailServerTests` (5 tests) | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.notes.*' --tests 'ch.sonensei.cockpit.mail.gtd.*' jacocoTestReport`

Result:

Pass: BUILD SUCCESSFUL, 10 test classes, 174 tests, 0 failures, 0 errors, 0 skipped (`NoteMarkdownTests` 35, `MailGtdWebTests` 47); ktlint (main, test, scripts) clean. A first attempt in this run failed only on ktlint (one test line over 140 characters), which was fixed before the measured run.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run, on the parent worker's instruction: the full server suite takes about 16 minutes and fails on this machine for environmental reasons (`Disk quota exceeded` in the backup round-trip and 60 MB file tests, see `fixes.md`). Neither web nor Android code is touched by the task.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` includes the full server `./gradlew check` (see above); left to the parent worker before `done`.

## Bugs found

None in this re-run. TW-1 (from the earlier run: `titleOf` kept the backslash of a hard line break) is fixed in `5616eb928`; its test `the title of a line ending in a backslash hard break has no stray backslash` passes, and the 6 regression tests above found no regression from the fix.

## Coverage

Status: verified - fresh JaCoCo run on the final code plus the new tests, test command exit 0.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by intersecting JaCoCo XML per-line entries with the added lines of `git diff -U0 6c5a19ed8..HEAD` (script `.tmp/tw-changed-lines.py`, Git-ignored): 32/32 executable changed lines covered (NoteMarkdown.kt 31/31, MailGtdService.kt 1/1); branches on those lines 51/54 (the 3 missed are at NoteMarkdown.kt:98 and :124, below). Removed and non-executable lines (imports, KDoc) are not in the denominator.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `notes-markdown-escapes`, base `6c5a19ed8ba198ed0aa1dd900a4a8c79d36f800d` (from parent), HEAD `5616eb9288a61c7d6d6626fcba2f924597e72186`; task commits `f51cf2c56`, `93e01d206`, `5616eb928` (from parent, confirmed with `git log`); file scope from `git diff 6c5a19ed8..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `5616eb928` plus uncommitted `NoteMarkdownTests.kt` (git blob `6bcfdcf626f64f3839e458d223c446e4a43b14ee`, sha256 `947d59e537405729a3d2a89620c7d19aeef686bfd1e08150f510c78c3e1cc9a6`); unchanged blobs: `MailGtdWebTests.kt` `4e68dab8a178281446abffa2c986a440b6f95b49`, `NoteMarkdown.kt` `6e4b0d6998440fde9aaf68d24a2cb9bce5654bb6`, `MailGtdService.kt` `e076861b02be19e8dd061493feda99ba473de2cd`.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteMarkdown.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt`. Excluded: test files, `reviews/` and `tasks/` files (not production code). No renamed or deleted files. Both files present in the report; none missing.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (project default version, no `toolVersion` set), Gradle 9.8.1, JDK 21.0.12.1; `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.notes.*' --tests 'ch.sonensei.cockpit.mail.gtd.*' jacocoTestReport` in `server/`; selection: packages `notes` and `mail.gtd` (174 tests).
- Run start/completion time and exit status: test suites ran 2026-10-26T04:46:26+01:00 to 04:46:50+01:00, `test.exec` written 04:46:55, XML report 04:46:56; exit 0 (BUILD SUCCESSFUL).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 `8b1d9dbdc1175cf336c0e9093795594eb7783f26938541782ffc9e726443cf2f`; `server/build/jacoco/test.exec` sha256 `d76450869454a89e5d2911388ff8602db5fdd69806689698d721d4ac2c93a234`.
- Evidence outputs are fresh and inputs stayed unchanged: yes; Gradle recompiled the edited test class and reran `test` and `jacocoTestReport` in this invocation, the exec/report timestamps are from it, and the test file hash after the run equals the measured snapshot (last edit before the run).
- Final run after latest test additions and production fixes: yes (after the TW-1 fix `5616eb928` and after the 6 new tests).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION, Functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteMarkdown.kt` | 500/512 (97.7%) | 63/68 (92.6%) | 6/6 (100.0%) | 61/61 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt` | 1350/1373 (98.3%) | 115/126 (91.3%) | 40/41 (97.6%) | 148/148 (100.0%) |
| Aggregate (summed counters, both files, none missing or excluded) | 1850/1885 (98.1%) | 178/194 (91.8%) | 46/47 (97.9%) | 209/209 (100.0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteMarkdown.kt:98` (1 of 4 branches) | `first == null \|\| last == null` of an undefined reference: "first child present, last child null" cannot occur | Justified gap (unreachable); childless (`![]`, `![][nope]`) and with-children cases are tested |
| `NoteMarkdown.kt:124` (2 of 8 branches) | short-circuit variants of `verbatim \|\| node is Code \|\| node is CodeBlock \|\| node is FencedCodeBlock`: a `CodeBlock` only occurs under a fenced/indented block, where `verbatim` is already true or the indented arm applies | Justified gap; verbatim fenced, indented and inline code is tested, also inside quotes, lists and at the start of a title |
| `NoteMarkdown.kt:66` (2 branches, instructions) | `inlineLinks` invalid-UUID fallback: pre-existing, not changed by this task | Outside task diff; no test added |
| `MailGtdService.kt:106,122,123,126,159,233,234,278,280` | pre-existing Kotlin null-safety branches, not changed by this task; the changed `neutralizeInlineLinks` (line 264) is covered | Outside task diff; no test added |

Compared with the pre-fix run: the old `titleOf` line-mapping lambda and its null branch are gone (NoteMarkdown functions 7 -> 6, lines 62 -> 61); the new `titleOf` has no uncovered branch.

## Unable to test

- Nothing required by the acceptance criteria. The full server suite and `.taskcheck` were not run here (see above); the parent worker runs `.taskcheck` before `done`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. TW-1 is fixed and the `titleOf` change shows no regression for multi-line blocks, setext headings, leading code blocks, lists, quotes, tables, HTML comments, reference definitions and long first lines.
