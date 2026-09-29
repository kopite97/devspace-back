package com.kopite.devspace;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class DocumentMigrationTests {
    @Container static final PostgreSQLContainer db=new PostgreSQLContainer("postgres:16.4");
    final String schema="document_"+UUID.randomUUID().toString().replace("-","");
    Flyway flyway(int target) {
        return Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword())
            .schemas(schema).defaultSchema(schema).target(""+target)
            .locations("filesystem:src/main/resources/db/migration","filesystem:src/main/migration-stages/cutover","filesystem:src/main/migration-stages/contract").load();
    }
    void sql(String text)throws SQLException {try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword())){c.setSchema(schema);try(var s=c.createStatement()){s.execute(text);}}}
    String scalar(String text)throws SQLException {try(var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword())){c.setSchema(schema);try(var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);}}}
    String digest(String table)throws SQLException {return scalar("select coalesce(md5(string_agg(to_jsonb(t)::text,'' order by to_jsonb(t)::text)),'empty') from "+table+" t");}
    void fixture(int count)throws SQLException {
        sql("insert into users values('00000000-0000-4000-8000-000000000001','Owner','2026-01-01Z','2026-01-01Z',null)");
        sql("insert into workspaces values('00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000001','Workspace',7,42,'2026-01-01Z','2026-01-02Z')");
        sql("insert into projects(id,workspace_id,name,stack,created_at,updated_at) values('00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000001','Project','Java','2026-01-01Z','2026-01-02Z')");
        sql("insert into journals(id,workspace_id,project_id,title,body,entry_date,revision,created_at,updated_at) select gen_random_uuid(),'00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000001','Journal '||n,'Original body '||n,'2026-01-01',3,'2026-01-01Z','2026-01-02Z' from generate_series(1,"+count+") n");
        sql("insert into journal_create_idempotency values('00000000-0000-4000-8000-000000000001','POST','/api/v1/journals','historical','exact-hash',201,'{  \"historical\": true }','2026-01-01Z','2026-01-02Z')");
    }
    @Test void populatedV18UpgradeKeepsJournalsAndHistoryAndAllowsOldWriter()throws Exception {
        flyway(18).migrate();fixture(2);
        String journals=digest("journals"),users=digest("users"),workspaces=digest("workspaces"),replays=digest("journal_create_idempotency");
        String checksums=scalar("select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history where version is not null");
        assertEquals(1,flyway(19).migrate().migrationsExecuted);flyway(19).validate();assertEquals(0,flyway(19).migrate().migrationsExecuted);
        assertEquals(journals,digest("journals"));assertEquals(users,digest("users"));assertEquals(workspaces,digest("workspaces"));
        assertEquals(replays,digest("journal_create_idempotency"));
        assertEquals(checksums,scalar("select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history where version::int<=18"));
        sql("insert into journals(id,workspace_id,project_id,title,body,entry_date,created_at,updated_at) select gen_random_uuid(),workspace_id,project_id,'New','Body',entry_date,created_at,updated_at from journals limit 1");
        assertEquals("3",scalar("select count(*) from journals"));assertEquals("0",scalar("select count(*) from documents"));
    }
    @Test void failedMigrationRollsBackAllNewTablesAndCanRetry()throws Exception {
        flyway(18).migrate();sql("create table attachments(id integer)");
        assertThrows(Exception.class,()->flyway(19).migrate());
        assertEquals("0",scalar("select count(*) from information_schema.tables where table_schema=current_schema() and table_name in ('document_spaces','documents')"));
        assertEquals("18",scalar("select max(version::int) from flyway_schema_history where success"));
        sql("drop table attachments");assertEquals(1,flyway(19).migrate().migrationsExecuted);flyway(19).validate();
    }
    @Test void journalBackfillPreservesValuesAndReplayAtVolumeAndIsNotRepeated()throws Exception {
        flyway(18).migrate();fixture(1001);
        String before=digest("journals"),replays=digest("journal_create_idempotency"),workspaces=digest("workspaces");
        flyway(20).migrate();flyway(20).validate();
        assertEquals(before,scalar("select md5(string_agg((to_jsonb(t)-'document_space_id')::text,'' order by (to_jsonb(t)-'document_space_id')::text)) from journals t"));
        assertEquals(replays,digest("journal_create_idempotency"));assertEquals(workspaces,digest("workspaces"));
        assertEquals("1001",scalar("select count(distinct document_space_id) from journals"));
        assertEquals("1001",scalar("select count(*) from document_spaces"));
        assertEquals("0",scalar("select count(*) from journals j join document_spaces s on s.id=j.document_space_id where j.created_at<>s.created_at or j.updated_at<>s.updated_at"));
        assertEquals("0",scalar("select count(*) from documents"));
        assertEquals(0,flyway(20).migrate().migrationsExecuted);
        assertThrows(SQLException.class,()->sql("update journals set document_space_id=null"));
        assertThrows(SQLException.class,()->sql("update journals set document_space_id=gen_random_uuid()"));
        assertThrows(SQLException.class,()->sql("update journals set document_space_id=(select document_space_id from journals limit 1)"));
    }
    @Test void backfillFailureRollsBackColumnAndInsertedSpaces()throws Exception {
        flyway(19).migrate();fixture(2);
        String before=digest("journals");
        sql("alter table document_spaces add constraint reject_fixture check(created_at>'2030-01-01Z')");
        assertThrows(Exception.class,()->flyway(20).migrate());
        assertEquals("0",scalar("select count(*) from document_spaces"));assertEquals(before,digest("journals"));
        assertEquals("0",scalar("select count(*) from information_schema.columns where table_schema=current_schema() and table_name='journals' and column_name='document_space_id'"));
        sql("alter table document_spaces drop constraint reject_fixture");assertEquals(1,flyway(20).migrate().migrationsExecuted);flyway(20).validate();
    }
}
