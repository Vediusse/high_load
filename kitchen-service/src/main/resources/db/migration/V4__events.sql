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

create table processed_event(event_id uuid primary key,processed_at timestamptz not null default clock_timestamp());
