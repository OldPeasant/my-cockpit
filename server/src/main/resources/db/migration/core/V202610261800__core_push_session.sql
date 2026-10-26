-- A Web Push subscription belongs to the login session that registered it (SPEC.md §4.1, §4.6): when that session
-- ends (logout, idle expiry, ended by another login or a TOTP re-enrollment), the browser gets no more pushes.
-- Existing subscriptions have no session; they are dropped, and the web app registers again on its next start.
delete from push_subscription;

alter table push_subscription
    add column session_id uuid not null references auth_session (id) on delete cascade;

create index push_subscription_session on push_subscription (session_id);
