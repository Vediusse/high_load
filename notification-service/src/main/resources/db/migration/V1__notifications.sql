create table processed_event(event_id uuid primary key,processed_at timestamptz not null default clock_timestamp());
create table notification(
 id uuid primary key,event_id uuid not null references processed_event(event_id),recipient_user_id uuid not null,
 type varchar(40) not null,subject_id uuid not null,text varchar(256) not null,
 created_at timestamptz not null,read_at timestamptz,
 unique(event_id,recipient_user_id)
);
create index ix_notification_recipient on notification(recipient_user_id,created_at desc,id desc);
