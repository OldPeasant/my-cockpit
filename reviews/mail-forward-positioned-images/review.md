# Code Review

Date: 2026-11-28

Task: mail-forward-positioned-images - Mail: forwards drop internal positioned-image references

Scope: The task has no commits of its own. Its behaviour was already on main before the claim, added in 84aaa89fc ("forwards drop positioned-image references") and refined in a346391a4 ("drop positioned image wrapper divs"). This review covers the code as it stands at HEAD: the FORWARD branch of `DraftService.respond`, `withoutPositionedImages` / `positionedImagesRemoved` / `forwardedAttachments` in `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt`, `MimeContent.partImage` / `partImageId` in `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt`, and the forward tests in `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftSendGreenMailTests.kt` (lines 323-400).

Requirements:
- tasks/mail-forward-positioned-images.md acceptance criteria
- AGENTS.md project notes (server conventions, fictitious test data)
- Commits 84aaa89fc and a346391a4 (git show), plus the current HEAD code

Git range:
- Branch: main
- HEAD: 4b589527d340f3fd7dae0f40b5717b480637dddf
- Base/start: 4b589527d (state review at HEAD; the code came from 84aaa89fc and a346391a4)
- Commits reviewed: 2 (84aaa89fc, a346391a4; no task-specific commits)
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Criteria check

1. No `cid:cockpit-part-` reference in a forward's quoted HTML; the image still goes along as an attachment.
   - `respond` (DraftService.kt:307-314) runs the sanitized HTML through `withoutPositionedImages` for FORWARD. `positionedImagesRemoved` (DraftService.kt:472-489) picks only those `img` elements whose `cid:` src passes `MimeContent.partImageId` (prefix `cockpit-part-`, suffix `@cockpit.invalid`, numeric dotted part id). It removes each one together with the bare div wrapper that MimeContent puts around it.
   - The sanitizer keeps `cid:` sources (HtmlMailSanitizer.kt:103, 214), so the reference does reach this step. That means the tests check something real and are not passing by accident.
   - For a plain-text mail with an inline image, MimeContent still builds an HTML body (escaped text plus the image references), so `sanitized` is not null and the same removal runs.
   - `forwardedAttachments` (DraftService.kt:398-414) forwards every content attachment. A positioned image has `inline=false` (it is not referenced by a real `cid:`) and keeps its null Content-ID, so it becomes a normal attachment.
   - Tested by: "a forward drops the positioned image reference ..." (Apple Mail style) and "a reply and a forward of a plain-text mail with a positioned image ...". Both check the API html, the attachment list, and the HTML part of the draft stored over IMAP.
2. Real `cid:` images in forwards keep today's behaviour.
   - Images whose `cid:` does not match `partImageId` are left alone. If nothing positioned is found, the method returns the original string unchanged.
   - Tested by: "a reply drops inline cid images, a forward keeps them as inline parts" (the inline part keeps its Content-ID) and "a forward keeps a real cid image next to a dropped positioned image".
3. Tests in the compose tests: present (GreenMail, DraftSendGreenMailTests). This review did not run `./gradlew check`, as instructed, because another helper runs Gradle at the same time. The parent worker must record that evidence.

Other observations, all validated and none needing a change:
- The reply path (`withoutInlineImages`) was not changed.
- Wrapper-div removal only applies to an attribute-free `div` whose only child is the image. Author HTML around a positioned image is therefore kept. Positioned references are created only by MimeContent, so these rules cannot catch a real image.
- Matching is case-insensitive for both `cid:` and the prefix/suffix, the same way MimeContent and MailReadService resolve these references.
- No secrets, logging changes or API contract changes are in scope.

## Review conclusion

No material issues found.
