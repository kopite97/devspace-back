package com.kopite.devspace;
import com.kopite.devspace.widget.application.*;
import com.kopite.devspace.widget.domain.WidgetRepository;
import com.kopite.devspace.dashboard.application.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class WidgetDataTests extends WidgetTestSupport {
    @Autowired WidgetDataService data;@Autowired WidgetCommandService commands;@Autowired HomeLayoutCommandService layouts;@Autowired HomeLayoutQueryService layoutQueries;
    @Autowired EntityManagerFactory factory;@MockitoSpyBean WidgetRepository widgets;
    @MockitoSpyBean com.kopite.devspace.task.application.query.TaskQueryService taskQueries;
    UUID project(Owner o){UUID p=UUID.randomUUID();jdbc.update("insert into projects(id,workspace_id,name,stack,created_at,updated_at) values(?,?,'Project','Java',now(),now())",p,o.workspace());return p;}
    void tasks(Owner o,UUID p,int n){for(int i=0;i<n;i++)jdbc.update("insert into tasks(id,workspace_id,project_id,title,status,created_at,updated_at) values(?,?,?,? ,?,now(),now())",UUID.randomUUID(),o.workspace(),p,"Task "+i,List.of("todo","doing","done").get(i%3));}
    @Test void eachTypeHasTypedEmptyOrUnavailableAndNoGetWrites()throws Exception {
        var o=owner();for(String type:List.of("overview","board","journal","milestone","links","deploy")){
            var w=create(o,type);long revision=counter(o);var response=request(o,get(W+"/"+id(w)+"/data"),null,200);var value=tree(response);
            assertEquals(Long.toString(revision),response.getHeader("X-Workspace-Data-Revision"));assertEquals(1,value.path("configRevision").asLong());assertEquals(revision,counter(o));
            assertTrue(value.path("sourceObservedAt").isNull());assertTrue(value.path("lastSuccessfulSyncAt").isNull());
            if(type.equals("deploy")){assertEquals("unavailable",value.path("availability").asString());assertEquals("unknown",value.path("freshness").asString());assertTrue(value.path("data").isNull());assertEquals("NOT_CONFIGURED",value.path("problem").path("code").asString());}
            else {assertEquals("empty",value.path("availability").asString());assertEquals("current",value.path("freshness").asString());assertEquals(type,value.path("data").path("kind").asString());assertTrue(value.path("problem").isNull());}
        }
    }
    @Test void globalBoardBudgetFullStatisticsAndConfigBoundCursor()throws Exception {
        var o=owner();UUID p=project(o);tasks(o,p,5);var w=create(o,"board");String id=id(w);
        request(o,put(W+"/"+id),update(1).replace("\"selection\":{\"kind\":\"all\"}","\"selection\":{\"kind\":\"all\"},\"limit\":2"),200);
        var first=tree(request(o,get(W+"/"+id+"/data"),null,200));assertEquals(5,first.path("data").path("statistics").path("total").asLong());int count=0;for(var column:first.path("data").path("columns"))count+=column.path("items").size();assertEquals(2,count);
        String cursor=first.path("page").path("nextCursor").asString();request(o,get(W+"/"+id+"/data").param("cursor",cursor),null,200);
        var other=create(o,"board");request(o,get(W+"/"+id(other)+"/data").param("cursor",cursor),null,400);
        request(o,put(W+"/"+id),update(2),200);request(o,get(W+"/"+id+"/data").param("cursor",cursor),null,400);
        request(o,get(W+"/"+id+"/data?limit=5"),null,400);request(owner(),get(W+"/"+id+"/data"),null,404);
    }
    @Test void journalMilestoneAndLinkCompositionMatchExistingQueries()throws Exception {
        var o=owner();UUID p=project(o);for(int i=0;i<5;i++){
            JournalFixtures.insert(jdbc,"insert into journals(id,workspace_id,project_id,title,body,entry_date,created_at,updated_at) values(?,?,?,'Journal','Body',current_date,now(),now())",UUID.randomUUID(),o.workspace(),p);
            jdbc.update("insert into milestones(id,workspace_id,project_id,title,completed,created_at,updated_at) values(?,?,?,'Milestone',?,now(),now())",UUID.randomUUID(),o.workspace(),p,i==4);
        }
        var j=create(o,"journal");var m=create(o,"milestone");assertEquals(3,tree(request(o,get(W+"/"+id(j)+"/data"),null,200)).path("data").path("items").size());
        var milestones=tree(request(o,get(W+"/"+id(m)+"/data"),null,200));assertEquals(2,milestones.path("data").path("items").size());assertEquals(4,milestones.path("page").path("total").asLong());
        jdbc.update("insert into link_collections values(?,0)",o.workspace());
        jdbc.update("insert into links(id,workspace_id,label,url,position,project_id,created_at,updated_at) values(?,?,'Unlinked','https://example.test',0,null,now(),now()),(?,?,'Linked','https://example.test',1,?,now(),now())",UUID.randomUUID(),o.workspace(),UUID.randomUUID(),o.workspace(),p);
        var l=create(o,"links");var all=tree(request(o,get(W+"/"+id(l)+"/data"),null,200));assertEquals(2,all.path("data").path("items").size());assertTrue(all.path("page").isNull());
        request(o,put(W+"/"+id(l)),update(1).replace("\"kind\":\"all\"","\"kind\":\"project\",\"projectId\":\""+p+"\""),200);
        var filtered=tree(request(o,get(W+"/"+id(l)+"/data"),null,200));assertEquals(1,filtered.path("data").path("items").size());assertEquals("Project",filtered.path("data").path("items").get(0).path("projectName").asString());
    }
    @Test void missingCategoryIsUnavailableAndNeverBroadensToAll()throws Exception {
        var o=owner();tasks(o,project(o),4);var w=create(o,"board");UUID category=UUID.randomUUID();
        jdbc.update("update widgets set config=?::jsonb where id=?","{\"selection\":{\"kind\":\"category\",\"categoryId\":\""+category+"\"}}",UUID.fromString(id(w)));
        long before=counter(o);var n=tree(request(o,get(W+"/"+id(w)+"/data"),null,200));assertEquals("unavailable",n.path("availability").asString());assertEquals("REFERENCE_MISSING",n.path("problem").path("code").asString());assertTrue(n.path("data").isNull());assertTrue(n.path("page").isNull());assertEquals(before,counter(o));
    }
    @Test void delayedReadKeepsOldConfigurationAndWorkspaceSnapshot()throws Exception {
        var o=owner();var w=create(o,"board");UUID id=UUID.fromString(id(w));long before=counter(o);var once=new AtomicBoolean();
        try(var pool=Executors.newSingleThreadExecutor()) {
            doAnswer(call->{var result=call.callRealMethod();if(once.compareAndSet(false,true))pool.submit(()->commands.replace(o.user(),id,1,"New",1,"{\"selection\":{\"kind\":\"all\"},\"limit\":1}")).get(20,TimeUnit.SECONDS);return result;}).when(widgets).owned(o.workspace(),id,false);
            var old=data.get(o.user(),id,null);assertEquals(1,old.configRevision());assertEquals(before,old.dataRevision());assertEquals(before+1,counter(o));
        }
        assertEquals(2,data.get(o.user(),id,null).configRevision());
    }
    @Test void layoutConfigurationLookupDoesNotGrowPerWidget()throws Exception {
        var o=owner();var first=create(o,"board");layouts.replace(o.user(),0,List.of(new HomeLayoutCommandService.PlacementInput(UUID.fromString(id(first)),"wide")));
        var stats=factory.unwrap(SessionFactory.class).getStatistics();boolean old=stats.isStatisticsEnabled();stats.setStatisticsEnabled(true);
        try {stats.clear();layoutQueries.get(o.user());long count=stats.getPrepareStatementCount();
            var placements=new ArrayList<HomeLayoutCommandService.PlacementInput>();placements.add(new HomeLayoutCommandService.PlacementInput(UUID.fromString(id(first)),"wide"));
            for(int i=0;i<30;i++)placements.add(new HomeLayoutCommandService.PlacementInput(UUID.fromString(id(create(o,"board"))),"small"));
            layouts.replace(o.user(),1,placements);stats.clear();assertEquals(31,layoutQueries.get(o.user()).widgets().size());assertEquals(count,stats.getPrepareStatementCount());
        }finally{stats.setStatisticsEnabled(old);}
    }
    @Test void envelopeMatrixAcceptsOnlyDefinedCombinations() {
        var payload=new WidgetPayload.Links(List.of(),0);var problem=new WidgetDataEnvelope.Problem("SOURCE_UNAVAILABLE",true);
        for(String availability:List.of("ready","empty"))for(String freshness:List.of("current","stale"))new WidgetDataEnvelope(UUID.randomUUID(),"links",1,1,1,availability,freshness,Instant.now(),null,null,payload,null,freshness.equals("stale")?problem:null,1L);
        new WidgetDataEnvelope(UUID.randomUUID(),"links",1,1,1,"unavailable","unknown",Instant.now(),null,null,null,null,problem,1L);
        assertThrows(IllegalArgumentException.class,()->new WidgetDataEnvelope(UUID.randomUUID(),"links",1,1,1,"ready","current",Instant.now(),null,null,payload,null,problem,1L));
        assertThrows(IllegalArgumentException.class,()->new WidgetDataEnvelope(UUID.randomUUID(),"links",1,1,1,"empty","current",Instant.now(),null,null,null,null,null,1L));
        assertThrows(IllegalArgumentException.class,()->new WidgetDataEnvelope(UUID.randomUUID(),"links",1,1,1,"unavailable","stale",Instant.now(),null,null,null,null,problem,1L));
    }
    @Test void projectNameAndCategoryChangesRefreshPayloadAndAggregates()throws Exception {
        var o=owner();UUID p=project(o);tasks(o,p,1);var board=create(o,"board");var overview=create(o,"overview");UUID category=UUID.randomUUID();
        jdbc.update("insert into project_categories(id,workspace_id,name,created_at,updated_at) values(?,?,'Category',now(),now())",category,o.workspace());
        long before=counter(o);request(o,patch("/api/v2/projects/"+p),"{\"revision\":1,\"name\":\"Renamed\",\"categoryId\":\""+category+"\"}",200);
        var response=request(o,get(W+"/"+id(board)+"/data"),null,200);var columns=tree(response).path("data").path("columns");
        var task=columns.get(0).path("items").get(0);assertEquals("Renamed",task.path("projectName").asString());assertEquals(category.toString(),task.path("categoryId").asString());assertEquals(Long.toString(before+1),response.getHeader("X-Workspace-Data-Revision"));
        var buckets=tree(request(o,get(W+"/"+id(overview)+"/data"),null,200)).path("data").path("projects");boolean found=false;for(var bucket:buckets)if(category.toString().equals(bucket.path("categoryId").asString())){assertEquals(1,bucket.path("active").asLong());found=true;}assertTrue(found);
    }
    @Test void unexpectedQueryFailureRemainsSafe500()throws Exception {
        var o=owner();var w=create(o,"board");doThrow(new IllegalStateException("private database details")).when(taskQueries).stats(eq(o.user()),any());
        var result=tree(request(o,get(W+"/"+id(w)+"/data"),null,500));assertEquals("INTERNAL_ERROR",result.path("code").asString());assertFalse(result.has("availability"));assertFalse(result.toString().contains("private database"));
    }
}
