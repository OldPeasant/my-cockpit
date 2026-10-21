---
title: Save mail attachment to Files (/Attachments/Mail)
status: todo
owner:
deps: [web-mail-read,mail-load-older]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api,api/openapi.yaml,web/src/app/features/mail/read]
prio: 2
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
