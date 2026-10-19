package com.kopite.devspace;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class GitHubMigrationTests {
    @Container static final PostgreSQLContainer db = new PostgreSQLContainer("postgres:16.4");
    final String schema = "github_" + UUID.randomUUID().toString().replace("-", "");
    Flyway flyway(int target) {
        return Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).schemas(schema).defaultSchema(schema)
                .target("" + target).locations("filesystem:src/main/resources/db/migration", "filesystem:src/main/migration-stages/cutover", "filesystem:src/main/migration-stages/contract").load();
    }
    void sql(String text) throws SQLException {
        try (var c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
            c.setSchema(schema); try (var s = c.createStatement()) { s.execute(text); }
        }
    }
    String scalar(String text) throws SQLException {
        try (var c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
            c.setSchema(schema); try (var s = c.createStatement(); var r = s.executeQuery(text)) { r.next(); return r.getString(1); }
        }
    }
    @Test void populatedV21UpgradePreservesExistingProjectAndTaskDataAndHistory() throws Exception {
        flyway(21).migrate();
        sql("insert into users values('00000000-0000-4000-8000-000000000001','Owner','2026-01-01Z','2026-01-01Z',null)");
        sql("insert into workspaces values('00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000001','Workspace',7,42,'2026-01-01Z','2026-01-02Z')");
        sql("insert into projects(id,workspace_id,name,stack,repository_url,created_at,updated_at) values('00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000001','Project','Java','https://github.com/old/link','2026-01-01Z','2026-01-02Z')");
        sql("insert into tasks(id,workspace_id,project_id,title,description,status,priority,tag,revision,created_at,updated_at) values(gen_random_uuid(),'00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000001','Local task','Local body','todo','normal','',1,'2026-01-01Z','2026-01-02Z')");
        String project = scalar("select to_jsonb(p)::text from projects p");
        String task = scalar("select to_jsonb(t)::text from tasks t");
        String history = scalar("select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history where version is not null");
        assertEquals(18, flyway(39).migrate().migrationsExecuted); flyway(39).validate();
        assertEquals(project, scalar("select to_jsonb(p)::text from projects p"));
        assertEquals(task, scalar("select (to_jsonb(t)-'source_type')::text from tasks t"));
        assertEquals("local",scalar("select source_type from tasks"));
        assertEquals(history, scalar("select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history where version::int<=21"));
        assertEquals(0, flyway(39).migrate().migrationsExecuted);
    }
}
