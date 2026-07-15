create table agent_run_report_snapshot (
    id bigserial primary key,
    agent_task_id bigint not null references agent_task(id) on delete cascade,
    agent_run_id bigint references agent_run(id) on delete set null,
    project_id bigint not null references project(id) on delete cascade,
    generated_by_user_id bigint not null references app_user(id) on delete cascade,
    project_name varchar(255) not null,
    task_title varchar(255) not null,
    task_type varchar(50) not null,
    task_status varchar(80) not null,
    run_status varchar(50) not null,
    run_started_at timestamp with time zone not null,
    run_finished_at timestamp with time zone,
    report_generated_at timestamp with time zone not null,
    section_count int not null,
    markdown text not null,
    created_at timestamp with time zone not null
);

create index idx_agent_run_report_snapshot_task_generated
    on agent_run_report_snapshot(agent_task_id, report_generated_at desc);

create index idx_agent_run_report_snapshot_user_created
    on agent_run_report_snapshot(generated_by_user_id, created_at desc);
