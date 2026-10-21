---
title: Mail reading view
status: in_progress
owner: dell26/agent-2
deps: [web-mail-list,web-links-panel,mail-gtd]
touches: [web/src/app/features/mail/read]
prio: 1
lease_until: 2026-10-21T15:46:04Z
---
## Goal
Read mail safely (SPEC.md §7.3).

## Acceptance criteria
- [ ] Sanitized HTML in a sandboxed iframe (no scripts, CSP), 'load images' toggle (per sender allowlist), attachments (download, preview, save to Files)
- [ ] Buttons: → Inbox task, → Waiting For, Create note; linked items panel
- [ ] Vitest component/service tests cover iframe sandbox attributes, image toggle; `cd web && npm run check` passes
- [ ] Playwright `e2e/mail-read/read.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/mail-read/read.spec.ts`, see p0-e2e-harness): script in mail does not run; images load only after toggle

## Notes
- ASSUMPTION (2026-10-21 17:26+0200 by dell26/agent-2): Per-sender 'always load images' allowlist is kept client-side in localStorage (cockpit.mail.loadImagesFrom): the API has no allowlist endpoint; a server-side list can replace it later
- ASSUMPTION (2026-10-21 17:26+0200 by dell26/agent-2): Save to Files downloads the attachment in the browser and uploads it to the Files root via POST /api/v1/files (no server copy endpoint); attachments above 50 MB would need the resumable upload and fail with 413
- ASSUMPTION (2026-10-21 17:26+0200 by dell26/agent-2): Attachment preview covers raster images (inline=true URL) and text/* (first 100k chars); other types offer only download and save to Files
- ASSUMPTION (2026-10-21 17:26+0200 by dell26/agent-2): Read view route /mail/messages/:id (the server's deep link); the list opens a message with 'o' or double-click since single click only moves the cursor (existing list behavior); this edits mail.routes.ts and list/ outside touches
