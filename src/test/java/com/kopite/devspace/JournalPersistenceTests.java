package com.kopite.devspace;
import com.kopite.devspace.journal.domain.Journal;
import com.kopite.devspace.journal.domain.JournalConflictException;
import com.kopite.devspace.journal.domain.JournalRepository;
import com.kopite.devspace.journal.domain.JournalValidationException;
import com.kopite.devspace.journal.domain.JournalValues;

import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.TimeZone;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class JournalPersistenceTests {
    private final UserWorkspaceCreationService users;
    private final JournalRepository journals;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final DataSource dataSource;
    @Autowired
    JournalPersistenceTests(UserWorkspaceCreationService users,JournalRepository journals,JdbcTemplate jdbc,
                            PlatformTransactionManager manager,DataSource dataSource) {
        this.users=users; this.journals=journals; this.jdbc=jdbc;
        this.transaction=new TransactionTemplate(manager); this.dataSource=dataSource;
    }
    private UUID workspace() { return users.createOrReuse("journal-persistence",UUID.randomUUID().toString(),"Owner").workspace().getId(); }
    private UUID project(UUID workspace) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into projects(id,workspace_id,name,stack,created_at,updated_at) values(?,?,'Project','Java',now(),now())",id,workspace);
        return id;
    }
    private JournalValues values(UUID project) { return new JournalValues(" Title ",project,"  Body\n ",LocalDate.of(2024,2,29)); }

    @Test
    void datesRoundTripAcrossTimezonesAndAuditRevisionDeleteRollbackRemainIndependent() {
        UUID workspace=workspace(); UUID project=project(workspace);
        Instant created=Instant.parse("2026-09-13T03:00:00.123456Z");
        TimeZone original=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            Journal saved=transaction.execute(s->{
                var space=com.kopite.devspace.documentspace.domain.DocumentSpace.create(created);
                jdbc.update("insert into document_spaces values(?,?,?)",space.getId(),java.sql.Timestamp.from(created),java.sql.Timestamp.from(created));
                return journals.save(Journal.create(workspace,space.getId(),values(project),created));
            });
            assertNotNull(saved);
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            transaction.executeWithoutResult(s->{
                var journal=journals.lockOwned(workspace,saved.getId()).orElseThrow();
                assertEquals(LocalDate.of(2024,2,29),journal.getEntryDate());
                assertEquals(created,journal.getCreatedAt()); assertEquals(created,journal.getUpdatedAt());
                assertEquals("Title",journal.getTitle()); assertEquals("  Body\n ",journal.getBody());
                assertTrue(journals.findOwned(UUID.randomUUID(),journal.getId()).isEmpty());
                journal.update(1,journal.values(),created.plusSeconds(1));
                assertEquals(2,journal.getRevision());
                assertThrows(JournalConflictException.class,()->journal.update(1,journal.values(),created));
                journal.update(2,new JournalValues("Changed",project,"Body",LocalDate.of(2026,9,1)),created.plusSeconds(2));
            });
            assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(s->{
                journals.delete(journals.lockOwned(workspace,saved.getId()).orElseThrow());
                throw new IllegalStateException("rollback deletion");
            }));
            transaction.executeWithoutResult(s->{
                var journal=journals.lockOwned(workspace,saved.getId()).orElseThrow();
                assertEquals(created,journal.getCreatedAt()); assertEquals(created.plusSeconds(2),journal.getUpdatedAt());
                assertEquals(LocalDate.of(2026,9,1),journal.getEntryDate()); assertEquals(workspace,journal.getWorkspaceId());
                journal.checkRevision(3); journals.delete(journal);
            });
            assertEquals(0L,jdbc.queryForObject("select count(*) from journals where id=?",Long.class,saved.getId()));
        } finally { TimeZone.setDefault(original); }
    }

    @Test
    void domainEnforcesUtf16WhitespaceAndStrictCalendarDates() {
        UUID project=UUID.randomUUID();
        assertEquals(120,new JournalValues("😀".repeat(60),project,"😀".repeat(10000),LocalDate.of(1,1,1)).title().length());
        assertThrows(JournalValidationException.class,()->new JournalValues("😀".repeat(61),project,"body",LocalDate.now()));
        assertThrows(JournalValidationException.class,()->new JournalValues("title",project,"😀".repeat(10001),LocalDate.now()));
        for(String blank:new String[]{null,""," \t\n","\u2003"}) {
            assertThrows(JournalValidationException.class,()->new JournalValues(blank,project,"body",LocalDate.now()));
            assertThrows(JournalValidationException.class,()->new JournalValues("title",project,blank,LocalDate.now()));
        }
        assertThrows(JournalValidationException.class,()->new JournalValues("title",null,"body",LocalDate.now()));
        assertThrows(JournalValidationException.class,()->new JournalValues("title",project,"body",null));
        for(String invalid:new String[]{"2023-02-29","2024-02-30","2024-13-01","0000-01-01","10000-01-01","2024-2-01","2024-02-29T00:00:00Z"," 2024-02-29"})
            assertThrows(JournalValidationException.class,()->JournalValues.date(invalid,"entryDate"),invalid);
        assertEquals(LocalDate.of(2024,2,29),JournalValues.date("2024-02-29","entryDate"));
        assertEquals(LocalDate.of(9999,12,31),JournalValues.date("9999-12-31","entryDate"));
    }

    @Test
    void databaseConstraintsAreComplementaryAndRejectForeignProjectAndRevisionOverflow() {
        UUID workspace=workspace(); UUID project=project(workspace); UUID id=UUID.randomUUID();
        JournalFixtures.insert(jdbc,"insert into journals(id,workspace_id,project_id,title,body,entry_date,created_at,updated_at) values(?,?,?,'Title','Body','2024-02-29',now(),now())",id,workspace,project);
        assertEquals(1L,jdbc.queryForObject("select revision from journals where id=?",Long.class,id));
        for(String assignment:new String[]{"title=null","title=' '","title=repeat('x',121)","body=''","body=null","body=repeat('x',20001)",
            "entry_date=null","entry_date='0001-01-01 BC'","entry_date='10000-01-01'","revision=0","revision=9007199254740992"})
            assertThrows(DataIntegrityViolationException.class,()->jdbc.update("update journals set "+assignment+" where id=?",id),assignment);
        UUID foreign=project(workspace());
        for(UUID target:new UUID[]{foreign,UUID.randomUUID()})
            assertThrows(DataIntegrityViolationException.class,()->jdbc.update("update journals set project_id=? where id=?",target,id));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("delete from projects where id=?",project));
        jdbc.update("update journals set revision=? where id=?",Journal.MAX_REVISION,id);
        transaction.executeWithoutResult(s->{
            var journal=journals.lockOwned(workspace,id).orElseThrow();
            assertThrows(JournalConflictException.class,()->journal.update(Journal.MAX_REVISION,journal.values(),Instant.now()));
            journal.checkRevision(Journal.MAX_REVISION); journals.delete(journal);
        });
    }

    @Test
    void upgradesPopulatedV5WithoutRewritingDataOrChecksums() {
        String schema="journal_upgrade_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("5").load().migrate();
        UUID user=UUID.randomUUID(); UUID workspace=UUID.randomUUID(); UUID project=UUID.randomUUID();
        jdbc.update("insert into "+schema+".users values(?,'Existing',now(),now(),null)",user);
        jdbc.update("insert into "+schema+".workspaces values(?,?,'Existing',7,42,now(),now())",workspace,user);
        jdbc.update("insert into "+schema+".projects(id,workspace_id,name,scope,stack,created_at,updated_at) values(?,?,'Existing','server','Java',now(),now())",project,workspace);
        jdbc.update("insert into "+schema+".tasks(id,workspace_id,project_id,title,created_at,updated_at) values(?,?,?,'Existing',now(),now())",UUID.randomUUID(),workspace,project);
        var before=jdbc.queryForMap("select * from "+schema+".tasks");
        var checksums=jdbc.queryForList("select version,checksum from "+schema+".flyway_schema_history where version is not null order by version");
        var upgraded=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("14").load();
        upgraded.migrate(); upgraded.validate();
        assertEquals(before,jdbc.queryForMap("select * from "+schema+".tasks"));
        assertEquals(42L,jdbc.queryForObject("select data_revision from "+schema+".workspaces",Long.class));
        assertEquals(checksums,jdbc.queryForList("select version,checksum from "+schema+".flyway_schema_history where version in ('1','2','3','4','5') order by version"));
        assertEquals(0L,jdbc.queryForObject("select count(*) from "+schema+".journals",Long.class));
    }
}
