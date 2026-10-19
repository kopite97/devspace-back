ALTER TABLE tasks ADD COLUMN source_type TEXT NOT NULL DEFAULT 'local' CHECK(source_type IN ('local','github'));
ALTER TABLE tasks ADD CONSTRAINT uq_tasks_owned_identity UNIQUE(workspace_id,project_id,id);
CREATE TABLE github_task_sources (
    task_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    link_id UUID NOT NULL,
    issue_id BIGINT NOT NULL,
    issue_number BIGINT NOT NULL,
    html_url TEXT NOT NULL,
    last_decision_label_id BIGINT,
    status_reason TEXT,
    conflict_label_ids JSONB NOT NULL DEFAULT '[]',
    content_error TEXT,
    content_hash VARCHAR(64) NOT NULL,
    source_state TEXT NOT NULL,
    source_state_reason TEXT,
    source_updated_at TIMESTAMPTZ NOT NULL,
    last_applied_at TIMESTAMPTZ NOT NULL,
    UNIQUE(workspace_id,project_id,issue_id),
    FOREIGN KEY(workspace_id,project_id,task_id) REFERENCES tasks(workspace_id,project_id,id),
    FOREIGN KEY(workspace_id,project_id,link_id) REFERENCES github_repository_links(workspace_id,project_id,id),
    FOREIGN KEY(link_id,issue_id) REFERENCES github_issue_snapshots(link_id,issue_id)
);
CREATE INDEX ix_github_task_sources_link ON github_task_sources(link_id,issue_id);
CREATE TABLE github_import_settings (
    link_id UUID PRIMARY KEY REFERENCES github_repository_links(id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    generation BIGINT NOT NULL DEFAULT 1 CHECK(generation>0),
    baseline_ready BOOLEAN NOT NULL DEFAULT FALSE,
    label_ids JSONB NOT NULL DEFAULT '[]',
    assignee_ids JSONB NOT NULL DEFAULT '[]',
    todo_label_id BIGINT,
    doing_label_id BIGINT,
    done_label_id BIGINT,
    CHECK(todo_label_id IS NULL OR todo_label_id>0),
    CHECK(doing_label_id IS NULL OR doing_label_id>0),
    CHECK(done_label_id IS NULL OR done_label_id>0),
    CHECK(todo_label_id IS NULL OR doing_label_id IS NULL OR todo_label_id<>doing_label_id),
    CHECK(todo_label_id IS NULL OR done_label_id IS NULL OR todo_label_id<>done_label_id),
    CHECK(doing_label_id IS NULL OR done_label_id IS NULL OR doing_label_id<>done_label_id)
);
INSERT INTO github_import_settings(link_id) SELECT id FROM github_repository_links;
CREATE TABLE github_import_observations (
    link_id UUID NOT NULL,
    issue_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    matched BOOLEAN NOT NULL,
    last_failure_hash VARCHAR(64),
    PRIMARY KEY(link_id,issue_id),
    FOREIGN KEY(link_id,issue_id) REFERENCES github_issue_snapshots(link_id,issue_id) ON DELETE CASCADE
);
CREATE TABLE github_issue_exclusions (
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    issue_id BIGINT NOT NULL,
    task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
    reason TEXT NOT NULL CHECK(reason IN ('deleted','converted','purged')),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(workspace_id,project_id,issue_id),
    FOREIGN KEY(workspace_id,project_id) REFERENCES projects(workspace_id,id)
);
ALTER TABLE github_sync_jobs ADD COLUMN payload JSONB NOT NULL DEFAULT '{}';
ALTER TABLE github_sync_jobs ADD COLUMN completed_count INTEGER NOT NULL DEFAULT 0 CHECK(completed_count>=0);
ALTER TABLE github_sync_jobs ADD COLUMN failed_count INTEGER NOT NULL DEFAULT 0 CHECK(failed_count>=0);
CREATE UNIQUE INDEX uq_github_task_work_active ON github_sync_jobs(link_id,kind,stage)
    WHERE kind IN ('apply','baseline','mapping') AND state IN ('queued','running');
CREATE TABLE github_task_job_items (
    job_id UUID NOT NULL REFERENCES github_sync_jobs(id),
    issue_id BIGINT NOT NULL,
    state TEXT NOT NULL DEFAULT 'queued' CHECK(state IN ('queued','completed','failed')),
    task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
    error_code TEXT,
    PRIMARY KEY(job_id,issue_id)
);
CREATE TABLE github_task_request_replays (
    user_id UUID NOT NULL REFERENCES users(id),
    link_id UUID NOT NULL REFERENCES github_repository_links(id),
    operation TEXT NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    job_id UUID NOT NULL REFERENCES github_sync_jobs(id),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(user_id,link_id,operation,request_key)
);
