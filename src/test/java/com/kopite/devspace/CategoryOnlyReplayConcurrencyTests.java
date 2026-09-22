package com.kopite.devspace;
import com.kopite.devspace.compatibility.application.*;
import com.kopite.devspace.project.application.command.*;
import com.kopite.devspace.project.application.model.ProjectSnapshot;
import com.kopite.devspace.project.application.exception.ProjectIdempotencyConflictException;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import com.kopite.devspace.workspace.domain.PersonalWorkspaceRepository;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Import({TestcontainersConfiguration.class,LegacyReplayService.class,
    com.kopite.devspace.compatibility.infrastructure.LegacyReplayAdapter.class})
class CategoryOnlyReplayConcurrencyTests {
    @Autowired ProjectCommandService projects; @Autowired LegacyReplayService legacy;
    @Autowired UserWorkspaceCreationService users; @Autowired PersonalWorkspaceRepository workspaces;
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
    @RepeatedTest(3) void bothVersionOrdersShareOneCreationNamespace()throws Exception {
        var evidence=new StringBuilder();
        for(boolean legacyFirst:new boolean[]{true,false}) {
            var o=users.createOrReuse("version-race",UUID.randomUUID().toString(),"Owner");var u=o.user().getId();var w=o.workspace().getId();
            var old=new LegacyCreateRequest(CreationResource.PROJECT,LegacyFingerprint.of(1,"Project",null,"unity","Java",null,null,null),null);
            Callable<Object> v1=()->legacy.replay(u,"key",old),v2=()->projects.create(u,"key",new CreateProjectCommand("Project",null,"Java",null,null,null));
            var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);var pid=new AtomicInteger();
            try(var pool=Executors.newFixedThreadPool(2)) {
                var first=pool.submit(()->tx(()->{workspaces.lockByOwnerId(u).orElseThrow();locked.countDown();if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");return(legacyFirst?v1:v2).call();}));
                assertTrue(locked.await(10,TimeUnit.SECONDS));
                var second=pool.submit(()->tx(()->{pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));started.countDown();return(legacyFirst?v2:v1).call();}));
                try {
                    assertTrue(started.await(10,TimeUnit.SECONDS));boolean waiting=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    while(System.nanoTime()<until){waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get()));if(waiting)break;Thread.sleep(10);}
                    assertTrue(waiting,"The losing version must actually wait for the workspace lock");
                } finally {release.countDown();}
                Object a=first.get(20,TimeUnit.SECONDS),b=second.get(20,TimeUnit.SECONDS);
                if(legacyFirst){assertInstanceOf(ApiVersionRetiredException.class,a);assertInstanceOf(ProjectSnapshot.class,b);}
                else {assertInstanceOf(ProjectSnapshot.class,a);assertInstanceOf(ProjectIdempotencyConflictException.class,b);}
                assertEquals(1L,jdbc.queryForObject("select data_revision from workspaces where id=?",Long.class,w));
                assertEquals(1L,jdbc.queryForObject("select count(*) from projects where workspace_id=?",Long.class,w));
                assertEquals(1L,jdbc.queryForObject("select count(*) from project_create_idempotency where workspace_id=? and path='/api/v1/projects' and key='key'",Long.class,w));
                evidence.append("legacyFirst=").append(legacyFirst).append(" observed PostgreSQL Lock; exactly one v2 creation and counter increment\n");
            } finally {release.countDown();}
        }
        Path dir=Path.of(".gradle/project-category-only-validation/concurrency");Files.createDirectories(dir);Files.writeString(dir.resolve("version-race-"+UUID.randomUUID()+".txt"),evidence);
    }
    Object tx(Callable<Object> action) {
        try{return new TransactionTemplate(manager).execute(s->{try{return action.call();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}});}
        catch(ApiVersionRetiredException|ProjectIdempotencyConflictException expected){return expected;}
    }
}
