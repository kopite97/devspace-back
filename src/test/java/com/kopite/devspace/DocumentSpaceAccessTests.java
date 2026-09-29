package com.kopite.devspace;

import com.kopite.devspace.documentspace.application.DocumentSpaceAccess;
import com.kopite.devspace.document.domain.DocumentException;
import com.kopite.devspace.auth.application.AccountDisabledException;
import com.kopite.devspace.journal.application.command.*;
import com.kopite.devspace.project.application.command.*;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Import(TestcontainersConfiguration.class)
class DocumentSpaceAccessTests {
    @Autowired DocumentSpaceAccess access;
    @Autowired JournalCommandService journals;
    @Autowired ProjectCommandService projects;
    @Autowired UserWorkspaceCreationService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    TransactionTemplate tx(){return new TransactionTemplate(manager);}
    UUID owner(){return users.createOrReuse("space-access",UUID.randomUUID().toString(),"Owner").user().getId();}
    UUID project(UUID user){return projects.create(user,UUID.randomUUID().toString(),new CreateProjectCommand("Project",null,"Java",null,null,null)).id();}
    UUID space(UUID journal){return jdbc.queryForObject("select document_space_id from journals where id=?",UUID.class,journal);}

    @Test void ownershipArchiveDisabledAndMaintenanceFollowOwnerPolicy() {
        UUID user=owner(),other=owner(),project=project(user);
        var journal=journals.create(user,"journal",new CreateJournalCommand("Journal",project,"Body","2026-01-01"));UUID space=space(journal.id());
        tx().executeWithoutResult(s->{assertNotNull(access.read(user,space));assertThrows(DocumentException.class,()->access.read(other,space));assertThrows(DocumentException.class,()->access.read(user,UUID.randomUUID()));});
        projects.update(user,project,new UpdateProjectCommand(1,null,null,null,null,null,null,"archived"));
        tx().executeWithoutResult(s->assertNotNull(access.lock(user,space)));
        jdbc.update("update users set disabled_at=now() where id=?",user);
        assertThrows(AccountDisabledException.class,()->tx().execute(s->access.lock(user,space)));
        tx().executeWithoutResult(s->assertNotNull(access.maintenance(space)));
    }
    @Test void createReplayDeleteAndParentRollbackPreserveExclusiveSpace() {
        UUID user=owner(),project=project(user);var input=new CreateJournalCommand("Journal",project,"Body","2026-01-01");
        long before=jdbc.queryForObject("select count(*) from document_spaces",Long.class);
        assertThrows(IllegalStateException.class,()->tx().executeWithoutResult(s->{journals.create(user,"rollback",input);throw new IllegalStateException();}));
        assertEquals(before,jdbc.queryForObject("select count(*) from document_spaces",Long.class));
        var journal=journals.create(user,"journal",input);UUID space=space(journal.id());
        assertEquals(journal.id(),journals.create(user,"journal",input).id());
        assertEquals(before+1,jdbc.queryForObject("select count(*) from document_spaces",Long.class));
        assertThrows(IllegalStateException.class,()->tx().executeWithoutResult(s->{journals.delete(user,journal.id(),1);throw new IllegalStateException();}));
        assertEquals(space,space(journal.id()));
        journals.delete(user,journal.id(),1);
        assertEquals(0L,jdbc.queryForObject("select count(*) from document_spaces where id=?",Long.class,space));
        assertEquals(journal.id(),journals.create(user,"journal",input).id());
        assertThrows(DocumentException.class,()->tx().execute(s->access.read(user,space)));
        assertEquals(before,jdbc.queryForObject("select count(*) from document_spaces",Long.class));
    }
    @Test void accessLockSerializesJournalMovement()throws Exception {
        UUID user=owner(),source=project(user),target=project(user);
        var journal=journals.create(user,"journal",new CreateJournalCommand("Journal",source,"Body","2026-01-01"));
        UUID space=space(journal.id());
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var read=executor.submit(()->tx().execute(s->{
                access.lock(user,space);held.countDown();
                try{assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                return jdbc.queryForObject("select project_id from journals where id=?",UUID.class,journal.id());
            }));
            assertTrue(held.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var moved=executor.submit(()->journals.update(user,journal.id(),new UpdateJournalCommand(1,null,target,null,null)));
            release.countDown();assertEquals(source,read.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(target,moved.get(15,java.util.concurrent.TimeUnit.SECONDS).projectId());
            tx().executeWithoutResult(s->assertNotNull(access.lock(user,space)));
            assertEquals(space,space(journal.id()));
        } finally {release.countDown();}
    }
}
