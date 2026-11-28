-- Bodies cached before the body assembly of mail-apple-inline-images (joined text parts, positioned inline images,
-- inline only for cid: references) are re-read: without its attachment row a cached body counts as a miss, and the
-- loader fetches the message again and overwrites body and attachments.
delete from mail_body_attachments;
