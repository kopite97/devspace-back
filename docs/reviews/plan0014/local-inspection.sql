-- Read-only local inventory. Does not return credentials, user IDs or response bodies.
BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SELECT version, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT current_timestamp AS observed_at, cutover_at, retire_after, clock_margin_seconds,
       current_timestamp >= retire_after AS durable_deadline_elapsed
FROM category_cutover_state WHERE transition_key = 'project-category-only';
SELECT max(expires_at) AS latest_legacy_expiry FROM (
    SELECT expires_at FROM project_create_idempotency WHERE response_body::jsonb ? 'scope' UNION ALL
    SELECT expires_at FROM task_create_idempotency WHERE response_body::jsonb ? 'scope' UNION ALL
    SELECT expires_at FROM journal_create_idempotency WHERE response_body::jsonb ? 'scope' UNION ALL
    SELECT expires_at FROM milestone_create_idempotency WHERE response_body::jsonb ? 'scope' UNION ALL
    SELECT expires_at FROM link_create_idempotency WHERE response_body::jsonb->'item' ? 'scope'
) legacy;
SELECT 'workspaces' AS resource, count(*) FROM workspaces UNION ALL
SELECT 'projects', count(*) FROM projects UNION ALL
SELECT 'categories', count(*) FROM project_categories UNION ALL
SELECT 'tasks', count(*) FROM tasks UNION ALL
SELECT 'journals', count(*) FROM journals UNION ALL
SELECT 'milestones', count(*) FROM milestones UNION ALL
SELECT 'links', count(*) FROM links UNION ALL
SELECT 'dashboards', count(*) FROM dashboards;
SELECT schema_version, count(*), sum(jsonb_array_length(widgets)) AS legacy_widgets
FROM dashboards GROUP BY schema_version;
SELECT w->>'type' AS widget_type, w->'selection'->>'kind' AS selection_kind, count(*)
FROM dashboards CROSS JOIN LATERAL jsonb_array_elements(widgets) w GROUP BY 1,2 ORDER BY 1,2;
SELECT table_name, column_name FROM information_schema.columns
WHERE table_schema='public' AND ((table_name IN ('projects','links') AND column_name IN ('scope','category_id','project_id'))
OR table_name IN ('widgets','dashboard_widget_placements','legacy_widget_mapping')) ORDER BY 1,2;
SELECT pid, application_name, backend_type, state FROM pg_stat_activity
WHERE datname=current_database() AND pid<>pg_backend_pid();
ROLLBACK;
