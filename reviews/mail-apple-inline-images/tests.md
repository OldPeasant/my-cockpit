# Test Report

Date: 2026-11-28

Task: mail-apple-inline-images - Mail: show unreferenced inline images and text after images (Apple Mail)

Scope: `git diff 10e5329f19cb29feec8f8ee78e81143e63dc8569..f69ba18fab753182c51b5d80712c9b2de56ea2f0` (task commits 20a91330a, f69ba18fa on branch main; the parent named them b99aba389 and 5f824414d before a rebase, same content). Production files: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt`, `server/src/main/resources/db/migration/mail/V202611280930__mail_reparse_bodies.sql`. During the work the parent rebased the task commits (now 9885f288f, 702b5f1ff, review report 600039fcb); `git diff f69ba18fa 600039fcb -- server` is empty, so the measurement applies to the current HEAD unchanged. Uncommitted at measurement: only the test changes listed below (plus an unrelated untracked `tasks/mail-forward-positioned-images.md`, not part of the measurement).

Requirements:
- `tasks/mail-apple-inline-images.md` acceptance criteria, Notes and the two recorded assumptions
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage")
- parent instructions (forged part references, cid in related not duplicated, percent-encoded cid, size limit per text part)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MimeContentTests.kt` (11 tests added, one of them parameterized with 4 cases)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailReadServiceInlineImagesTests.kt` (new, 4 tests)

## Tests added or updated

Count: 15 (MimeContentTests: 11, MailReadServiceInlineImagesTests: 4). No existing test changed.

MimeContentTests:
- `cid references that are percent-encoded, bracketed or in another case keep their images inline, shown once` (also `+` and a malformed `%` escape)
- `a cid reference written with HTML character references keeps its image inline, shown once` (FAILS, TW-1)
- `a cid image in the related HTML variant of an alternative stays inline and is not appended`
- `an alternative without an HTML variant is shown as its escaped plain text next to HTML parts`
- `alternatives with only one text variant still frame a positioned image`
- `the size limit applies per text part, so joined text may exceed it`
- `unreferenced inline raster images of every shown type are put into the body` (png, gif, webp, upper-case JPEG)
- `SVG and raster parts with attachment disposition are listed but not put into the body`
- `the file name of a positioned image is escaped in its alt text`
- `an image before the first text and text with CRLF line ends keep their order and breaks`
- `partImage references round-trip and only well-formed part ids are accepted`

MailReadServiceInlineImagesTests (MailReadService.read with mocked locator/loader and the real HtmlMailSanitizer):
- `the reported Apple Mail message shows its image through the inline download, after the text`
- `a forged part reference only resolves to a raster part no cid references` (PDF, cid-referenced PNG, SVG, unknown and malformed part id lose their src; non-referenced JPEG/BMP resolve)
- `a mail forging part references to its own PDF or cid image shows neither` (end to end from a parsed MIME message)
- `a plain-text-only mail with an image gets an escaped HTML body with the image below the text`

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 reported Apple structure: image listed `inline = false`, `<img>` to inline download | MimeContentTests `the reported Apple Mail structure ...` (existing); MailReadServiceInlineImagesTests `the reported Apple Mail message shows its image ...`; MailReadGreenMailTests `an Apple Mail inline image without Content-ID ...` (existing) | `cd server && ./gradlew test --continue` (full) and `./gradlew integrationTest --tests '*MailReadGreenMailTests'` | Pass |
| AC2 cid image in related unchanged: inline, at cid position, not listed, not appended | MimeContentTests `mixed, related and alternative parts ...` (existing), `cid references that are percent-encoded, bracketed or in another case ...`, `a cid image in the related HTML variant of an alternative ...`; `a cid reference written with HTML character references ...` | `cd server && ./gradlew test --tests '*MimeContentTests'` | Pass, except the HTML-character-reference case: Fail (TW-1) |
| AC3 text after an image, plain-only and alternatives, `textPlain` with both parts | MimeContentTests `text after an image ... plain only`, `... as alternatives` (existing), `an alternative without an HTML variant ...`, `alternatives with only one text variant ...`, `an image before the first text ...` | `cd server && ./gradlew test --tests '*MimeContentTests'` | Pass |
| AC4 plain-only mail + image: escaped text, image below, no injection | MimeContentTests `text after an image ... plain only` (existing), `the file name of a positioned image is escaped in its alt text`; MailReadServiceInlineImagesTests `a plain-text-only mail with an image ...` | full `./gradlew test` | Pass |
| AC5 cached bodies re-parsed via migration | MailReadGreenMailTests `a body cached without its attachment row ...` (existing; migration applied by Flyway in every Postgres test context) | `./gradlew integrationTest --tests '*MailReadGreenMailTests'` | Pass |
| AC6 non-raster/unknown inline parts listed, never `<img>` | MimeContentTests `a plain-only mail with a non-raster inline part ...`, `a non-raster inline part next to HTML ...` (existing), `SVG and raster parts with attachment disposition ...`; MailReadServiceInlineImagesTests `a forged part reference only resolves ...`, `a mail forging part references ...` | full `./gradlew test` | Pass |
| AC7 unit tests + GreenMail test; `./gradlew check` green; OpenAPI unchanged | all of the above; OpenApiContractTest in the full suite | full `./gradlew test --continue`, `./gradlew ktlintCheck` | Fail: full suite 5319 tests, 1 failed (TW-1); ktlint Pass; OpenApiContractTest Pass |
| Note: maxTextSize per text part, joined text may exceed it | MimeContentTests `the size limit applies per text part ...` | `./gradlew test --tests '*MimeContentTests'` | Pass |
| Assumption: synthetic references resolve only to raster, non-cid-referenced parts of the same message | MailReadServiceInlineImagesTests `a forged part reference only resolves ...`, `a mail forging part references ...`; MimeContentTests `partImage references round-trip ...` | full `./gradlew test` | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*MimeContentTests' --tests '*MailReadServiceInlineImagesTests'` and `cd server && ./gradlew integrationTest --tests '*MailReadGreenMailTests'`

