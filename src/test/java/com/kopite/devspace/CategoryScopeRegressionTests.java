package com.kopite.devspace;

import com.kopite.devspace.project.application.command.*;
import com.kopite.devspace.project.application.query.*;
import com.kopite.devspace.projectcategory.application.*;
import com.kopite.devspace.overview.application.*;
import com.kopite.devspace.task.application.query.*;
import com.kopite.devspace.journal.application.query.*;
import com.kopite.devspace.milestone.application.query.*;
import com.kopite.devspace.dashboard.application.*;
import com.kopite.devspace.dashboard.domain.DashboardWidget;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Import(TestcontainersConfiguration.class)
class CategoryScopeRegressionTests {
    @Autowired tools.jackson.databind.json.JsonMapper json;
    @Autowired ProjectCommandService projects; @Autowired ProjectQueryService projectQueries;
    @Autowired CategoryCommandService categories; @Autowired CategoryQueryService categoryQueries;
    @Autowired OverviewQueryService overview; @Autowired TaskQueryService tasks;
    @Autowired JournalQueryService journals; @Autowired MilestoneQueryService milestones;
    @Autowired HomeDashboardCommandService dashboards; @Autowired HomeDashboardQueryService dashboardQueries;
    @Autowired UserWorkspaceCreationService users; @Autowired JdbcTemplate jdbc; @Autowired EntityManagerFactory factory;

    @Test void categoryChangesPreserveDerivedDataCountsCursorAndDashboard()throws Exception {
        var o=users.createOrReuse("category-scope",UUID.randomUUID().toString(),"Owner");UUID u=o.user().getId(),w=o.workspace().getId();
        var p=projects.create(u,"p",new CreateProjectCommand("Same",null,"Java",null,null,null));
        var p2=projects.create(u,"p2",new CreateProjectCommand("Same",null,"Java",null,null,null));
        UUID t=UUID.randomUUID(),j=UUID.randomUUID(),m=UUID.randomUUID();
        jdbc.update("insert into tasks(id,workspace_id,project_id,title,created_at,updated_at) values(?,?,?,'Task',now(),now())",t,w,p.id());
        JournalFixtures.insert(jdbc,"insert into journals(id,workspace_id,project_id,title,body,entry_date,created_at,updated_at) values(?,?,?,'Journal','Body',current_date,now(),now())",j,w,p.id());
        jdbc.update("insert into milestones(id,workspace_id,project_id,title,created_at,updated_at) values(?,?,?,'Milestone',now(),now())",m,w,p.id());
        var dashboard=dashboards.save(u,0,List.of(new DashboardWidget("p","overview","Project","small",new com.kopite.devspace.dashboard.domain.DashboardSelection("project",p.id(),null),null)));
        var tBefore=tasks.get(u,t);var jBefore=journals.get(u,j);var mBefore=milestones.get(u,m);
        var counts=overview.get(u,new OverviewFilter("all",null));
        var filter=new ProjectListFilter("all","all","",1);var page=projectQueries.list(u,filter,null);assertNotNull(page.nextCursor());
        var nextBefore=projectQueries.list(u,filter,page.nextCursor());
        var c=categories.create(u,"category","UnsearchableCategoryName");
        projects.update(u,p.id(),new UpdateProjectCommand(1,null,null,null,null,null,null,null,new ProjectCategorySelection(true,c.id().toString())));
        categories.rename(u,c.id(),1,"FreshName");
        unchangedExceptCategory(tBefore,tasks.get(u,t),c.id());unchangedExceptCategory(jBefore,journals.get(u,j),c.id());unchangedExceptCategory(mBefore,milestones.get(u,m),c.id());
        assertEquals(tBefore.revision(),tasks.get(u,t).revision());assertEquals(jBefore.revision(),journals.get(u,j).revision());assertEquals(mBefore.revision(),milestones.get(u,m).revision());
        assertEquals(tBefore.updatedAt(),tasks.get(u,t).updatedAt());assertEquals(jBefore.updatedAt(),journals.get(u,j).updatedAt());assertEquals(mBefore.updatedAt(),milestones.get(u,m).updatedAt());
        assertEquals(c.id(),tasks.get(u,t).categoryId());assertEquals(c.id(),journals.get(u,j).categoryId());assertEquals(c.id(),milestones.get(u,m).categoryId());
        assertEquals(dashboard.revision(),dashboardQueries.get(u).revision());assertEquals(dashboard.widgets(),dashboardQueries.get(u).widgets());assertEquals("project",dashboardQueries.get(u).widgets().getFirst().selection().kind());
        var after=overview.get(u,new OverviewFilter("all",null));assertEquals(2,after.projects().stream().mapToLong(ProjectCategoryCount::active).sum());assertEquals(1,after.projects().stream().filter(x->c.id().equals(x.categoryId())).mapToLong(ProjectCategoryCount::active).sum());assertEquals(counts.tasks().counts(),after.tasks().counts());
        assertEquals(nextBefore.items().stream().map(x->x.id()).toList(),projectQueries.list(u,filter,page.nextCursor()).items().stream().map(x->x.id()).toList());
        assertEquals(0,projectQueries.list(u,new ProjectListFilter("all","all","FreshName",20),null).total());
        assertEquals(1,projectQueries.list(u,new ProjectListFilter(c.id().toString(),"all","Same",20),null).total());
        assertNull(projectQueries.get(u,p2.id()).categoryId());assertEquals(c.id(),projectQueries.get(u,p.id()).categoryId());
        projects.update(u,p.id(),new UpdateProjectCommand(2,null,null,null,null,null,null,null,new ProjectCategorySelection(true,null)));
        categories.delete(u,c.id(),2);assertEquals(tBefore.revision(),tasks.get(u,t).revision());assertNull(tasks.get(u,t).categoryId());assertEquals(dashboard.revision(),dashboardQueries.get(u).revision());assertEquals(dashboard.widgets(),dashboardQueries.get(u).widgets());
        assertTrue(categoryQueries.list(u).isEmpty());
    }

