create table approval_record (
    id bigserial primary key,
    agent_task_id bigint not null references agent_task(id) on delete cascade,
    patch_id bigint not null references patch_record(id) on delete cascade,
    user_id bigint not null references app_user(id),
    action varchar(50) not null,
    comment text,
    created_at timestamp with time zone not null
);

create index idx_approval_record_task_created on approval_record(agent_task_id, created_at);
create index idx_approval_record_patch on approval_record(patch_id);
create index idx_approval_record_user on approval_record(user_id);

