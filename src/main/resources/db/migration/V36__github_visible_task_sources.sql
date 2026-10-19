CREATE VIEW github_visible_task_sources AS
SELECT s.*,l.full_name AS repository_name,l.paused,l.status AS link_status
FROM github_task_sources s
JOIN github_repository_links l ON l.id=s.link_id AND l.workspace_id=s.workspace_id AND l.project_id=s.project_id
JOIN github_accounts a ON a.user_id=l.user_id AND a.github_user_id=l.github_user_id
JOIN github_issue_snapshots i ON i.link_id=s.link_id AND i.issue_id=s.issue_id
JOIN workspaces w ON w.id=l.workspace_id AND w.owner_user_id=l.user_id
JOIN users u ON u.id=l.user_id
WHERE a.status='connected' AND l.access_status='verified' AND l.access_account_version=a.token_version
AND l.access_expires_at>CURRENT_TIMESTAMP AND l.status NOT IN ('disconnecting','disconnected')
AND i.available AND u.disabled_at IS NULL;