    @Test void uncategorizedAndCategorizedReadsHaveConstantQueryCount()throws Exception {
        var o=users.createOrReuse("category-query",UUID.randomUUID().toString(),"Owner");UUID u=o.user().getId(),w=o.workspace().getId();
        var c=categories.create(u,"c","Category");seed(w,null);
        var stats=factory.unwrap(SessionFactory.class).getStatistics();boolean enabled=stats.isStatisticsEnabled();stats.setStatisticsEnabled(true);
        try {
            stats.clear();readAll(u,1);long single=stats.getPrepareStatementCount();
            for(int i=0;i<9;i++)seed(w,i%2==0?c.id():null);
            stats.clear();readAll(u,10);assertEquals(single,stats.getPrepareStatementCount());
            Path out=Path.of(".gradle/project-category-validation/queries");Files.createDirectories(out);
            Files.writeString(out.resolve("query-count-and-plan.txt"),"Project/Task/Journal/Milestone list query count for 1 and 10 rows: "+single+"\n"+String.join("\n",jdbc.queryForList("explain select id from projects where workspace_id=? and category_id=? limit 1",String.class,w,c.id())));
        } finally {stats.setStatisticsEnabled(enabled);}
    }
    void unchangedExceptCategory(Object before,Object after,UUID category) {
        var expected=(tools.jackson.databind.node.ObjectNode)json.valueToTree(before);var actual=(tools.jackson.databind.node.ObjectNode)json.valueToTree(after);
        assertEquals(category.toString(),actual.path("categoryId").asString());expected.remove("categoryId");actual.remove("categoryId");assertEquals(expected,actual);
    }
    void seed(UUID w,UUID c) {
        UUID p=UUID.randomUUID();jdbc.update("insert into projects(id,workspace_id,name,stack,category_id,created_at,updated_at) values(?,?,'P','Java',?,now(),now())",p,w,c);
        jdbc.update("insert into tasks(id,workspace_id,project_id,title,created_at,updated_at) values(?,?,?,'T',now(),now())",UUID.randomUUID(),w,p);
        JournalFixtures.insert(jdbc,"insert into journals(id,workspace_id,project_id,title,body,entry_date,created_at,updated_at) values(?,?,?,'J','B',current_date,now(),now())",UUID.randomUUID(),w,p);
        jdbc.update("insert into milestones(id,workspace_id,project_id,title,created_at,updated_at) values(?,?,?,'M',now(),now())",UUID.randomUUID(),w,p);
    }
    void readAll(UUID u,int count) {
        assertEquals(count,projectQueries.list(u,new ProjectListFilter("all","all","",100),null).items().size());
        assertEquals(count,tasks.list(u,new TaskListFilter("all",null,"all","",null,false,100),null).items().size());
        assertEquals(count,journals.list(u,new JournalListFilter("all",null,"all","",null,null,"newest",100),null).items().size());
        assertEquals(count,milestones.list(u,new MilestoneListFilter("all",null,"all","all",100),null).items().size());
    }
}
