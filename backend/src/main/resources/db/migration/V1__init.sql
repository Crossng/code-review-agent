create extension if not exists vector;

create table app_user (
    id bigserial primary key,
    email varchar(255) not null unique,
    password_hash varchar(255) not null,
    display_name varchar(100) not null,
    role varchar(50) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table project (
    id bigserial primary key,
    owner_user_id bigint not null references app_user(id),
    repo_url text not null,
    repo_full_name varchar(255) not null,
    default_branch varchar(100) not null,
    local_path text,
    status varchar(50) not null,
    last_indexed_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_project_owner on project(owner_user_id);
create index idx_project_repo_full_name on project(repo_full_name);

create table agent_task (
    id bigserial primary key,
    project_id bigint not null references project(id),
    user_id bigint not null references app_user(id),
    task_type varchar(50) not null,
    title varchar(255) not null,
    description text not null,
    status varchar(80) not null,
    current_run_id bigint,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_agent_task_project_status on agent_task(project_id, status);
create index idx_agent_task_user_created on agent_task(user_id, created_at);

create table agent_run (
    id bigserial primary key,
    agent_task_id bigint not null references agent_task(id),
    status varchar(50) not null,
    started_at timestamp with time zone not null,
    finished_at timestamp with time zone,
    error_message text
);

alter table agent_task
    add constraint fk_agent_task_current_run
    foreign key (current_run_id) references agent_run(id);

create table agent_step (
    id bigserial primary key,
    agent_run_id bigint not null references agent_run(id),
    step_name varchar(100) not null,
    status varchar(50) not null,
    input_json jsonb,
    output_json jsonb,
    error_message text,
    started_at timestamp with time zone,
    finished_at timestamp with time zone
);

create index idx_agent_step_run_started on agent_step(agent_run_id, started_at);

