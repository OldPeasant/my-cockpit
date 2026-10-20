---
title: Quick capture dialog
status: todo
owner:
deps: [web-gtd-data,gtd-capture-api]
touches: [web/src/app/features/capture]
prio: 1
---
## Goal
Capture from anywhere (SPEC.md §4.5), provided through the shell's `CAPTURE_DIALOG` token.

## Acceptance criteria
- [ ] Dialog: text field with live syntax preview (debounced `capture/preview`), optional notes, paste/drop attachment placeholder (files arrive in phase 2), Enter saves to Inbox and closes, Shift+Enter saves and keeps open
- [ ] Client generates a UUIDv7 `id` so a retried submit cannot duplicate
- [ ] Vitest component/service tests cover preview, save, double-submit protection; `cd web && npm run check` passes
- [ ] Playwright `e2e/capture/capture.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/capture/capture.spec.ts`, see p0-e2e-harness): Q opens the dialog, three captures appear in the inbox

## Notes
