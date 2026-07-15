create table controller_api_doc_snapshot (
    id bigserial primary key,
    project_id bigint not null references project(id) on delete cascade,
    generated_by_user_id bigint not null references app_user(id) on delete cascade,
    repo_full_name varchar(255) not null,
    generated_at timestamp with time zone not null,
    route_count int not null,
    filtered_count bigint not null,
    risk_level varchar(50),
    risk_code varchar(120),
    markdown text not null,
    created_at timestamp with time zone not null
);

create index idx_controller_api_doc_snapshot_project_generated
    on controller_api_doc_snapshot(project_id, generated_at desc);

create index idx_controller_api_doc_snapshot_user_created
    on controller_api_doc_snapshot(generated_by_user_id, created_at desc);
