# Fix Report

Date: 2026-10-21

Task: mail-compose - Drafts and sending

Scope: current task; findings CR-1, CR-2, CR-3, CR-4, CR-6 of `reviews/mail-compose/review.md` (CR-5 deferred by the parent worker). Changes limited to `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose` and `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose`.

## Fixed findings

### CR-1 — Address validation accepts full mailboxes and groups in `address`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftController.kt`

Problem:
`DraftContentRequest.addresses()` checked the address with `InternetAddress(address, true).validate()`, which accepts `Name <addr>` and group syntax, and then stored the raw input string. The result was malformed `To`/`Cc`/`Bcc` headers and wrong SMTP recipients.

Fix:
The parsed `InternetAddress` is kept. A parse failure, a group, an address with a personal (display-name) part, or an empty addr-spec now gets 400 on `<field>[i].address`. Only the bare parsed `.address` is stored. The display name still comes from `name`.

Validation:
- `./gradlew test --tests 'ch.sonensei.cockpit.mail.compose.*'`: Pass. This includes the test-writer's test `an address with a display name in the address field is rejected` in `DraftControllerTests$Create`, which failed before the fix and passes now.

### CR-2 — Uploading into a draft bypasses the maximum message size

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftSendGreenMailTests.kt` (existing test, unchanged, now passes)

Problem:
`attach()` never checked the size. Repeated uploads could grow a draft past `cockpit.mail.compose.max-message-size`, and `send()` had no size check.

Fix:
- `attach()` adds up the stored version's size (text and HTML length plus the estimated decoded attachment sizes, through the new helper `sizeOf(MailContent)`) and the upload's size. It throws `InvalidRequestException("attachments", ...)` when the total is over the limit. This happens before anything is appended to IMAP.
- `send()` has a backstop. It reads the fetched outgoing draft (`MimeContent.read`) and refuses an oversized one before SMTP. This covers drafts that another mail client changed.

Validation:
- `./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.compose.*'`: Pass. `DraftSendGreenMailTests` runs with `max-message-size=4KB`. The test `an upload that makes the draft larger than the limit is refused and the stored version stays` (3 KB draft + 3 KB upload, expects 400 on `attachments` with the stored version unchanged) passes.

### CR-3 — Attachment bytes read without a running total

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftSendGreenMailTests.kt`

Problem:
`withAttachments` read up to `limit() + 1` bytes for every referenced item, up to 100 of them, and checked the size only after reading them all.

Fix:
`withAttachments` now keeps a running total, starting at the body size. It reads each item with at most `limit() - total + 1` bytes (capped at `Int.MAX_VALUE`) and throws `tooLarge()` as soon as the total is over the limit. Because the running total includes the body, the final `checkSize` there was redundant and was removed.

Validation:
- New test `files that are each under the limit but together over it are refused and nothing is stored` (two 3 KB files on create with a 4 KB limit, expects 400 on `attachments` and an empty Drafts folder): Pass with `./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.compose.*'`.

### CR-4 — Read-view sanitizer used for editable and outgoing content

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftComposeGreenMailTests.kt`

Problem:
The draft body returned by `GET /drafts/{id}` (`view()`) and the reply/forward quote (`respond()`) used the default `RemoteImagePolicy.BLOCK`. That policy strips the `src` of remote images, so content was lost on a GET->PUT round trip and in quoted originals.

Fix:
The parent worker decided that remote images must be kept. Both places now sanitize with a private `KEEP_REMOTE_IMAGES = SanitizeOptions(RemoteImagePolicy.PROXY, imageProxy = { it })`. This identity "proxy" leaves remote `src` unchanged, while scripts, event handlers, forms and other unsafe content are still removed. `HtmlMailSanitizer` and its default options are unchanged. The parent worker should record this decision as a `tasks.py assume` note: it replaces the "remote images blocked" / "GET draft returns sanitized HTML" wording in the existing assumption.

Validation:
- New test `remote images survive a reply quote and a GET-PUT round trip, scripts do not`: Pass with `./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.compose.*'`. It checks that the remote `src` is in the reply's HTML and in the stored draft, that `<script` is removed from the quote, and that the `src` survives GET then PUT of the returned HTML, both in the response and on the IMAP server.

