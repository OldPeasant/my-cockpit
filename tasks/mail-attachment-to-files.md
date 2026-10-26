---
title: Save mail attachment to Files (/Attachments/Mail)
status: in_progress
owner: dell26/agent-1
deps: [web-mail-read,mail-load-older]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api,api/openapi.yaml,web/src/app/features/mail/read]
prio: 2
lease_until: 2026-10-26T08:40:17Z
extended: dell26/agent-1
---
## Goal
"Save to Files" in the mail read view stores the attachment in the auto-managed `/Attachments/Mail` folder
(SPEC.md §7.3, §8.2), server-side, instead of the browser downloading and re-uploading it to the drive root
(web-mail-read's interim solution).

## Acceptance criteria
- [ ] `POST /api/v1/mail/messages/{id}/attachments/{partId}/save-to-files` streams the part from the mail server into
      `FilesService` in `specialFolder(MAIL)` (name = attachment filename, taken names get a suffix), returns the
      `FileResponse` (201) and links the file to the message; Problem Details for 404/502/503; integration test
- [ ] `api/openapi.yaml` and the web client regenerated; `MailReadService.saveToFiles` uses the endpoint;
      `cd server && ./gradlew check` and `cd web && npm run check` pass

## Notes
Found in the web-mail-read review (CR-4). The file goes through the server, so large attachments work too.
- ASSUMPTION (2026-10-26 08:18+0100 by dell26/agent-1): Name-collision check for save-to-files added to the files module (FilesService.freeFileName + FileRepository.existsActiveInFolderByName, outside touches, small): taken = an active file in /Attachments/Mail with the same name regardless of case; it's a hint, not enforced (file names need not be unique)
- ASSUMPTION (2026-10-26 08:18+0100 by dell26/agent-1): save-to-files buffers the IMAP part in cockpit.data-dir/tmp/mail first (limited to cockpit.files.max-size, else 413), then stores and links in one transaction, so no DB connection waits on the mail server; link direction FILE -> EMAIL like the GTD actions; filename sanitized (slashes and control chars become _), "attachment" when missing
- extended by dell26/agent-1: Implementation + tests done and committed (e1ef1eec2, 93f2904a5, fbc34966c, 171db244d); sh scripts/check.sh passes at 171db244d (took >10 min). Left: (1) test-writer report reviews/mail-attachment-to-files/tests.md is unfinished (SEE_SERVER_CHECK/SEE_FULL_RESULT/SEE_SUMMARY placeholders) - rerun test-writer, passing it the server check result; (2) 171db244d added a test after the review, so rerun code-reviewer on final code; (3) tasks.py verify for both criteria (GreenMail integrationTest '*MailAttachmentToFilesGreenMailTests' passed, server/web checks pass); (4) write quality.json (fixer was run earlier, fixes.md), commit, done.
