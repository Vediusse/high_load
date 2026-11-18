create table file_document(
 id uuid primary key,order_id uuid not null,relation_type varchar(32) not null check(relation_type in ('SPECIFICATION','DISCREPANCY_ACT')),
 original_name varchar(255) not null,mime_type varchar(128) not null,size bigint not null check(size>0),
 checksum varchar(64) not null check(checksum ~ '^[0-9a-f]{64}$'),uploaded_by uuid not null,uploaded_at timestamptz not null,
 status varchar(16) not null check(status in ('ACTIVE','DELETED')),storage_key uuid not null unique,version bigint not null check(version>=0)
);
create index ix_file_order on file_document(order_id,uploaded_at desc,id desc) where status='ACTIVE';
create table outbox_event (
  sequence bigserial unique not null,
  event_id uuid primary key,
  aggregate_id uuid not null,
  topic varchar(64) not null,
  payload jsonb not null,
  sent_at timestamptz,
  next_attempt_at timestamptz not null default clock_timestamp(),
  attempts integer not null default 0 check(attempts>=0),
  last_error varchar(128)
);
create index ix_outbox_pending on outbox_event(sequence) where sent_at is null;
create index ix_outbox_aggregate on outbox_event(aggregate_id,sequence) where sent_at is null;
