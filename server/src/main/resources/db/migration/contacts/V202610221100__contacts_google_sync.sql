-- Google contacts sync state (People API, SPEC.md §8.3). Kept apart from contact so a sync run does not change a
-- contact's version (ETag) unless its data changed.

-- One row per Google OAuth connection that was synced: the People API sync token of the last complete run (null: the
-- next run lists everything), when it was last attempted, last succeeded, and the error of the last attempt (safe to
-- show: no secrets, no contact data). connection_id points into core's oauth_connection, so it is no foreign key; the
-- row is removed when the connection is disconnected.
create table contacts_google_account (
    connection_id   uuid primary key,
    sync_token      text,
    last_attempt_at timestamptz,
    last_success_at timestamptz,
    last_error      text
);

-- Which Google person a contact is: resource_name (people/c...) and the person's etag as last seen. resource_name is
-- null when the person is gone at Google (deleted there, or here and pushed); a restored contact is then created again.
-- synced_version is the contact version that matches Google; a higher version is a local change to push.
create table contacts_google_link (
    contact_id     uuid primary key references contact (id) on delete cascade,
    connection_id  uuid   not null references contacts_google_account (connection_id) on delete cascade,
    resource_name  text,
    etag           text,
    synced_version bigint not null
);

create unique index contacts_google_link_resource on contacts_google_link (connection_id, resource_name)
    where resource_name is not null;
