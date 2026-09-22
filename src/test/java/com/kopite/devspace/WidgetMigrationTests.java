package com.kopite.devspace;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class WidgetMigrationTests {
    @Container static final PostgreSQLContainer db=new PostgreSQLContainer("postgres:16.4");
    final String schema="widget_"+UUID.randomUUID().toString().replace("-","");
    final UUID w=UUID.fromString("00000000-0000-0000-0000-000000000001");
    Flyway flyway(int target){return Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).schemas(schema).defaultSchema(schema).target(""+target)
        .locations("filesystem:src/main/resources/db/migration","filesystem:src/main/migration-stages/cutover","filesystem:src/main/migration-stages/contract").load();}
    Connection connection()throws Exception {var c=DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());c.setSchema(schema);return c;}
    void sql(String text,Object... values)throws Exception {try(var c=connection();var s=c.prepareStatement(text)){for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);s.execute();}}
    String scalar(String text)throws Exception {try(var c=connection();var s=c.createStatement();var r=s.executeQuery(text)){r.next();return r.getString(1);}}
    String migration()throws Exception{return Files.readString(Path.of("src/main/resources/db/migration/V18__independent_widgets_and_placements.sql"));}
    void owner(UUID id)throws Exception {
        sql("insert into users values(?,'Owner',now(),now(),null)",id);
        sql("insert into workspaces(id,owner_user_id,name,created_at,updated_at) values(?,?,'Workspace',now(),now())",id,id);
    }
    String widget(String id,String type,String selection,String limit) {
        return "{\"id\":\""+id+"\",\"type\":\""+type+"\",\"title\":\"Example\",\"size\":\"wide\",\"selection\":"+selection+limit+"}";
    }
    void saved(UUID id,String widgets)throws Exception {sql("insert into dashboards values(?,'home',2,7,?::jsonb,'2026-01-01Z','2026-02-01Z')",id,widgets);}
    @Test void emptyUpgradeAndChecksumHistory()throws Exception {
        flyway(17).migrate();String before=scalar("select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history where version is not null");
        flyway(18).migrate();flyway(18).validate();assertEquals("0",scalar("select count(*) from widgets"));
        assertEquals(before,scalar("select jsonb_agg(jsonb_build_array(version,checksum) order by installed_rank)::text from flyway_schema_history where version::int<=17"));
    }
    @Test void populatedVolumeRehearsalAndPreSwitchRollbackTiming()throws Exception {
        flyway(17).migrate();var values=new ArrayList<String>();for(String type:List.of("overview","board","deploy","links","journal","milestone"))values.add(widget("home-"+type,type,"{\"kind\":\"all\"}",""));
        String widgets="["+String.join(",",values)+"]";
        try(var c=connection()) {
            c.setAutoCommit(false);
            try(var u=c.prepareStatement("insert into users values(?,'Owner',now(),now(),null)");var ws=c.prepareStatement("insert into workspaces(id,owner_user_id,name,created_at,updated_at) values(?,?,'Workspace',now(),now())");var d=c.prepareStatement("insert into dashboards values(?,'home',2,7,?::jsonb,now(),now())")) {
                for(int i=0;i<100;i++){UUID id=UUID.randomUUID();u.setObject(1,id);u.executeUpdate();ws.setObject(1,id);ws.setObject(2,id);ws.executeUpdate();d.setObject(1,id);d.setString(2,widgets);d.executeUpdate();}
            }c.commit();
        }
        String before=scalar("select md5(string_agg(to_jsonb(d)::text,'' order by workspace_id)) from dashboards d");
        double rollbackMs;
        try(var c=connection()){c.setAutoCommit(false);try(var s=c.createStatement()){s.execute(migration());}long at=System.nanoTime();c.rollback();rollbackMs=(System.nanoTime()-at)/1e6;}
        assertEquals(before,scalar("select md5(string_agg(to_jsonb(d)::text,'' order by workspace_id)) from dashboards d"));
        assertEquals("0",scalar("select count(*) from information_schema.tables where table_schema=current_schema() and table_name='widgets'"));
        long at=System.nanoTime();flyway(18).migrate();double elapsed=(System.nanoTime()-at)/1e6;flyway(18).validate();
        assertEquals("600",scalar("select count(*) from widgets"));assertEquals("600",scalar("select count(*) from legacy_widget_mapping"));assertEquals("600",scalar("select count(*) from dashboard_widget_placements"));
        assertEquals("100",scalar("select count(*) from dashboards where layout_revision=7 and widgets=jsonb_build_array("+values.stream().map(v->"'"+v+"'::jsonb").collect(java.util.stream.Collectors.joining(","))+")"));
        assertEquals("100",scalar("select count(*) from workspaces where data_revision=1"));
        Path out=Path.of("build/reports/plan0013/migration-volume.json");Files.createDirectories(out.getParent());Files.writeString(out,"{\"workspaces\":100,\"widgets\":600,\"flywayV18Ms\":"+elapsed+",\"sqlTransactionRollbackMs\":"+rollbackMs+",\"reconciled\":true}");
    }
    @Test void populatedRollbackRetryPreservesExactMappingAndLegacy()throws Exception {
        flyway(17).migrate();owner(w);UUID empty=UUID.randomUUID(),absent=UUID.randomUUID(),other=UUID.randomUUID();owner(empty);owner(absent);owner(other);
        String all="{\"kind\":\"all\"}",missing="{\"kind\":\"category\",\"categoryId\":\""+UUID.randomUUID()+"\"}";
        String array="["+widget("home-board","board",all,"")+","+widget("unicode-\u03bb|:","board",missing,",\"limit\":3")+"]";
        saved(w,array);saved(empty,"[]");saved(other,"["+widget("home-board","board",all,"")+"]");
        String original=scalar("select widgets::text from dashboards where workspace_id='"+w+"'");String mapping;
        try(var c=connection()){c.setAutoCommit(false);try(var s=c.createStatement()){s.execute(migration());try(var r=s.executeQuery("select jsonb_agg(to_jsonb(m) order by workspace_id,legacy_widget_id)::text from legacy_widget_mapping m")){r.next();mapping=r.getString(1);}}c.rollback();}
        assertEquals("0",scalar("select count(*) from information_schema.tables where table_schema=current_schema() and table_name='widgets'"));
        assertEquals("0",scalar("select data_revision from workspaces where id='"+w+"'"));
        long started=System.nanoTime();flyway(18).migrate();System.out.println("WIDGET_POPULATED_MIGRATION_MS="+(System.nanoTime()-started)/1_000_000.0);
        assertEquals(mapping,scalar("select jsonb_agg(to_jsonb(m) order by workspace_id,legacy_widget_id)::text from legacy_widget_mapping m"));
        assertEquals(original,scalar("select widgets::text from dashboards where workspace_id='"+w+"'"));
        assertEquals("e80ce8b8-a78c-3dc4-9337-e62c359c39b9",scalar("select widget_id from legacy_widget_mapping where workspace_id='"+w+"' and legacy_widget_id='home-board'"));
        assertEquals("ce732f96-b7f5-3a8a-8334-cfdbb1a7ad62",scalar("select placement_id from legacy_widget_mapping where workspace_id='"+w+"' and legacy_widget_id='home-board'"));
        assertEquals("7",scalar("select layout_revision from dashboards where workspace_id='"+empty+"'"));
        assertEquals("0",scalar("select count(*) from dashboards where workspace_id='"+absent+"'"));
        assertEquals("1",scalar("select data_revision from workspaces where id='"+empty+"'"));
        assertEquals("0",scalar("select data_revision from workspaces where id='"+absent+"'"));
        assertEquals("3",scalar("select count(*) from widgets where revision=1 and created_at='2026-01-01Z' and updated_at='2026-02-01Z'"));
        assertEquals("2",scalar("select count(*) from widgets where not(config ? 'limit')"));
        assertThrows(SQLException.class,()->sql("update dashboard_widget_placements set workspace_id=? where workspace_id=?",absent,w));
        assertThrows(SQLException.class,()->sql("delete from widgets where workspace_id=?",w));
        sql("delete from dashboard_widget_placements where workspace_id=?",w);sql("delete from widgets where workspace_id=?",w);
        assertEquals("2",scalar("select count(*) from legacy_widget_mapping where workspace_id='"+w+"'"));
    }
    @Test void invalidRowsAndOverflowAbortWithoutPartialTables()throws Exception {
        flyway(17).migrate();owner(w);saved(w,"["+widget("one","unknown","{\"kind\":\"all\"}","")+"]");
        assertThrows(Exception.class,()->flyway(18).migrate());assertEquals("17",scalar("select max(version::int) from flyway_schema_history where success"));
        sql("update dashboards set widgets='[]'::jsonb");sql("update workspaces set data_revision=9223372036854775807");
        assertThrows(Exception.class,()->flyway(18).migrate());
        assertEquals("0",scalar("select count(*) from information_schema.tables where table_schema=current_schema() and table_name='widgets'"));
    }
    @Test void missingProjectDuplicateIdentityAndUnsafeBridgeAreRejected()throws Exception {
        flyway(17).migrate();owner(w);saved(w,"["+widget("one","board","{\"kind\":\"project\",\"projectId\":\""+UUID.randomUUID()+"\"}","")+"]");
        assertThrows(Exception.class,()->flyway(18).migrate());
        String value=widget("same","board","{\"kind\":\"all\"}","");sql("update dashboards set widgets=?::jsonb","["+value+","+value+"]");
        assertThrows(Exception.class,()->flyway(18).migrate());
        sql("update dashboards set widgets='[]'::jsonb");sql("alter table projects add column scope text");
        assertThrows(Exception.class,()->flyway(18).migrate());
    }
    @Test void nameFramingAndNamespacesAreStable() {
        String prefix="devspace-widget-backfill-v1|";String name="|"+w+"|4:home|10:home-board";
        assertEquals(UUID.fromString("e80ce8b8-a78c-3dc4-9337-e62c359c39b9"),UUID.nameUUIDFromBytes((prefix+"widget"+name).getBytes(StandardCharsets.UTF_8)));
        assertEquals(UUID.fromString("ce732f96-b7f5-3a8a-8334-cfdbb1a7ad62"),UUID.nameUUIDFromBytes((prefix+"placement"+name).getBytes(StandardCharsets.UTF_8)));
    }

    @Test void postgresBackupRestoresAndFinalApplicationStartsWithoutChangingRows() throws Exception {
        flyway(18).migrate();owner(w);
        sql("insert into dashboards(workspace_id,dashboard_key,schema_version,revision,widgets,created_at,updated_at,layout_revision) values(?,'home',2,7,'[]',now(),now(),7)",w);
        sql("insert into widgets(id,workspace_id,type,title,config_version,config,revision,created_at,updated_at) values(?,?,'board','Restored',1,'{\"selection\":{\"kind\":\"all\"}}',1,now(),now())",UUID.randomUUID(),w);
        String backup="/tmp/"+schema+".dump", restored="restore_"+UUID.randomUUID().toString().replace("-","");
        long started=System.nanoTime();
        var dump=db.execInContainer("pg_dump","-U",db.getUsername(),"-d",db.getDatabaseName(),"-n",schema,"-Fc","-f",backup);
        assertEquals(0,dump.getExitCode(),dump.getStderr());
        var create=db.execInContainer("createdb","-U",db.getUsername(),restored);
        assertEquals(0,create.getExitCode(),create.getStderr());
        var restore=db.execInContainer("pg_restore","-U",db.getUsername(),"-d",restored,"--exit-on-error","--no-owner",backup);
        assertEquals(0,restore.getExitCode(),restore.getStderr());
        String restoredUrl=db.getJdbcUrl().replace("/"+db.getDatabaseName(),"/"+restored);
        var migrated=Flyway.configure().dataSource(restoredUrl,db.getUsername(),db.getPassword()).schemas(schema).defaultSchema(schema)
            .locations("filesystem:src/main/resources/db/migration","filesystem:src/main/migration-stages/cutover","filesystem:src/main/migration-stages/contract").load();
        migrated.validate();assertEquals(0,migrated.migrate().migrationsExecuted);
        var before=restoredRows(restoredUrl);
        assertEquals(restoredRows(db.getJdbcUrl()),before);
        try(var app=new org.springframework.boot.builder.SpringApplicationBuilder(DevspaceApplication.class).run(
            "--spring.config.location=classpath:widget-isolated-test.yml","--server.port=0",
            "--spring.datasource.url="+restoredUrl,"--spring.datasource.username="+db.getUsername(),"--spring.datasource.password="+db.getPassword(),
            "--spring.datasource.hikari.schema="+schema,"--spring.flyway.default-schema="+schema,"--spring.flyway.schemas="+schema,
            "--spring.jpa.properties.hibernate.default_schema="+schema)) {
            assertTrue(app.isActive());
            assertEquals(before,restoredRows(restoredUrl));
        }
        var evidence=Path.of("build/reports/plan0014/backup-restore.txt");Files.createDirectories(evidence.getParent());
        Files.writeString(evidence,"Synthetic PostgreSQL custom dump -> isolated database -> Flyway validate/no-op migrate -> final application/Hibernate validate. All table contents unchanged. Duration ms="+(System.nanoTime()-started)/1_000_000.0);
    }

    Map<String,String> restoredRows(String url) throws Exception {
        var result=new TreeMap<String,String>();
        try(var c=DriverManager.getConnection(url,db.getUsername(),db.getPassword())) {
            c.setSchema(schema);
            var tables=new ArrayList<String>();
            try(var s=c.prepareStatement("select tablename from pg_tables where schemaname=? order by tablename")) {
                s.setString(1,schema);try(var r=s.executeQuery()){while(r.next())tables.add(r.getString(1));}
            }
            for(String table:tables)try(var s=c.createStatement();var r=s.executeQuery("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from \""+table+"\" t")) {
                r.next();result.put(table,r.getString(1));
            }
        }
        return result;
    }
    @Test void allTypesReorderedArraysAndExistingMappingCollision()throws Exception {
        flyway(17).migrate();owner(w);var items=new ArrayList<String>();
        for(String type:List.of("overview","board","deploy","links","journal","milestone"))items.add(widget(type+"-\u03bb|:",type,"{\"kind\":\"all\"}",""));
        saved(w,"["+String.join(",",items)+"]");String first;
        try(var c=connection()){c.setAutoCommit(false);try(var s=c.createStatement()){s.execute(migration());try(var r=s.executeQuery("select jsonb_agg(to_jsonb(m) order by legacy_widget_id)::text from legacy_widget_mapping m")){r.next();first=r.getString(1);}}c.rollback();}
        Collections.reverse(items);sql("update dashboards set widgets=?::jsonb","["+String.join(",",items)+"]");
        sql("create table legacy_widget_mapping (workspace_id uuid)");assertThrows(Exception.class,()->flyway(18).migrate());
        sql("drop table legacy_widget_mapping");flyway(18).migrate();
        assertEquals(first,scalar("select jsonb_agg(to_jsonb(m) order by legacy_widget_id)::text from legacy_widget_mapping m"));
        assertEquals("milestone",scalar("select type from widgets w join dashboard_widget_placements p on p.widget_id=w.id where position=0"));
        assertEquals("6",scalar("select count(distinct type) from widgets"));
        String id="board-\u03bb|:";String name="devspace-widget-backfill-v1|widget|"+w+"|4:home|"+id.getBytes(StandardCharsets.UTF_8).length+":"+id;
        assertEquals(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString(),scalar("select widget_id from legacy_widget_mapping where legacy_widget_id='"+id+"'"));
    }
}
