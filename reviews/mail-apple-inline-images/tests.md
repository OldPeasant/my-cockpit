# Test Report

Date: 2026-11-28

Task: mail-apple-inline-images - Mail: show unreferenced inline images and text after images (Apple Mail)

Scope: round 3, final code revision e1a9d906cd73aff64970ba71c6f2b9a82b999bb6. Task diff `git diff origin/main HEAD` (origin/main = 037f9dbc12bf52d1e215fead659dfc2f7bad1ccc; HEAD = e90c61686a7665f6f6b5cbbaa519dd51fa732462, a merge of `origin/wip/mail-apple-inline-images` whose server tree equals e1a9d906c). Task server commits (`git log origin/main..HEAD -- server`): 8b79b2aab implementation, fd0fe2b85 tests, 9bc21541d test-writer round 1, 9cd4f40f9 TW-1 fix, 0e3681d49 test-writer round 2, e1a9d906c CR-1 fix. Production files: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt` (new in this round: CR-1 fix, outside the task's touches, recorded as an assumption), `server/src/main/resources/db/migration/mail/V202611280930__mail_reparse_bodies.sql`. The only production change since round 2 is e1a9d906c: `DraftService.respond` in FORWARD mode removes `img` elements whose `src` is a synthetic `cid:cockpit-part-N@cockpit.invalid` reference (`withoutPositionedImages`), real `cid:` images stay. Uncommitted at measurement: only `DraftSendGreenMailTests.kt` (one test added in this round).

Requirements:
- `tasks/mail-apple-inline-images.md` acceptance criteria, Notes ("check after the change that forwarding such a mail now lists the image as attachment") and the recorded assumptions (including the CR-1 assumption)
- `tasks/mail-forward-positioned-images.md` criteria, used as the specification of the CR-1 fix (forward of an Apple Mail image without Content-ID and of a plain-text mail with an inline image: no `cid:cockpit-part-` in the quoted HTML, image still forwarded as attachment; real `cid:` images unchanged)
- `reviews/mail-apple-inline-images/review.md` CR-1
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage")
- parent instructions: round 3 (check the CR-1 delta tests, add only for a real gap, run the named Gradle commands, coverage)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MimeContentTests.kt` (round 1: 11 tests added, one parameterized with 4 cases; round 2: 1 test added)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailReadServiceInlineImagesTests.kt` (round 1: new, 4 tests; round 2: 1 test added)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftSendGreenMailTests.kt` (round 3: 1 test and 1 fixture added; the fixer added 2 tests in e1a9d906c)

## Tests added or updated

Count: 18 in total by the test-writer (round 1: 15, round 2: 2, round 3: 1). No existing test changed or weakened.

Round 3 review of the CR-1 delta tests (fixer, e1a9d906c, both in DraftSendGreenMailTests, GreenMail IMAP/SMTP through the REST API):
- `a forward drops the positioned image reference from the html and forwards the image as an attachment`: Apple Mail structure (HTML part plus inline JPEG without Content-ID); the forward HTML keeps the text and has no `cid:cockpit-part-`/`cockpit.invalid`, the attachment list is exactly `image0.jpeg`, and the draft stored on the IMAP server has the JPEG and no synthetic reference in any text/html leaf. Fails without the fix (the old code quoted the sanitized HTML unchanged).
- `a forward keeps a real cid image next to a dropped positioned image`: related HTML with a `cid:` logo plus a positioned JPEG; the logo `img` is kept byte for byte, the positioned one is removed, both files are attached. This pins that the filter removes only synthetic references.
- The existing `a reply drops inline cid images, a forward keeps them as inline parts` still covers the forward branch with `cid:` content but no positioned image (`withoutPositionedImages` returns the HTML unchanged).

Gap found and closed in round 3: the second case named by the CR-1 specification, a plain-text-only mail with an inline image, had no compose test. Its read body is HTML built by MimeContent (escaped text plus a positioned image), so both the reply branch (`withoutInlineImages`) and the forward branch see a synthetic reference. Added:
- DraftSendGreenMailTests `a reply and a forward of a plain-text mail with a positioned image quote its text without the image reference`: the reply HTML keeps the text, has no `cockpit-part-` and no attachments; the forward HTML keeps the text with the HTML-looking text still escaped (`&lt;b&gt;bring a chair&lt;/b&gt;`), has no `cockpit-part-`, attaches exactly `image0.jpeg`, and the stored draft on the IMAP server has the JPEG and no synthetic reference in its HTML. Passes on e1a9d906c.