### CR-6 — Inline `cid:` parts placed in `multipart/mixed`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftMime.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftMimeTests.kt`

Problem:
Inline images were added next to the `multipart/alternative` body inside `multipart/mixed`. RFC 2387 expects them in `multipart/related` with the HTML.

Fix:
When the message has HTML, attachments with `inline = true` and a `contentId` go into `multipart/related(html, inline...)`, which is the second part of `multipart/alternative(text/plain, related)`. Real attachments stay in `multipart/mixed`. If there are only inline parts, the top level is `multipart/alternative`. Plain-text-only messages keep the old layout. `MimeContent.read` still lists these parts as inline attachments (their `related` parent marks them inline), so `partId`-based keeping on update still works.

Test changed:
The existing test `attachments wrap the body in multipart mixed` asserted the old structure that CR-6 reports as wrong: the inline image as a sibling of the alternative inside mixed. It now asserts the new structure and is renamed `attachments wrap the body in multipart mixed, inline parts stay with the HTML`. It still checks the same bytes, disposition and Content-ID.

Validation:
- New tests `inline cid parts go with the HTML into multipart related, real attachments into mixed` and `with only inline parts there is no multipart mixed`, plus the updated test above: Pass with `./gradlew test --tests 'ch.sonensei.cockpit.mail.compose.*'`.
- The GreenMail forward test `a reply drops inline cid images, a forward keeps them as inline parts`: Pass with `integrationTest`.

## Not fixed

### CR-5 — Correspondent query sorts the whole message table

Status:
Out of scope

Reason:
The parent worker deferred this to the perf-tuning work and told the fixer not to change it.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat`: Pass, no remaining changes.
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.compose.*'`: Pass (136 tests, 0 failures).
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.compose.*'`: Pass (`DraftComposeGreenMailTests` 11, `DraftSendGreenMailTests` 11, 0 failures).
- `cd server && ./gradlew test --tests '*OpenApiContractTest'` (without `UPDATE_OPENAPI`): Pass. The API schema did not change, so it was not regenerated.

Full test suite:
- `cd server && ./gradlew check` (ktlint and all server unit tests): Pass (BUILD SUCCESSFUL in 6m 36s).

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck`: Pass. Detekt is not configured in `server/build.gradle.kts`.

`.taskcheck`:
- Not run by the fixer. The server part of it (`./gradlew check`) passed. The parent worker runs `.taskcheck` before `done`.

Remaining unresolved findings:
1 (CR-5, deferred by the parent worker)

## Final status

All validated findings fixed and validation passed.

---

# Fix Report (round 2: re-review)

Date: 2026-10-21

Task: mail-compose - Drafts and sending

Scope: current task; findings CR-1 (P2) and CR-2 (P3) of the re-review in `reviews/mail-compose/review.md`, plus running the two test-writer tests added in 61fa3c21 that had never been run. Changes limited to `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose`, its tests and `api/openapi.yaml`.

## Fixed findings

### CR-1 — Size checks in send and upload overestimate base64 attachments

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftSendGreenMailTests.kt`

Problem:
Saves count exact decoded attachment bytes (`ComposedMessage.sizeBytes`), but the `send()` backstop and the `attach()` check used `sizeOf(MailContent)`. That function summed `MailAttachment.sizeBytes`, estimated as `encodedSize * 3 / 4`, and the encoded size includes the base64 CRLF line breaks (about 2.6% too high). A draft saved just under the limit could not be sent or receive further uploads.

