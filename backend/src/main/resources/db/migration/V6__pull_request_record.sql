create table pull_request_record (
    id bigserial primary key,
    agent_task_id bigint not null references agent_task(id) on delete cascade,
    patch_id bigint not null references patch_record(id) on delete cascade,
    provider varchar(50) not null,
    pr_number integer,
    url text,
    title varchar(255) not null,
    body text,
    status varchar(50) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_pull_request_record_task_created on pull_request_record(agent_task_id, created_at);
create index idx_pull_request_record_patch_created on pull_request_record(patch_id, created_at);
create index idx_pull_request_record_status on pull_request_record(status);
