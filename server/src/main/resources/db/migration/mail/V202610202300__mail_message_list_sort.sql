-- Message lists (GET /api/v1/mail/messages) sort and page on coalesce(received_at, created_at) desc, id desc
-- (MailMessageListQuery): indexes on exactly that expression, per folder and per account, so a page of a large
-- folder is read in index order instead of sorting the whole folder.
create index mail_message_folder_sort on mail_message (folder_id, coalesce(received_at, created_at) desc, id desc)
    where uid is not null;
create index mail_message_account_sort on mail_message (account_id, coalesce(received_at, created_at) desc, id desc)
    where uid is not null;