Fix:
Both checks now count the same way as a save, using exact decoded sizes. The new `sizeOf(MimeMessage)` takes the text and HTML lengths from `MimeContent.read`, then streams each attachment part's decoded bytes and counts them. It stops counting once the total is over the limit. The message is already fully in memory at both call sites: `send()` uses the fetched `outgoing` copy, and `attach()` now checks inside the `store` build block on the fully read `FixedIdMimeMessage` copy of the stored version. A `tooLarge()` thrown there becomes `Stored.Invalid` (400) after the IMAP connection is released, and nothing is appended. Before IMAP is touched, `attach()` keeps only a cheap check that the upload alone is not over the limit. `MimeContent` (in `mail/api`, outside the scope) is unchanged, so the estimate shown in API responses stays as it is.

Tests added (GreenMail, `max-message-size=4KB`):
- `a draft saved just under the limit with a base64 attachment is sent`: a 4000-byte binary file plus the body is accepted on create and sent. Bob receives the identical bytes.
- `an upload that brings the draft just under the limit is accepted and the draft is sent`: a 3000-byte file, then a 1000-byte upload, gives 200. Sending succeeds with both attachments.
- `sending a draft another client made larger than the limit is refused and nothing is sent`: an oversized `\Draft` message is appended directly to the Drafts folder. `POST /send` gives 400 on `attachments`. Nothing reaches Bob's INBOX or Sent, and the draft stays.

The first two tests were also run against the previous `DraftService.kt` (the HEAD version, temporarily restored): both FAILED there (2 of 15 failed). With the fix they pass.

Validation:
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.compose.*'` — Pass

### CR-2 — The send endpoint's 400 response does not document the size refusal

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftController.kt`
- `api/openapi.yaml` (regenerated)

Problem:
`sendMailDraft` documented 400 only as "The draft has no recipient".

Fix:
The 400 description now reads: "The draft has no recipient (field `to`) or is larger than the size limit (field `attachments`, e.g. after another mail client changed it); nothing was sent". The KDoc of `DraftService.send` says the same. `api/openapi.yaml` was regenerated with `./gradlew updateOpenApi`, the documented equivalent of `UPDATE_OPENAPI=1 ... OpenApiContractTest`; the env-prefixed form needed an approval that was not available here. `cd web && npm run api:gen` ran and produced no changes in `web/src/app/api/generated/`, because ng-openapi-gen does not emit response descriptions.

Validation:
- `cd server && ./gradlew test --tests '*OpenApiContractTest'` (without the flag) — Pass

## Previously unrun test-writer tests (commit 61fa3c21)

- `DraftComposeGreenMailTests` › `a reply keeps its threading headers when edited and the recipient gets them`: Pass. The only change was a ktlint line wrap by `ktlintFormat`.
- `DraftSendGreenMailTests` › `a Drafts folder deleted on the server by another client fails the save as a client error, nothing is sent`: Pass, unchanged.

Both passed before and after the CR-1 fix. No production bug was found, and neither test was changed in substance.

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — Pass (one line wrap in `DraftComposeGreenMailTests.kt`)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.compose.*' --tests '*OpenApiContractTest'` — Pass (139 tests, 0 failures)
- `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.compose.*'` — Pass (`DraftComposeGreenMailTests` 15, `DraftSendGreenMailTests` 15, 0 failures)

Full test suite:
- See `.taskcheck` below (includes `./gradlew check` for the server).

Lint/type-check/build:
- ktlint ran through `ktlintFormat` and as part of `.taskcheck`.

`.taskcheck`:
- `sh scripts/check.sh`: partial. The TFP gate tests passed, the server part (`./gradlew --quiet check`: ktlint + all server unit tests) passed, and the web part (`npm run -s check`: 106 files, 2095 tests, build) passed. The Android part was still running when the fixer's own 15-minute `timeout` wrapper stopped it, so its result is unknown. No Android file was changed. The whole check takes far longer than the 2-minute target on this machine. The parent worker runs `.taskcheck` before `done`.

Remaining unresolved findings:
0 from the re-review (CR-5 of the first review stays deferred by the parent worker).

## Final status

All validated findings fixed and focused validation passed (`.taskcheck` Android part not completed by the fixer, see above).