Result:

Fail: MimeContentTests 59 tests, 1 failure (TW-1); MailReadServiceInlineImagesTests 4/4 pass; MailReadGreenMailTests 9/9 pass.

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport --continue` (all server unit tests, excluding tag `external`), plus `cd server && ./gradlew ktlintCheck`

Result:

Fail: 5319 tests completed, 1 failed (only TW-1). ktlintCheck: Pass. Web and Android were not run: the task changes the server only.

## .taskcheck

Result:

Not run: `scripts/check.sh` includes the server unit tests, which fail on the kept TW-1 test; the server part of the check was run directly (full `./gradlew test` and `ktlintCheck`, above). The parent runs `.taskcheck` after the fix.

## Bugs found

### A cid reference written with HTML character references is treated as unreferenced: image shown twice and listed as attachment

Finding ID:

`TW-1`

Test:

`MimeContentTests` > `a cid reference written with HTML character references keeps its image inline, shown once`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt:241` (`cidReferences`, regex over the raw, undecoded HTML) together with `MailReadService.resolveInlineImages` (Jsoup decodes the attribute)

Requirement:

AC2: a `cid:`-referenced image in `multipart/related` stays `inline = true`, is shown at the `cid:` position, is not listed and not appended a second time. Approach step 1: `inline = true` only if the HTML body references its Content-ID via `cid:`.

Expected behavior:

`<img src="cid:pic&#64;example.test">` references Content-ID `pic@example.test` (browsers and Jsoup read the attribute as `cid:pic@example.test`), so the part stays `inline = true` and the HTML body stays `<p><img src="cid:pic&#64;example.test"></p>`.

Actual behavior:

`cidReferences` matches the raw text `pic&#64;example.test`, so the part counts as unreferenced: it is listed with `inline = false` and a second `<div><img src="cid:cockpit-part-2@cockpit.invalid" alt="pic.png"></div>` is appended. After sanitizing, `MailReadService` resolves both the original (decoded by Jsoup) and the appended reference, so the image is shown twice. The same applies to any character reference in the src (`&amp;`, `&#x40;`, ...). Real mailers rarely write cids this way, so the impact is cosmetic (duplicate image plus an extra attachment entry), no security impact.

Severity:

