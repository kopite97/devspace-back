package com.kopite.devspace;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CategoryCutoverMigrationTests {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.4");
    final JsonMapper json=JsonMapper.builder().build();
    final String schema="cutover_"+UUID.randomUUID().toString().replace("-","");
    final UUID w=UUID.randomUUID(),other=UUID.randomUUID(),project=UUID.randomUUID(),empty=UUID.randomUUID(),category=UUID.randomUUID(),link=UUID.randomUUID();
    Flyway flyway(int target,String manifest) {
        var config=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
            .schemas(schema).defaultSchema(schema).target(Integer.toString(target))
            .locations("filesystem:src/main/resources/db/migration","filesystem:src/main/migration-stages/cutover","filesystem:src/main/migration-stages/contract");
        if(manifest!=null)config.initSql("select set_config('devspace.category_cutover_manifest','"+manifest.replace("'","''")+"',false)");
        return config.load();
    }
    Connection connection()throws Exception {var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());c.setSchema(schema);return c;}
    void sql(String sql,Object...args)throws Exception {try(var c=connection();var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.execute();}}
    String scalar(String sql)throws Exception {try(var c=connection();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getString(1);}}
    void seed(int start)throws Exception {seed(start,false);}
    void seed(int start,boolean expiredLegacy)throws Exception {
        flyway(start,null).migrate();
        for(UUID id:List.of(w,other)) {
            sql("insert into users values(?,'Owner',now(),now(),null)",id);
            sql("insert into workspaces(id,owner_user_id,name,data_revision,created_at,updated_at) values(?,?,'Workspace',7,now(),now())",id,id);
        }
        sql("insert into projects(id,workspace_id,name,scope,stack,status,revision,created_at,updated_at) values(?,?,'Project','server','Java','archived',4,now(),now())",project,w);
        sql("insert into projects(id,workspace_id,name,scope,stack,created_at,updated_at) values(?,?,'Project','unity','Java',now(),now())",empty,w);
        sql("insert into projects(id,workspace_id,name,scope,stack,created_at,updated_at) values(?,?,'Other Project','unity','Java',now(),now())",other,other);
        sql("insert into tasks(id,workspace_id,project_id,title,created_at,updated_at,deleted_at) values(?,?,?,'Task',now(),now(),now())",UUID.randomUUID(),w,project);
        sql("insert into journals(id,workspace_id,project_id,title,body,entry_date,created_at,updated_at) values(?,?,?,'Journal','Body',current_date,now(),now())",UUID.randomUUID(),w,project);
        sql("insert into milestones(id,workspace_id,project_id,title,created_at,updated_at) values(?,?,?,'Milestone',now(),now())",UUID.randomUUID(),w,empty);
        sql("insert into link_collections values(?,3)",w);
        sql("insert into links(id,workspace_id,label,url,scope,position,revision,created_at,updated_at) values(?,?,'Link','https://example.com','unity',0,3,now(),now())",link,w);
        sql("insert into dashboards values(?,'home',1,3,?::jsonb,now(),now())",w,"[{\"id\":\"ambiguous\",\"type\":\"board\",\"title\":\"Board\",\"scope\":\"unity\",\"size\":\"wide\"},{\"id\":\"project\",\"type\":\"journal\",\"title\":\"Journal\",\"scope\":\"all\",\"size\":\"medium\",\"projectId\":\""+project+"\"}]");
        int n=0;
        for(var fixture:json.readTree(Files.readString(Path.of("src/test/resources/category-only/legacy-creations.json")))) {
            String r=fixture.path("resource").asString();
            sql("insert into "+r+"_create_idempotency values(?,'POST',?, ?,?,201,?,"+(expiredLegacy?"now()-interval '2 days',now()-interval '1 day'":"now()-interval '2 hours',now()+interval '22 hours'")+")",w,"/api/v1/"+r+"s","legacy-"+n++,fixture.path("requestHash").asString(),fixture.path("responseBody").asString());
            sql("insert into "+r+"_create_idempotency values(?,'POST',?, ?,?,201,?,now()-interval '2 days',now()-interval '1 day')",w,"/api/v1/"+r+"s","expired-"+n,fixture.path("requestHash").asString(),fixture.path("responseBody").asString());
        }
        if(start<14)flyway(14,null).migrate();
        sql("insert into project_categories values(?,?,'Tools',3,now(),now())",category,w);
        sql("insert into project_categories values(?,?,'Tools',2,now(),now())",UUID.randomUUID(),other);
        sql("update projects set category_id=? where id=?",category,project);
    }
    Map<String,Object> manifest()throws Exception {
        var dashboard=new LinkedHashMap<String,Object>();dashboard.put("expectedRevision",3);dashboard.put("expectedWidgetsMd5",scalar("select md5(widgets::text) from dashboards"));
        dashboard.put("selections",Map.of("ambiguous",Map.of("kind","category","categoryId",category.toString())));
        return new LinkedHashMap<>(Map.of("workspaces",List.of(
            new LinkedHashMap<>(Map.of("workspaceId",w.toString(),"expectedDataRevision",7,"dashboard",dashboard,"links",List.of(Map.of("id",link.toString(),"expectedRevision",3,"projectId",project.toString())))),
            Map.of("workspaceId",other.toString(),"expectedDataRevision",7,"links",List.of()))));
    }
    Map<String,String> snapshot()throws Exception {
        var result=new LinkedHashMap<String,String>();
        for(String table:List.of("users","workspaces","projects","project_categories","tasks","journals","milestones","links","link_collections","dashboards","project_create_idempotency","task_create_idempotency","journal_create_idempotency","milestone_create_idempotency","link_create_idempotency"))
            result.put(table,scalar("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from "+table+" t"));
        return result;
    }
    @Test void populatedV12AndV14GoldenRowsSurviveExplicitCutover()throws Exception {
        seed(12);var old=snapshot();String checksums=scalar("select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history");
        flyway(15,null).migrate();var expanded=snapshot();
        for(String table:old.keySet())if(!table.equals("links"))assertEquals(old.get(table),expanded.get(table),table);
        assertEquals("1",scalar("select count(*) from links where project_id is null"));
        var migrate=flyway(16,json.writeValueAsString(manifest()));assertEquals(1,migrate.migrate().migrationsExecuted);migrate.validate();
        var after=snapshot();
        for(String table:old.keySet())if(!Set.of("workspaces","links","link_collections","dashboards").contains(table))assertEquals(old.get(table),after.get(table),table);
        assertEquals(checksums,scalar("select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history where installed_rank<=14"));
        assertEquals("8",scalar("select min(data_revision) from workspaces"));assertEquals("8",scalar("select max(data_revision) from workspaces"));
        assertEquals("4",scalar("select revision from links"));assertEquals(project.toString(),scalar("select project_id from links"));assertEquals("4",scalar("select revision from link_collections"));
        assertEquals("4",scalar("select revision from dashboards"));assertEquals("2",scalar("select schema_version from dashboards"));
        assertEquals("0",scalar("select count(*) from dashboards d cross join lateral jsonb_array_elements(widgets) v where v ? 'scope' or v ? 'projectId'"));
        assertEquals(category.toString(),scalar("select widgets->0->'selection'->>'categoryId' from dashboards"));
        assertThrows(SQLException.class,()->sql("update links set project_id=? where id=?",UUID.randomUUID(),link));
        assertThrows(SQLException.class,()->sql("update links set project_id=? where id=?",other,link));
        assertThrows(SQLException.class,()->sql("delete from projects where id=?",project));
        Path out=Path.of(".gradle/project-category-only-validation/migration");Files.createDirectories(out);
        Files.writeString(out.resolve("populated-upgrade.json"),json.writeValueAsString(Map.of("oldChecksums",checksums,"before",old,"after",after)));
    }
    @Test void missingAmbiguousAndStaleManifestsRollbackEverything()throws Exception {
        seed(14);flyway(15,null).migrate();var before=snapshot();
        assertThrows(Exception.class,()->flyway(16,null).migrate());assertEquals(before,snapshot());
        String good=json.writeValueAsString(manifest());
        for(String bad:List.of(good.replace("\"expectedDataRevision\":7","\"expectedDataRevision\":6"),good.replace("\"ambiguous\"","\"unknown\""),good.replace(category.toString(),UUID.randomUUID().toString()))) {
            assertThrows(Exception.class,()->flyway(16,bad).migrate());assertEquals(before,snapshot());
        }
        sql("update workspaces set data_revision=9223372036854775807 where id=?",w);
        var overflow=snapshot();String max=good.replace("\"expectedDataRevision\":7","\"expectedDataRevision\":9223372036854775807");
        assertThrows(Exception.class,()->flyway(16,max).migrate());assertEquals(overflow,snapshot());
    }
    @Test void freshEmptyDatabaseNeedsNoManifestAndSeedsNothing()throws Exception {
        assertEquals(16,flyway(16,null).migrate().migrationsExecuted);
        assertEquals("0",scalar("select count(*) from project_categories"));assertEquals("0",scalar("select count(*) from projects"));
    }
    @Test void revisionOverflowsAndUnlinkedPolicyAreExplicit()throws Exception {
        seed(14);flyway(15,null).migrate();String good=json.writeValueAsString(manifest());
        for(String table:List.of("links","link_collections","dashboards")) {
            sql("update "+table+" set revision=9007199254740991");var before=snapshot();
            String adjusted=table.equals("link_collections")?good:good.replace("\"expectedRevision\":3","\"expectedRevision\":9007199254740991");
            assertThrows(Exception.class,()->flyway(16,adjusted).migrate());assertEquals(before,snapshot());
            sql("update "+table+" set revision=3");
        }
        // Review explicitly chooses null; no Link or collection revision bump for no assignment.
        String unlinked=good.replace("\"projectId\":\""+project+"\"","\"projectId\":null");
        flyway(16,unlinked).migrate();assertEquals("3",scalar("select revision from links"));
        assertEquals("3",scalar("select revision from link_collections"));assertEquals("1",scalar("select count(*) from links where project_id is null"));
    }

    @Test void finalMigrationCannotRetireBeforeDurableDeadlineOrLiveLegacyExpiry()throws Exception {
        seed(14);flyway(16,json.writeValueAsString(manifest())).migrate();var before=snapshot();
        assertEquals("t",scalar("select retire_after >= cutover_at+interval '24 hours 5 minutes' from category_cutover_state"));
        assertThrows(Exception.class,()->flyway(17,null).migrate());assertEquals(before,snapshot());
        // Only the rehearsal clock metadata moves; no replay body/hash/expiry is rewritten.
        sql("update category_cutover_state set cutover_at=now()-interval '2 days',retire_after=now()-interval '1 day'");
        assertThrows(Exception.class,()->flyway(17,null).migrate());assertEquals(before,snapshot());
        assertEquals("16",scalar("select max(version::integer) from flyway_schema_history where success"));
    }
    @Test void populatedV12ThroughFinalPreservesRowsAndAllowsUnexpiredV2Replays()throws Exception {
        seed(12,true);String checksums=scalar("select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history");
        flyway(15,null).migrate();flyway(16,json.writeValueAsString(manifest())).migrate();
        UUID created=UUID.randomUUID();var now=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        sql("insert into projects(id,workspace_id,name,stack,created_at,updated_at) values(?,?,'Project','Java',?,?)",created,w,Timestamp.from(now),Timestamp.from(now));
        var snapshot=new com.kopite.devspace.project.application.model.ProjectSnapshot(created,1,now,now,"Project","","Java",java.math.BigDecimal.ZERO,"","","active",null);
        sql("insert into project_create_idempotency values(?,'POST','/api/v1/projects','v2-live','fc6656fbbaf5f6f99e0d7d191df801b2e03dedd37d0fd1073dca8c42496a06bb',201,?,?,?)",w,json.writeValueAsString(snapshot),Timestamp.from(now),Timestamp.from(now.plusSeconds(86400)));
        var before=snapshot();
        sql("update category_cutover_state set cutover_at=now()-interval '2 days',retire_after=now()-interval '1 day'");
        var finalMigration=flyway(17,null);assertEquals(1,finalMigration.migrate().migrationsExecuted);finalMigration.validate();var after=snapshot();
        for(String table:before.keySet()) {
            var old=json.readTree(before.get(table));
            if(Set.of("projects","links").contains(table))for(var row:old)((tools.jackson.databind.node.ObjectNode)row).remove("scope");
            // JSONB aggregation ordering can change after a column removal; compare row sets.
            var oldRows=new HashSet<tools.jackson.databind.JsonNode>();old.forEach(oldRows::add);var newRows=new HashSet<tools.jackson.databind.JsonNode>();json.readTree(after.get(table)).forEach(newRows::add);
            assertEquals(oldRows,newRows,table);
        }
        assertEquals("0",scalar("select count(*) from information_schema.columns where table_schema=current_schema() and table_name in ('projects','links') and column_name='scope'"));
        assertEquals(checksums,scalar("select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history where installed_rank<=14"));
        assertThrows(SQLException.class,()->sql("delete from project_categories where id=?",category));
        assertThrows(SQLException.class,()->sql("update projects set category_id=? where id=?",category,other));
        assertThrows(SQLException.class,()->sql("update dashboards set schema_version=1"));
        assertEquals("8",scalar("select min(data_revision) from workspaces"));assertEquals("4",scalar("select revision from dashboards"));
        Path out=Path.of(".gradle/project-category-only-validation/migration");Files.createDirectories(out);Files.writeString(out.resolve("contract-upgrade.json"),json.writeValueAsString(Map.of("oldChecksums",checksums,"before",before,"after",after,"drain","expired legacy rows unchanged; future v2 replay retained")));
    }
    @Test void emptyFinalInstallHasNoLegacyWindowOrStarterData()throws Exception {
        assertEquals(17,flyway(17,null).migrate().migrationsExecuted);
        assertEquals("0",scalar("select count(*) from projects"));assertEquals("0",scalar("select count(*) from project_categories"));
        assertEquals("0",scalar("select count(*) from dashboards"));assertEquals("0",scalar("select count(*) from links"));
    }

    @Test void expiredReplayCleanupCannotBypassDurableRetirementDeadline() throws Exception {
        seed(14,true);
        flyway(16,json.writeValueAsString(manifest())).migrate();
        for (String resource : List.of("project","task","journal","milestone","link"))
            sql("delete from "+resource+"_create_idempotency where expires_at < now()");
        var before=snapshot();
        assertThrows(Exception.class,()->flyway(17,null).migrate());
        assertEquals(before,snapshot());
        assertEquals("16",scalar("select max(version::int) from flyway_schema_history where success"));
    }

    @Test void committedV17SurvivesV18FailureAndUnchangedMigrationCanRetry() throws Exception {
        seed(14,true);
        flyway(16,json.writeValueAsString(manifest())).migrate();
        // Synthetic fixture only: represent an already elapsed replay window.
        sql("update category_cutover_state set cutover_at=now()-interval '2 days',retire_after=now()-interval '1 day'");
        String good=scalar("select widgets::text from dashboards");
        sql("update dashboards set widgets=jsonb_set(widgets,'{0,type}','\"unsupported\"'::jsonb)");
        var before=snapshot();
        assertThrows(Exception.class,()->flyway(18,null).migrate());
        assertEquals("17",scalar("select max(version::int) from flyway_schema_history where success"));
        assertEquals("0",scalar("select count(*) from information_schema.tables where table_schema=current_schema() and table_name='widgets'"));
        for (String resource : List.of("project","task","journal","milestone","link"))
            assertEquals(before.get(resource+"_create_idempotency"),snapshot().get(resource+"_create_idempotency"));
        sql("update dashboards set widgets=?::jsonb",good);
        assertEquals(1,flyway(18,null).migrate().migrationsExecuted);
        assertEquals(0,flyway(18,null).migrate().migrationsExecuted);
        flyway(18,null).validate();
        assertEquals("2",scalar("select count(*) from widgets"));
        assertEquals("2",scalar("select count(*) from dashboard_widget_placements"));
        assertEquals("2",scalar("select count(*) from legacy_widget_mapping"));
    }
}
