CREATE TABLE github_repository_links (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    project_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES github_accounts(user_id),
    github_user_id BIGINT NOT NULL CHECK(github_user_id>0),
    installation_id BIGINT NOT NULL CHECK(installation_id>0),
    repository_id BIGINT NOT NULL CHECK(repository_id>0),
    full_name TEXT NOT NULL,
    html_url TEXT NOT NULL,
    private_repository BOOLEAN NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('initializing','ready','disconnecting','disconnected','failed')),
    access_status TEXT NOT NULL CHECK(access_status IN ('verified','check_pending','denied','reauthorization_required')),
    access_expires_at TIMESTAMPTZ,
    revision BIGINT NOT NULL CHECK(revision BETWEEN 1 AND 9007199254740991),
    generation BIGINT NOT NULL CHECK(generation>0),
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(workspace_id,project_id,repository_id),
    UNIQUE(workspace_id,project_id,id),
    FOREIGN KEY(workspace_id,project_id) REFERENCES projects(workspace_id,id) ON DELETE RESTRICT
);
CREATE INDEX ix_github_links_installation_repository ON github_repository_links(installation_id,repository_id);
CREATE INDEX ix_github_links_user ON github_repository_links(user_id);
CREATE TABLE github_link_create_replays (
    user_id UUID NOT NULL REFERENCES users(id),
    project_id UUID NOT NULL REFERENCES projects(id),
    request_key VARCHAR(128) NOT NULL,
    installation_id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    link_id UUID NOT NULL REFERENCES github_repository_links(id),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(user_id,project_id,request_key)
);
