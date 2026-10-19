ALTER TABLE github_repository_links ADD COLUMN last_manual_sync_at TIMESTAMPTZ;
ALTER TABLE github_repository_links ADD COLUMN last_successful_sync_at TIMESTAMPTZ;
CREATE TABLE github_sync_jobs (
    id UUID PRIMARY KEY,
    link_id UUID NOT NULL REFERENCES github_repository_links(id),
    kind TEXT NOT NULL DEFAULT 'collect',
    state TEXT NOT NULL DEFAULT 'queued' CHECK(state IN ('queued','running','partial','completed','failed')),
    stage TEXT NOT NULL DEFAULT 'labels',
    page INTEGER NOT NULL DEFAULT 1 CHECK(page>0),
    scan_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    requested_again BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0),
    available_at TIMESTAMPTZ NOT NULL,
    claim UUID,
    lease_until TIMESTAMPTZ,
    error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK((claim IS NULL)=(lease_until IS NULL))
);
CREATE UNIQUE INDEX uq_github_collect_active ON github_sync_jobs(link_id) WHERE kind='collect' AND state IN ('queued','running');
CREATE INDEX ix_github_jobs_dispatch ON github_sync_jobs(available_at,created_at,id) WHERE state IN ('queued','running');
CREATE TABLE github_dispatch_limits (
    installation_id BIGINT PRIMARY KEY,
    not_before TIMESTAMPTZ NOT NULL,
    claim UUID,
    lease_until TIMESTAMPTZ,
    CHECK((claim IS NULL)=(lease_until IS NULL))
);
CREATE TABLE github_sync_replays (
    user_id UUID NOT NULL REFERENCES users(id),
    link_id UUID NOT NULL REFERENCES github_repository_links(id),
    request_key VARCHAR(128) NOT NULL,
    job_id UUID NOT NULL REFERENCES github_sync_jobs(id),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(user_id,link_id,request_key)
);
CREATE TABLE github_issue_snapshots (
    link_id UUID NOT NULL REFERENCES github_repository_links(id),
    issue_id BIGINT NOT NULL CHECK(issue_id>0),
    issue_number BIGINT NOT NULL CHECK(issue_number>0),
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('open','closed')),
    state_reason TEXT,
    html_url TEXT NOT NULL,
    label_ids JSONB NOT NULL,
    assignee_ids JSONB NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    content_overflow BOOLEAN NOT NULL,
    available BOOLEAN NOT NULL DEFAULT TRUE,
    source_updated_at TIMESTAMPTZ NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(link_id,issue_id),
    UNIQUE(link_id,issue_number)
);
CREATE INDEX ix_github_issues_filter ON github_issue_snapshots(link_id,state,issue_id) WHERE available;
CREATE TABLE github_label_snapshots (
    link_id UUID NOT NULL REFERENCES github_repository_links(id),
    label_id BIGINT NOT NULL CHECK(label_id>0),
    name TEXT NOT NULL,
    color VARCHAR(6) NOT NULL,
    available BOOLEAN NOT NULL DEFAULT TRUE,
    scan_id UUID NOT NULL,
    PRIMARY KEY(link_id,label_id)
);
INSERT INTO github_sync_jobs(id,link_id,scan_id,generation,available_at,created_at,updated_at)
    SELECT gen_random_uuid(),id,gen_random_uuid(),generation,now(),now(),now()
    FROM github_repository_links WHERE status NOT IN ('disconnecting','disconnected');