Round 2 (unchanged): MimeContentTests `cid references with named, hex or bracket character references, also mixed with percent escapes, stay inline`; MailReadServiceInlineImagesTests `a cid reference written with character references shows its image once, through the inline download`.

Round 1 (unchanged), MimeContentTests:
- `cid references that are percent-encoded, bracketed or in another case keep their images inline, shown once` (also `+` and a malformed `%` escape)
- `a cid reference written with HTML character references keeps its image inline, shown once` (TW-1)
- `a cid image in the related HTML variant of an alternative stays inline and is not appended`
- `an alternative without an HTML variant is shown as its escaped plain text next to HTML parts`
- `alternatives with only one text variant still frame a positioned image`
- `the size limit applies per text part, so joined text may exceed it`
- `unreferenced inline raster images of every shown type are put into the body` (png, gif, webp, upper-case JPEG)
- `SVG and raster parts with attachment disposition are listed but not put into the body`
- `the file name of a positioned image is escaped in its alt text`
- `an image before the first text and text with CRLF line ends keep their order and breaks`
- `partImage references round-trip and only well-formed part ids are accepted`

Round 1 (unchanged), MailReadServiceInlineImagesTests: `the reported Apple Mail message shows its image through the inline download, after the text`, `a forged part reference only resolves to a raster part no cid references`, `a mail forging part references to its own PDF or cid image shows neither`, `a plain-text-only mail with an image gets an escaped HTML body with the image below the text`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 reported Apple structure: image listed `inline = false`, img element to inline download | MimeContentTests `the reported Apple Mail structure ...`; MailReadServiceInlineImagesTests `the reported Apple Mail message shows its image ...`; MailReadGreenMailTests `an Apple Mail inline image without Content-ID ...` | `cd server && ./gradlew test --tests '*MimeContentTests' --tests '*MailReadService*'`; `cd server && ./gradlew integrationTest --tests '*MailReadGreenMailTests' --tests '*DraftSendGreenMailTests'` | Pass |
| AC2 cid image in related unchanged: inline, at cid position, not listed, not appended | MimeContentTests `mixed, related and alternative parts ...`, `cid references that are percent-encoded ...`, `a cid image in the related HTML variant ...`, `a cid reference written with HTML character references ...`, `cid references with named, hex or bracket character references ...`; MailReadServiceInlineImagesTests `a cid reference written with character references ...`; MailReadGreenMailTests `the body is fetched ...` | same two commands | Pass |
| AC3 text after an image, plain-only and alternatives, `textPlain` with both parts | MimeContentTests `text after an image ... plain only`, `... as alternatives`, `an alternative without an HTML variant ...`, `alternatives with only one text variant ...`, `an image before the first text ...` | `cd server && ./gradlew test --tests '*MimeContentTests' --tests '*MailReadService*'` | Pass |
| AC4 plain-only mail + image: escaped text, image below, no injection | MimeContentTests `text after an image ... plain only`, `the file name of a positioned image is escaped ...`; MailReadServiceInlineImagesTests `a plain-text-only mail with an image ...` | same unit command | Pass |
| AC5 cached bodies re-parsed via migration | MailReadGreenMailTests `a body cached without its attachment row ...` (migration applied by Flyway in the Postgres test context) | `cd server && ./gradlew integrationTest --tests '*MailReadGreenMailTests' --tests '*DraftSendGreenMailTests'` | Pass (MailReadGreenMailTests 9/9) |
| AC6 non-raster/unknown inline parts listed, never img element | MimeContentTests `a plain-only mail with a non-raster inline part ...`, `a non-raster inline part next to HTML ...`, `SVG and raster parts with attachment disposition ...`; MailReadServiceInlineImagesTests `a forged part reference only resolves ...`, `a mail forging part references ...` | same unit command | Pass |
| AC7 unit tests + GreenMail test; `./gradlew check` green; OpenAPI unchanged | all of the above; OpenApiContractTest in the full suite | `cd server && ./gradlew check jacocoTestReport --continue` | Pass: 5321 tests, 0 failures, 0 errors, 0 skipped; ktlint main and test Pass |
| Note / CR-1: forwarding an Apple Mail image without Content-ID lists the image as attachment and quotes no synthetic reference | DraftSendGreenMailTests `a forward drops the positioned image reference ...` | `cd server && ./gradlew integrationTest --tests '*MailReadGreenMailTests' --tests '*DraftSendGreenMailTests'` | Pass |
| CR-1: real `cid:` images in forwards keep today's behavior | DraftSendGreenMailTests `a forward keeps a real cid image next to a dropped positioned image`, `a reply drops inline cid images, a forward keeps them as inline parts` | same integration command | Pass |
| CR-1: plain-text mail with an inline image, reply and forward quote no synthetic reference, forward attaches the image | DraftSendGreenMailTests `a reply and a forward of a plain-text mail with a positioned image quote its text without the image reference` (round 3) | same integration command | Pass (DraftSendGreenMailTests 18/18) |
| Note: maxTextSize per text part, joined text may exceed it | MimeContentTests `the size limit applies per text part ...` | same unit command | Pass |
| Assumption: synthetic references resolve only to raster, non-cid-referenced parts of the same message | MailReadServiceInlineImagesTests `a forged part reference only resolves ...`, `a mail forging part references ...`; MimeContentTests `partImage references round-trip ...` | same unit command | Pass |

