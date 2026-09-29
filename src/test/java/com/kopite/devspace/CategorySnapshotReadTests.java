package com.kopite.devspace;
import com.kopite.devspace.project.application.command.*;
import com.kopite.devspace.project.application.query.*;
import com.kopite.devspace.project.application.port.ProjectSearchRepository;
import com.kopite.devspace.projectcategory.application.*;
import com.kopite.devspace.task.application.query.*;
import com.kopite.devspace.task.application.port.TaskSearchRepository;
import com.kopite.devspace.journal.application.query.*;
import com.kopite.devspace.journal.application.port.JournalSearchRepository;
import com.kopite.devspace.milestone.application.query.*;
import com.kopite.devspace.milestone.application.port.MilestoneSearchRepository;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SpringBootTest @ActiveProfiles("test") @Import(TestcontainersConfiguration.class)
class CategorySnapshotReadTests {
    @Autowired UserWorkspaceCreationService users; @Autowired CategoryCommandService categories; @Autowired ProjectCommandService projects;
    @Autowired ProjectQueryService projectReads; @Autowired TaskQueryService tasks; @Autowired JournalQueryService journals; @Autowired MilestoneQueryService milestones;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ProjectSearchRepository projectSearch; @MockitoSpyBean TaskSearchRepository taskSearch;
    @MockitoSpyBean JournalSearchRepository journalSearch; @MockitoSpyBean MilestoneSearchRepository milestoneSearch;
    @Test void categoryMoveAndRenameBetweenCountAndRowsKeepEachReadAndCounterInOneSnapshot()throws Exception {
        for(String resource:List.of("projects","tasks","journals","milestones")) {
            var owner=users.createOrReuse("category-snapshot",UUID.randomUUID().toString(),"Owner");var u=owner.user().getId();var w=owner.workspace().getId();
            var a=categories.create(u,"a","A");var b=categories.create(u,"b","B");
            var p=projects.create(u,"p",new CreateProjectCommand("Project",null,"Java",null,null,null,new ProjectCategorySelection(true,a.id().toString())));
            jdbc.update("insert into tasks(id,workspace_id,project_id,title,created_at,updated_at) values(?,?,?,'T',now(),now())",UUID.randomUUID(),w,p.id());
            JournalFixtures.insert(jdbc,"insert into journals(id,workspace_id,project_id,title,body,entry_date,created_at,updated_at) values(?,?,?,'J','Body',current_date,now(),now())",UUID.randomUUID(),w,p.id());
            jdbc.update("insert into milestones(id,workspace_id,project_id,title,created_at,updated_at) values(?,?,?,'M',now(),now())",UUID.randomUUID(),w,p.id());
            var once=new AtomicBoolean();
            try(var executor=Executors.newSingleThreadExecutor()) {
                org.mockito.stubbing.Answer<Object> concurrent=call->{var count=call.callRealMethod();if(once.compareAndSet(false,true))executor.submit(()->{
                    projects.update(u,p.id(),new UpdateProjectCommand(1,null,null,null,null,null,null,null,new ProjectCategorySelection(true,b.id().toString())));
                    categories.rename(u,a.id(),1,"Renamed");
                }).get(20,TimeUnit.SECONDS);return count;};
                switch(resource) {
                    case "projects" -> {doAnswer(concurrent).when(projectSearch).count(eq(w),any());var page=projectReads.list(u,new ProjectListFilter(a.id().toString(),"all","",20),null);assertEquals(1,page.total());assertEquals(a.id(),page.items().getFirst().categoryId());assertEquals(3L,page.dataRevision());}
                    case "tasks" -> {doAnswer(concurrent).when(taskSearch).count(eq(w),any());var page=tasks.list(u,new TaskListFilter(a.id().toString(),null,"all","",null,false,20),null);assertEquals(1,page.total());assertEquals(a.id(),page.items().getFirst().categoryId());assertEquals(3L,page.dataRevision());}
                    case "journals" -> {doAnswer(concurrent).when(journalSearch).count(eq(w),any());var page=journals.list(u,new JournalListFilter(a.id().toString(),null,"all","",null,null,"newest",20),null);assertEquals(1,page.total());assertEquals(a.id(),page.items().getFirst().categoryId());assertEquals(3L,page.dataRevision());}
                    case "milestones" -> {doAnswer(concurrent).when(milestoneSearch).count(eq(w),any());var page=milestones.list(u,new MilestoneListFilter(a.id().toString(),null,"all","all",20),null);assertEquals(1,page.total());assertEquals(a.id(),page.items().getFirst().categoryId());assertEquals(3L,page.dataRevision());}
                }
                assertTrue(once.get());assertEquals(5L,jdbc.queryForObject("select data_revision from workspaces where id=?",Long.class,w));
                assertEquals(b.id(),projectReads.get(u,p.id()).categoryId());assertEquals(5L,projectReads.get(u,p.id()).dataRevision());
            }
        }
    }
}
