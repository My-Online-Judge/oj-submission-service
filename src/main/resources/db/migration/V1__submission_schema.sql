-- submission-db baseline: the four submission tables exactly as they exist in oj-db after judge-api V18
-- (pg_dump --schema-only of the live schema, 2026-10-04): submissions, languages, judge servers and the outbox,
-- with their indexes and the submission -> language foreign key. Problems and users live in other services: their
-- ids are plain columns here (soft references). The data itself is copied in by
-- judge-deployment/migrations/sp3-submission.sh at cutover.

CREATE TABLE t_judge_servers (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    created_by character varying(255),
    updated_at timestamp(6) without time zone NOT NULL,
    updated_by character varying(255),
    cpu_core integer,
    cpu_usage double precision,
    hostname character varying(255) NOT NULL,
    ip character varying(255),
    is_disabled boolean NOT NULL,
    judger_version character varying(255),
    last_heartbeat timestamp(6) without time zone,
    memory_usage double precision,
    service_url character varying(255),
    task_number integer
);

CREATE TABLE t_languages (
    is_disabled boolean NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    compile_command character varying(255),
    created_by character varying(255),
    exe_name character varying(255),
    extension character varying(255),
    identifier character varying(255),
    name character varying(255),
    run_command character varying(255),
    src_name character varying(255),
    updated_by character varying(255),
    seccomp_rule character varying(255),
    max_memory bigint,
    compile_max_memory bigint,
    editor_format character varying(255)
);

CREATE TABLE t_outbox (
    id uuid NOT NULL,
    topic character varying(255) NOT NULL,
    message_key character varying(255),
    payload bytea NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    published_at timestamp(6) without time zone
);

CREATE TABLE t_submissions (
    cpu_time integer,
    status integer NOT NULL,
    "time" integer NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    memory bigint,
    updated_at timestamp(6) without time zone NOT NULL,
    id uuid NOT NULL,
    language_id uuid NOT NULL,
    problem_id uuid NOT NULL,
    created_by character varying(255),
    error_message text,
    result integer,
    source_code text,
    updated_by character varying(255),
    share_submission boolean,
    user_id uuid,
    details jsonb,
    problem_slug character varying(255) NOT NULL
);

ALTER TABLE ONLY t_judge_servers
    ADD CONSTRAINT t_judge_servers_pkey PRIMARY KEY (id);
ALTER TABLE ONLY t_languages
    ADD CONSTRAINT t_languages_pkey PRIMARY KEY (id);
ALTER TABLE ONLY t_outbox
    ADD CONSTRAINT t_outbox_pkey PRIMARY KEY (id);
ALTER TABLE ONLY t_submissions
    ADD CONSTRAINT t_submissions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY t_judge_servers
    ADD CONSTRAINT uk212txd2uon50ymsndni3pssu9 UNIQUE (hostname);

CREATE INDEX idx_outbox_unpublished ON t_outbox USING btree (created_at) WHERE (published_at IS NULL);
CREATE INDEX idx_submissions_problem_id ON t_submissions USING btree (problem_id);
CREATE INDEX idx_submissions_problem_slug_created_at ON t_submissions USING btree (problem_slug, created_at DESC);
CREATE INDEX idx_submissions_user_problem_slug_created_at ON t_submissions USING btree (user_id, problem_slug, created_at DESC);

ALTER TABLE ONLY t_submissions
    ADD CONSTRAINT fk3i9f6nx3g74kxcljr76ta0gnc FOREIGN KEY (language_id) REFERENCES t_languages(id);