Low

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. The coverage run exited non-zero only because of the kept TW-1 test (1 of 5319); the JaCoCo execution data of that same run was fresh and complete for all other tests, and the report was generated from it with `./gradlew jacocoTestReport -x test`. Treat the numbers as diagnostic until the fix makes the run green.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no changed-line tooling configured). All lines added in `MailReadService.kt` (positionedImage and its call) are covered, as the line counters below show.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-apple-inline-images; base 10e5329f19cb29feec8f8ee78e81143e63dc8569 (claim commit, given by the parent); HEAD f69ba18fab753182c51b5d80712c9b2de56ea2f0; task commits 20a91330a and f69ba18fa (`git log 10e5329f1..HEAD`, plus two `assume` commits touching only `tasks/`).
- Source/test snapshot identifier (including uncommitted inputs): sha256 MimeContent.kt 7888ec9da6240f69052b2555effd73f8c0732fd39b8af44389bc266661445992, MailReadService.kt be5830a29e5f2e05114208275d9beaae90165913c6f290830090ac30ac68db24, MimeContentTests.kt 5182dd1c5d477d991a235325012fc359ddad1c5d91f01f7d08b2c27fc792c905, MailReadServiceInlineImagesTests.kt edec84ef12ffd2510fcdfb61dcedfe1a4016ba451183f110fefdae60af4f260a; identical before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `MimeContent.kt`, `MailReadService.kt`. Excluded `V202611280930__mail_reparse_bodies.sql` (SQL migration, not instrumentable by JaCoCo; covered behaviorally by the GreenMail re-read test). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (project default version); `./gradlew test jacocoTestReport --continue`, then `./gradlew jacocoTestReport -x test`; working directory `server/`; all `test` task tests (tag `external` excluded, so the GreenMail tests do not contribute).
- Run start/completion time and exit status: started 2026-11-28T10:54:08+01:00, exec written 10:57:43, report written 10:57:57; test task exit 1 (TW-1 only, 5319 completed, 1 failed); report task exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 b56fd7a27a29047654dad878d67052602e63b745d98b428c4c0bc46687945451; exec `server/build/jacoco/test.exec` sha256 d2fca8dea040c23d24439f41aa8bf5cc54e51ef5a5bbff7d76f107b03f40d7d2.
- Evidence outputs are fresh and inputs stayed unchanged: yes (exec and report timestamps after the run start; input hashes re-checked after the run).
- Final run after latest test additions and production fixes: yes for the latest tests; no production fix yet (TW-1 open), so a fresh run is needed after the fix.

Values below use covered/total (percentage); JaCoCo has no separate statement counter, instructions are shown instead.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt` | 1455/1483 (98.1%) | 184/194 (94.8%) | 52/52 (100%) | 187/189 (98.9%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt` | 959/964 (99.5%) | 82/84 (97.6%) | 39/39 (100%) | 143/143 (100%) |
| Aggregate (summed counters, 2 measured files; migration excluded) | 2414/2447 (98.7%) | 266/278 (95.7%) | 91/91 (100%) | 330/332 (99.4%) |

(Before the tests of this report were added, MimeContent.kt was at 1442/1483 instructions, 180/194 branches, 186/189 lines in a run on the same production code.)

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MimeContent.kt:135` (multipart whose content is no `Multipart`) | defensive, pre-existing behavior | not reachable with parsed or IMAP messages; left |
| `MimeContent.kt:180` (`plainItems` on an `Html` item) | defensive: plain rendering only happens when no HTML exists | unreachable in the current flow; left |
| `MimeContent.kt:219` (`render` on an `Alternative` item) | defensive: items are flattened before rendering | unreachable; left |
| `MimeContent.kt:92-93` (one short-circuit branch in `partImageId`) | malformed synthetic references | behavior covered by `partImage references round-trip ...`; remaining branch is a bytecode null path |
| `MimeContent.kt:218` (one branch) | alt text of a positioned image | null and non-null file names are both tested; remaining branch is generated by string concatenation |
| `MimeContent.kt:262, 264, 286-287, 316` | size/encoding/content-id/media-type fallbacks in `describe`, `mediaType`, `text` | pre-existing code, not changed by this task |
| `MailReadService.kt:85, 99, 188-189` | list paging and copyAttachment output paths | pre-existing code, not changed by this task |

## Unable to test

- The OpenAPI contract is only checked through the existing OpenApiContractTest (passes in the full run); no schema change was expected.
- Client behavior (web/Android listing the now `inline = false` image, forwarding) is out of this task's server-only scope and was not tested.
- Observation, no failing test: `cidReferences` also counts a `cid:` text that is not an `<img src>` (for example a CSS `url(cid:...)` or plain text in the HTML). Such a part stays `inline = true` and, if the sanitizer removes that reference, is neither shown nor listed. This matches the behavior before the task (every inline part was hidden then), so it is no regression; mentioned for the parent to decide.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1, Low: a cid reference written with HTML character references makes the image appear twice and be listed as an attachment).