## Focused test execution

Command:

`cd server && ./gradlew integrationTest --tests '*MailReadGreenMailTests' --tests '*DraftSendGreenMailTests'` (BUILD SUCCESSFUL in 1m 8s), then `cd server && ./gradlew test --tests '*MimeContentTests' --tests '*MailReadService*'` (BUILD SUCCESSFUL in 6s)

Result:

Pass. Result files (`server/build/test-results/...`): DraftSendGreenMailTests 18 tests, 0 failures, 0 errors, 0 skipped (timestamp 2026-11-28T10:26:14Z, includes the three positioned-image tests and the existing cid test); MailReadGreenMailTests 9/9 (10:25:56Z); MimeContentTests 60/60 (10:27:23Z); MailReadServiceInlineImagesTests 5/5; MailReadServiceInInboxTests 4/4 (also matched by the filter).

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport --continue`, then `cd server && ./gradlew runKtlintCheckOverTestSourceSet --rerun` (the check reported `ktlintTestSourceSetCheck` UP-TO-DATE, so ktlint was forced over the changed test sources)

Result:

Pass: BUILD SUCCESSFUL in 3m 32s; `test` 5321 tests, 0 failures, 0 errors, 0 skipped (summed over `server/build/test-results/test/*.xml`); ktlint main and test source sets Pass (forced rerun BUILD SUCCESSFUL). The `integrationTest` task is not part of `check`; its two GreenMail classes ran above. Web and Android were not run: the task changes the server only.

## .taskcheck

Result:

Not run by the test-writer: `scripts/check.sh` lints and tests all three parts; its server part (`./gradlew check`) was run above and passes. The parent runs `.taskcheck` before `done`.

## Bugs found

### A cid reference written with HTML character references is treated as unreferenced (RESOLVED)

Finding ID:

`TW-1`

Test:

`MimeContentTests` > `a cid reference written with HTML character references keeps its image inline, shown once`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt` `cidReferences` (lines 245-260)

Requirement:

AC2: a `cid:`-referenced image in `multipart/related` stays `inline = true`, is shown at the `cid:` position, is not listed and not appended a second time.

Expected behavior:

`<img src="cid:pic&#64;example.test">` references Content-ID `pic@example.test`, so the part stays `inline = true` and the HTML body is unchanged.

Actual behavior:

Round 1: listed `inline = false` and shown twice. Fixed in 9cd4f40f9 (formerly 70f2f5745); the TW-1 test and the round-2 tests pass on e1a9d906c.

Severity:

Low

Fix required:

No longer required: resolved and verified by the tests above.

No new production defects found in round 3. The CR-1 fix behaves as specified in all tested cases (HTML with a positioned image, related `cid:` image plus positioned image, plain-text mail with a positioned image, reply and forward).

## Coverage

Status: verified for `MimeContent.kt` and `MailReadService.kt` (fresh run, exit 0). Partial for `DraftService.kt`: measured by the configured report, but its changed lines are exercised only by the GreenMail tests of the `integrationTest` task, which the configured `jacocoTestReport` does not read (see below).

Metric: whole-file coverage of task-changed production files (unit `test` task only, as configured in `docs/ARCHITECTURE.md`).
Changed-line coverage: not measured with tooling (none configured). Manual check against the JaCoCo line data: all lines of the TW-1 fix (`MimeContent.kt` 245-260) are covered; all lines added to `MailReadService.kt` are covered (143/143); the CR-1 lines of `DraftService.kt` (310-311, 462-475) show 0 covered instructions in the unit report, because `DraftService.respond` is only tested through GreenMail. Behaviorally they are executed by three passing `integrationTest` tests (forward with a positioned image, forward with a positioned and a real `cid:` image, reply and forward of a plain-text mail with a positioned image), and the early-return path (cid content without positioned image) by `a reply drops inline cid images, a forward keeps them as inline parts`.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-apple-inline-images; base origin/main 037f9dbc12bf52d1e215fead659dfc2f7bad1ccc (given by the parent as `git diff origin/main HEAD`, equal to `git merge-base HEAD origin/main`); measured HEAD e90c61686a7665f6f6b5cbbaa519dd51fa732462 (merge of the wip branch, final code revision e1a9d906cd73aff64970ba71c6f2b9a82b999bb6); task server commits from `git log origin/main..HEAD -- server` (listed under Scope).
- Source/test snapshot identifier (including uncommitted inputs): sha256 MimeContent.kt 46f5815b545d1c6a56d5a55a37165d4b70874fa76faaf8791a4d5d3cf1df2427, MailReadService.kt be5830a29e5f2e05114208275d9beaae90165913c6f290830090ac30ac68db24, DraftService.kt c58c551aae796f5d8c62a3d0a70eaeb23efc4f7139933c44b2e7d5aeb6877321, V202611280930__mail_reparse_bodies.sql 0299aa062a5b0834a498112ef279bd87a07a2c3847f31bbe4a10776f4a4f8ed6, MimeContentTests.kt 52b7eb428c3ddda0e9b74791590844a0f2739b5c23d6f0db8d328389ba69d5e9, MailReadServiceInlineImagesTests.kt 74b38ee84b2070efbc5745894054500d888687b578f27b5b3365acb56655d922, DraftSendGreenMailTests.kt (uncommitted, with the round-3 test) bd77c1d6c922e5e69a3147d86f4a402c30cd7c933d917cc15db58817777b25c9; identical before the first run (11:25:45+01:00) and after the last run.
- Exact included files; renamed/deleted/excluded files and reasons: included `MimeContent.kt`, `MailReadService.kt`, `DraftService.kt`. Excluded `V202611280930__mail_reparse_bodies.sql` (SQL migration, not instrumentable by JaCoCo; covered behaviorally by the passing GreenMail re-read test). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (project default version), Gradle 9.8.1; `./gradlew check jacocoTestReport --continue`; working directory `server/`; all `test` task tests. The plugin also writes `server/build/jacoco/integrationTest.exec` for the GreenMail run, but no configured report task reads it, and changing the coverage configuration is outside the test-writer's authority.
- Run start/completion time and exit status: integration run 11:25:45 to about 11:27 (exit 0); full check started after 11:27:30, `test.exec` written 11:31:02, report written 11:31:04; exit 0 (BUILD SUCCESSFUL in 3m 32s).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 0f8090a76191d1b7031b1703452be9b1261b60d83be84bbf95ce4885d11cebe0; `server/build/jacoco/test.exec` sha256 a204432bf8d9cf87255ff85764699daa71f48150264fec6dcfb575d7080bf117; `server/build/jacoco/integrationTest.exec` (not reported) sha256 c3174f7a81ffa21b7f688bc081e8a6deb260477e6a84d073a49ac05cc67d086e.
- Evidence outputs are fresh and inputs stayed unchanged: yes (the `test` task reran because its filter and test sources changed; exec and report written during this run; input hashes re-checked afterwards).
- Final run after latest test additions and production fixes: yes (on e1a9d906c with the round-3 test).

Values below use covered/total (percentage); JaCoCo has no separate statement counter, instructions are shown instead.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt` | 1478/1506 (98.1%) | 184/194 (94.8%) | 53/53 (100%) | 190/192 (99.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt` | 959/964 (99.5%) | 82/84 (97.6%) | 39/39 (100%) | 143/143 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt` (unit tests only; GreenMail coverage not reported) | 453/2978 (15.2%) | 31/268 (11.6%) | 36/90 (40.0%) | 73/364 (20.1%) |
| Aggregate of the two `mail/api` files (summed counters; migration excluded) | 2437/2470 (98.7%) | 266/278 (95.7%) | 92/92 (100%) | 333/335 (99.4%) |
| Aggregate of all three measured files (summed counters; migration excluded; DraftService unit-only) | 2890/5448 (53.0%) | 297/546 (54.4%) | 128/182 (70.3%) | 406/699 (58.1%) |

The low DraftService numbers reflect that the whole compose service is tested in the `integrationTest` task (GreenMail), not a lack of tests for this task; only about 18 of its 364 lines belong to this task.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `DraftService.kt:302-320, 462-475` (`respond` and `withoutPositionedImages`, unit report) | CR-1: forward and reply drop synthetic references, keep real `cid:` images | exercised by 4 passing DraftSendGreenMailTests in `integrationTest` (round 3 added the plain-text case); not visible in the configured unit report; extending the JaCoCo report to `integrationTest.exec` would need a build change, left to the parent |
| `MimeContent.kt:136` (multipart whose content is no `Multipart`) | defensive, pre-existing behavior | not reachable with parsed or IMAP messages; left |
| `MimeContent.kt:181` (`plainItems` on an `Html` item) | defensive: plain rendering only happens when no HTML exists | unreachable in the current flow; left |
| `MimeContent.kt:220` (`render` on an `Alternative` item) | defensive: items are flattened before rendering | unreachable; left |
| `MimeContent.kt:93-94` (short-circuit branches in the part lookup by id) | malformed or out-of-range part references | covered by `partImage references round-trip ...` and the forged-reference tests; remaining branches are bytecode null paths |
| `MimeContent.kt:219` (one branch) | alt text of a positioned image | null and non-null file names are both tested; remaining branch is generated by string concatenation |
| `MimeContent.kt:269, 271, 293` | encoding/content-id/text fallbacks in `describe` and `text` | pre-existing code, not changed by this task |
| `MailReadService.kt:85, 99` | list paging | pre-existing code, not changed by this task |

## Unable to test

- Unit-report coverage of the CR-1 lines in `DraftService.kt`: the configured `jacocoTestReport` reads only the `test` task; the lines are executed by passing GreenMail tests. Follow-up only if the project wants integration coverage in the report (build change).
- The OpenAPI contract is only checked through the existing OpenApiContractTest (passes in the full run); no schema change was expected.
- Client behavior (web/Android listing the now `inline = false` image, the compose editor showing the forward) is out of this task's server-only scope and was not tested.
- Observation, no failing test (unchanged): `cidReferences` also counts a `cid:` text that is not an image `src` attribute (for example a CSS `url(cid:...)`); such a part stays `inline = true` and, if the sanitizer removes that reference, is neither shown nor listed. Same as before the task, so no regression.
- Observation, no failing test: `withoutPositionedImages` removes only `img` elements; a synthetic reference can only appear as an `img src` because MimeContent generates it that way, and a forged synthetic reference written by the sender in a non-`img` attribute does not resolve to anything on the read side either. No action needed.

## Summary

Tests added and all validation passed. The CR-1 fix (e1a9d906c) is covered by the fixer's two GreenMail tests plus one round-3 test for the plain-text mail case (reply and forward), all passing; no new production defects. `./gradlew check` passes with 5321 tests and 0 failures, the GreenMail classes pass (DraftSendGreenMailTests 18/18, MailReadGreenMailTests 9/9), and task-scoped coverage is recorded for the final revision. The CR-1 lines in `DraftService.kt` do not show up in the unit coverage report because only the GreenMail tests run them.
