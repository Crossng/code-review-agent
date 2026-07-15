create table patch_record (
    id bigserial primary key,
    agent_task_id bigint not null references agent_task(id) on delete cascade,
    agent_run_id bigint not null references agent_run(id) on delete cascade,
    base_branch varchar(100) not null,
    target_branch varchar(100) not null,
    diff_content text not null,
    summary text,
    status varchar(50) not null,
    created_at timestamp with time zone not null
);

create index idx_patch_record_task_created on patch_record(agent_task_id, created_at);
create index idx_patch_record_run on patch_record(agent_run_id);

