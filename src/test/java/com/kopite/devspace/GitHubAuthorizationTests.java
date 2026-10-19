package com.kopite.devspace;

import com.kopite.devspace.github.application.GitHubAuthorizationService;
import com.kopite.devspace.github.application.port.GitHubAuthorizationClient;
import com.kopite.devspace.github.application.port.GitHubAccountStore;
import com.kopite.devspace.github.application.port.GitHubRepositoryClient;
import com.kopite.devspace.github.application.GitHubConnectionService;
import com.kopite.devspace.github.domain.GitHubException;
import com.kopite.devspace.user.application.UserWorkspaceCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties = {"app.github.enabled=true", "app.github.app-id=123", "app.github.client-id=test-client",
        "app.github.client-secret=test-only", "app.github.app-slug=test-app",
        "app.github.webhook-secret=test-only-webhook-secret-with-32-bytes",
        "app.github.callback-url=https://example.test/api/v2/integrations/github/callback",
        "app.github.frontend-return-url=https://example.test/settings/github"})
@ActiveProfiles("test") @Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class GitHubAuthorizationTests {
    @Autowired GitHubAuthorizationService service;
    @Autowired GitHubAccountStore store;
    @Autowired UserWorkspaceCreationService users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GitHubAuthorizationClient client;
    @MockitoBean GitHubRepositoryClient repositories;
    @Autowired GitHubConnectionService connections;
    @Autowired com.kopite.devspace.github.application.port.GitHubAccessStore accessStore;
    @Autowired com.kopite.devspace.github.application.port.GitHubSyncStore syncStore;
    @Autowired com.kopite.devspace.github.application.GitHubSyncService syncService;
    @Autowired com.kopite.devspace.github.application.GitHubIssueService issueService;
    @MockitoBean com.kopite.devspace.github.application.port.GitHubSourceClient source;
    @MockitoBean com.kopite.devspace.github.application.port.GitHubDeliveryClient deliveryClient;
    @Autowired com.kopite.devspace.github.application.GitHubRecoveryService recoveryService;
    @Autowired com.kopite.devspace.github.application.port.GitHubRecoveryStore recoveryStore;
    @Autowired com.kopite.devspace.github.infrastructure.GitHubHttpDispatch httpDispatch;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.kopite.devspace.github.application.port.GitHubTaskSettingsStore taskSettings;
    @Autowired com.kopite.devspace.github.application.port.GitHubTaskWorkStore taskWork;
    @Autowired com.kopite.devspace.task.application.query.TaskQueryService taskQueries;
    @Autowired com.kopite.devspace.task.application.command.TaskCommandService taskCommands;
    @Autowired com.kopite.devspace.task.application.port.TaskSourceAccess taskAccess;
    @Autowired com.kopite.devspace.github.application.port.GitHubDetachStore detachStore;
    @Autowired com.kopite.devspace.github.application.GitHubDetachService detachService;
    @Autowired com.kopite.devspace.project.application.command.ProjectCommandService projectCommands;
    @org.springframework.boot.test.web.server.LocalServerPort int serverPort;
    @DynamicPropertySource static void keys(DynamicPropertyRegistry registry) throws Exception {
        Path file = Files.createTempFile("github-test-key-", ".pem"); file.toFile().deleteOnExit();
        registry.add("app.github.private-key-path", file::toString);
        registry.add("app.github.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    private UUID user() { return users.createOrReuse("github-test", UUID.randomUUID().toString(), "Owner").user().getId(); }
    record TaskFixture(UUID user,UUID workspace,UUID project,UUID link) {}
    private TaskFixture taskFixture(long githubId) {
        UUID owner=user();stub(githubId);service.complete(owner,"session",state(service.begin(owner,"session")),"code",null);
        UUID workspace=jdbc.queryForObject("select id from workspaces where owner_user_id=?",UUID.class,owner),project=UUID.randomUUID();
        jdbc.update("insert into projects(id,workspace_id,name,stack,status,created_at,updated_at) values(?,?,'Task integration','Java','active',now(),now())",project,workspace);
        when(repositories.verify("access-secret",githubId,githubId)).thenAnswer(invocation->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return new GitHubRepositoryClient.Repository(githubId,"owner/repo","https://github.com/owner/repo",true,true);});
        UUID link=connections.connect(owner,project,githubId,githubId,"tasks").id();
        jdbc.update("update github_repository_links set status='ready',last_successful_sync_at=? where id=?",java.sql.Timestamp.from(Instant.now()),link);
        for(long label:List.of(10L,20L,30L))jdbc.update("insert into github_label_snapshots(link_id,label_id,name,color,scan_id) values(?,?,?,'ffffff',?)",link,label,"Label"+label,UUID.randomUUID());
        return new TaskFixture(owner,workspace,project,link);
    }
    private void cachedIssue(TaskFixture f,long id,String labels,boolean overflow) {
        Instant now=Instant.now();jdbc.update("insert into github_issue_snapshots(link_id,issue_id,issue_number,title,body,state,html_url,label_ids,assignee_ids,content_hash,content_overflow,source_updated_at,collected_at) values(?,?,?,'Private source','Private body','open',?,?::jsonb,'[]',?,?,?,?)",f.link(),id,id,"https://github.com/owner/repo/issues/"+id,labels,"a".repeat(64),overflow,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now));
    }
    private void localJobs(TaskFixture f,String kind) {
        for(UUID job:jdbc.queryForList("select id from github_sync_jobs where link_id=? and kind=? and state='queued' order by created_at,id",UUID.class,f.link(),kind))taskWork.apply(new com.kopite.devspace.github.application.port.GitHubTaskWorkStore.Work(job,f.link(),f.user()),Instant.now());
    }
    private void applyTaskIssue(TaskFixture f,long issue) {
        UUID job=UUID.randomUUID();Instant now=Instant.now();jdbc.update("insert into github_sync_jobs(id,link_id,kind,stage,scan_id,generation,available_at,created_at,updated_at) select ?,id,'apply',?,?,generation,?,?,? from github_repository_links where id=?",job,"issue:"+issue,UUID.randomUUID(),java.sql.Timestamp.from(now),java.sql.Timestamp.from(now),java.sql.Timestamp.from(now),f.link());
        taskWork.apply(new com.kopite.devspace.github.application.port.GitHubTaskWorkStore.Work(job,f.link(),f.user()),Instant.now());
    }
    @Test void importedTasksPreserveLocalFieldsAndHideEveryReadAfterAccessRevocation() {
        var f=taskFixture(9001);cachedIssue(f,101,"[20]",false);cachedIssue(f,102,"[]",true);
        var config=taskSettings.mapping(f.user(),f.link(),1,new com.kopite.devspace.github.domain.GitHubTaskMapping(10L,20L,30L),Instant.now());
        UUID job=taskWork.requestImport(f.user(),f.link(),config.revision(),List.of(101L,102L),"import",Instant.now());
        assertEquals(job,taskWork.requestImport(f.user(),f.link(),config.revision(),List.of(102L,101L),"import",Instant.now()));
        assertEquals("IDEMPOTENCY_KEY_REUSED",assertThrows(GitHubException.class,()->taskWork.requestImport(f.user(),f.link(),config.revision(),List.of(101L),"import",Instant.now())).code());
        // Submission cannot import a stale cached source.
        localJobs(f,"import");assertEquals(0,jdbc.queryForObject("select count(*) from tasks where workspace_id=?",Integer.class,f.workspace()));
        jdbc.update("update github_issue_snapshots set collected_at=?,collection_version=collection_version+1 where link_id=?",java.sql.Timestamp.from(Instant.now()),f.link());
        jdbc.update("update github_sync_jobs set available_at=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),job);
        localJobs(f,"import");
        assertEquals("partial",syncStore.job(f.user(),job,Instant.now()).state());
        UUID task=jdbc.queryForObject("select task_id from github_task_sources where link_id=?",UUID.class,f.link());
        var initial=taskQueries.get(f.user(),task);assertEquals("doing",initial.status());assertNotNull(initial.source());
        assertEquals("TASK_SOURCE_MANAGED",assertThrows(com.kopite.devspace.task.domain.TaskConflictException.class,()->taskCommands.update(f.user(),task,new com.kopite.devspace.task.application.command.UpdateTaskCommand(initial.revision(),"Edit",null,null,null,null,null))).getCode());
        var changed=taskCommands.update(f.user(),task,new com.kopite.devspace.task.application.command.UpdateTaskCommand(initial.revision(),null,null,null,null,"high","local"));
        jdbc.update("update github_issue_snapshots set title='',body='',content_overflow=true,state='closed',state_reason='completed',content_hash=? where link_id=? and issue_id=101","b".repeat(64),f.link());
        applyTaskIssue(f,101);var overflow=taskQueries.get(f.user(),task);
        assertEquals("Private source",overflow.title());assertEquals("Private body",overflow.description());assertEquals("done",overflow.status());assertEquals("high",overflow.priority());assertEquals("local",overflow.tag());assertEquals("CONTENT_LIMIT_EXCEEDED",overflow.source().contentError());assertEquals(changed.revision()+1,overflow.revision());
        applyTaskIssue(f,101);assertEquals(overflow.revision(),taskQueries.get(f.user(),task).revision());
        var filter=new com.kopite.devspace.task.application.query.TaskListFilter("all",null,"all","",null,false,20);
        assertEquals(1,taskQueries.stats(f.user(),filter).total());
        jdbc.update("insert into tasks(id,workspace_id,project_id,title,created_at,updated_at) values(?,?,?,'Local',now(),now())",UUID.randomUUID(),f.workspace(),f.project());
        var pagedFilter=new com.kopite.devspace.task.application.query.TaskListFilter("all",null,"all","",null,false,1);
        String cursor=taskQueries.list(f.user(),pagedFilter,null).nextCursor();assertNotNull(cursor);
        String scope=taskAccess.scopeVersion(f.workspace());accessStore.invalidateUser(9001,Instant.now());
        assertNotEquals(scope,taskAccess.scopeVersion(f.workspace()));
        assertEquals("TASK_ACCESS_SCOPE_CHANGED",assertThrows(com.kopite.devspace.task.domain.TaskConflictException.class,()->taskQueries.list(f.user(),pagedFilter,cursor)).getCode());
        assertThrows(com.kopite.devspace.task.application.exception.TaskNotFoundException.class,()->taskQueries.get(f.user(),task));
        assertThrows(com.kopite.devspace.task.application.exception.TaskNotFoundException.class,()->taskCommands.delete(f.user(),task,overflow.revision()));
        var page=taskQueries.list(f.user(),filter,null);assertEquals(1,page.total());assertEquals("Local",page.items().getFirst().title());assertTrue(page.externalDataUnavailable());assertEquals(1,taskQueries.stats(f.user(),filter).total());
        assertThrows(GitHubException.class,()->syncStore.job(f.user(),job,Instant.now()));
    }
    @Test void baselineDoesNotRetroImportAndOnlyObservedFalseToTrueCreatesTask() {
        var f=taskFixture(9002);cachedIssue(f,201,"[10]",false);cachedIssue(f,202,"[]",false);
        taskSettings.configure(f.user(),f.link(),1,true,new com.kopite.devspace.github.domain.GitHubImportCriteria(List.of(10L),List.of()),Instant.now());
        localJobs(f,"baseline");assertTrue(taskSettings.get(f.user(),f.link(),Instant.now()).baselineReady());
        applyTaskIssue(f,201);assertEquals(0,jdbc.queryForObject("select count(*) from tasks where workspace_id=?",Integer.class,f.workspace()));
        jdbc.update("update github_issue_snapshots set label_ids='[10]' where link_id=? and issue_id=202",f.link());applyTaskIssue(f,202);
        UUID task=jdbc.queryForObject("select task_id from github_task_sources where link_id=?",UUID.class,f.link());
        var value=taskQueries.get(f.user(),task);taskCommands.delete(f.user(),task,value.revision());
        jdbc.update("update github_issue_snapshots set label_ids='[]' where link_id=? and issue_id=202",f.link());applyTaskIssue(f,202);
        jdbc.update("update github_issue_snapshots set label_ids='[10]' where link_id=? and issue_id=202",f.link());applyTaskIssue(f,202);
        assertEquals(1,jdbc.queryForObject("select count(*) from tasks where workspace_id=?",Integer.class,f.workspace()));
        assertEquals(0,taskQueries.stats(f.user(),new com.kopite.devspace.task.application.query.TaskListFilter("all",null,"all","",null,false,20)).total());
    }
    @Test void pauseResumeReconcilesExistingTasksWithoutImportingPausedCandidates() {
        var f=taskFixture(9003);
        var settings=taskSettings.configure(f.user(),f.link(),1,true,new com.kopite.devspace.github.domain.GitHubImportCriteria(List.of(),List.of()),Instant.now());
        localJobs(f,"baseline");cachedIssue(f,301,"[]",false);applyTaskIssue(f,301);
        UUID task=jdbc.queryForObject("select task_id from github_task_sources where link_id=?",UUID.class,f.link());
        var paused=taskSettings.pause(f.user(),f.link(),settings.revision(),true,Instant.now());
        projectCommands.update(f.user(),f.project(),new com.kopite.devspace.project.application.command.UpdateProjectCommand(1,null,null,null,null,null,null,"archived"));
        projectCommands.update(f.user(),f.project(),new com.kopite.devspace.project.application.command.UpdateProjectCommand(2,null,null,null,null,null,null,"active"));
        paused=taskSettings.get(f.user(),f.link(),Instant.now());assertTrue(paused.paused());
        cachedIssue(f,302,"[]",false);jdbc.update("update github_issue_snapshots set state='closed',state_reason='completed' where link_id=? and issue_id=301",f.link());
        applyTaskIssue(f,301);applyTaskIssue(f,302);assertEquals("todo",taskQueries.get(f.user(),task).status());
        long pausedRevision=paused.revision();
        assertEquals("GITHUB_SYNC_PAUSED",assertThrows(GitHubException.class,()->taskWork.requestImport(f.user(),f.link(),pausedRevision,List.of(302L),"paused",Instant.now())).code());
        taskSettings.pause(f.user(),f.link(),paused.revision(),false,Instant.now());
        localJobs(f,"baseline");localJobs(f,"baseline");localJobs(f,"mapping");localJobs(f,"apply");
        assertEquals("done",taskQueries.get(f.user(),task).status());assertEquals(1,jdbc.queryForObject("select count(*) from tasks where workspace_id=?",Integer.class,f.workspace()));
        assertEquals(2,taskSettings.preview(f.user(),f.link(),null,null,20,null,Instant.now()).total());
        var value=taskQueries.get(f.user(),task);detachService.convert(f.user(),task,value.revision());
        assertNull(taskQueries.get(f.user(),task).source());assertEquals("local",jdbc.queryForObject("select source_type from tasks where id=?",String.class,task));
    }
    @Test void detachBatchesFiftyStopsOnInaccessibleSourceAndAllowsExplicitProtectedPurge() {
        var f=taskFixture(9004);List<Long> ids=new ArrayList<>();for(long id=401;id<=451;id++){cachedIssue(f,id,"[]",false);ids.add(id);}
        UUID imported=taskWork.requestImport(f.user(),f.link(),1,ids,"many",Instant.now());
        jdbc.update("update github_issue_snapshots set collected_at=?,collection_version=collection_version+1 where link_id=?",java.sql.Timestamp.from(Instant.now()),f.link());localJobs(f,"import");localJobs(f,"import");
        assertEquals(51,syncStore.job(f.user(),imported,Instant.now()).completedCount());
        var beforeDetach=taskQueries.list(f.user(),new com.kopite.devspace.task.application.query.TaskListFilter("all",null,"all","",null,false,20),null);
        assertEquals(51,beforeDetach.total());assertEquals(20,beforeDetach.items().size());assertNotNull(beforeDetach.nextCursor());
        verify(repositories,times(1)).verify("access-secret",9004,9004);
        UUID detach=detachService.request(f.user(),f.project(),f.link(),1,"convert");
        var filter=new com.kopite.devspace.task.application.query.TaskListFilter("all",null,"all","",null,false,20);assertEquals(0,taskQueries.list(f.user(),filter,null).total());
        detachService.runOnce();assertEquals(1,jdbc.queryForObject("select count(*) from github_task_sources where link_id=?",Integer.class,f.link()));
        assertEquals(50,taskQueries.stats(f.user(),filter).total());
        jdbc.update("update github_issue_snapshots set available=false where link_id=? and issue_id in (select issue_id from github_task_sources where link_id=?)",f.link(),f.link());
        detachService.runOnce();assertEquals("partial",syncService.job(f.user(),detach).state());
        accessStore.invalidateUser(9004,Instant.now());UUID purge=detachService.request(f.user(),f.project(),f.link(),2,"purge");
        detachService.runOnce();detachService.runOnce();detachService.runOnce();
        assertEquals("completed",syncService.job(f.user(),purge).state());assertEquals(50,taskQueries.stats(f.user(),filter).total());
        assertEquals(0,jdbc.queryForObject("select count(*) from github_issue_snapshots where link_id=?",Integer.class,f.link()));
        assertEquals(51,jdbc.queryForObject("select count(*) from github_issue_exclusions where workspace_id=?",Integer.class,f.workspace()));
        service.complete(f.user(),"session",state(service.begin(f.user(),"session")),"again",null);
        var reconnected=connections.connect(f.user(),f.project(),9004,9004,"reconnect");assertEquals(f.link(),reconnected.id());assertEquals("initializing",reconnected.status());
    }
    @Test void manualAndAutomaticImportsRacingCreateExactlyOneSourceTask() throws Exception {
        var f=taskFixture(9005);var settings=taskSettings.configure(f.user(),f.link(),1,true,new com.kopite.devspace.github.domain.GitHubImportCriteria(List.of(),List.of()),Instant.now());localJobs(f,"baseline");
        cachedIssue(f,501,"[]",false);UUID manual=taskWork.requestImport(f.user(),f.link(),settings.revision(),List.of(501L),"race",Instant.now());
        jdbc.update("update github_issue_snapshots set collection_version=collection_version+1 where link_id=?",f.link());
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->{start.await();applyTaskIssue(f,501);return true;});
            var b=executor.submit(()->{start.await();taskWork.apply(new com.kopite.devspace.github.application.port.GitHubTaskWorkStore.Work(manual,f.link(),f.user()),Instant.now());return true;});
            start.countDown();assertTrue(a.get(15,java.util.concurrent.TimeUnit.SECONDS));assertTrue(b.get(15,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from tasks where workspace_id=?",Integer.class,f.workspace()));
        assertEquals(1,jdbc.queryForObject("select count(*) from github_task_sources where link_id=?",Integer.class,f.link()));
    }
    @Test void firstBaselineWaitsForInitialCollectionAndImportQueueIsBounded() {
        var f=taskFixture(9007);cachedIssue(f,701,"[]",false);
        jdbc.update("update github_repository_links set last_successful_sync_at=null where id=?",f.link());
        var settings=taskSettings.configure(f.user(),f.link(),1,true,new com.kopite.devspace.github.domain.GitHubImportCriteria(List.of(),List.of()),Instant.now());
        localJobs(f,"baseline");assertFalse(taskSettings.get(f.user(),f.link(),Instant.now()).baselineReady());
        cachedIssue(f,700,"[]",false);
        jdbc.update("update github_repository_links set last_successful_sync_at=? where id=?",java.sql.Timestamp.from(Instant.now()),f.link());
        jdbc.update("update github_sync_jobs set available_at=? where link_id=? and kind='baseline'",java.sql.Timestamp.from(Instant.now()),f.link());
        localJobs(f,"baseline");assertTrue(taskSettings.get(f.user(),f.link(),Instant.now()).baselineReady());
        applyTaskIssue(f,700);assertEquals(0,jdbc.queryForObject("select count(*) from tasks where workspace_id=?",Integer.class,f.workspace()));
        UUID first=null;for(int i=0;i<10;i++){UUID job=taskWork.requestImport(f.user(),f.link(),settings.revision(),List.of(701L),"bounded-"+i,Instant.now());if(i==0)first=job;}
        assertEquals("GITHUB_IMPORT_QUEUE_FULL",assertThrows(GitHubException.class,()->taskWork.requestImport(f.user(),f.link(),settings.revision(),List.of(701L),"eleventh",Instant.now())).code());
        assertEquals(first,taskWork.requestImport(f.user(),f.link(),settings.revision(),List.of(701L),"bounded-0",Instant.now()));
    }
    @Test void sameIssueCanCreateSeparateTasksInTwoOwnedProjects() {
        var first=taskFixture(9008);cachedIssue(first,801,"[]",false);
        UUID project=UUID.randomUUID();jdbc.update("insert into projects(id,workspace_id,name,stack,status,created_at,updated_at) values(?,?,'Second project','Java','active',now(),now())",project,first.workspace());
        UUID link=connections.connect(first.user(),project,9008,9008,"second-project").id();var second=new TaskFixture(first.user(),first.workspace(),project,link);cachedIssue(second,801,"[]",false);
        for(var f:List.of(first,second)){
            taskWork.requestImport(f.user(),f.link(),1,List.of(801L),"same-issue",Instant.now());
            jdbc.update("update github_issue_snapshots set collection_version=collection_version+1 where link_id=?",f.link());localJobs(f,"import");
        }
        assertEquals(2,jdbc.queryForObject("select count(*) from github_task_sources where workspace_id=? and issue_id=801",Integer.class,first.workspace()));
        assertEquals(2,jdbc.queryForObject("select count(distinct project_id) from tasks where workspace_id=?",Integer.class,first.workspace()));
    }
    @Test void taskIntegrationHttpAndOpenApiExposeActualStatusesAndProtectMutations() throws Exception {
        var f=taskFixture(9006);cachedIssue(f,601,"[10]",false);
        jdbc.update("update github_issue_snapshots set assignee_ids='[77]' where link_id=?",f.link());
        var session=new org.springframework.mock.web.MockHttpSession();var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(new com.kopite.devspace.auth.application.InternalUserPrincipal(f.user(),"Owner"),null,List.of()));session.setAttribute("SPRING_SECURITY_CONTEXT",context);
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        String csrf=json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/csrf").session(session)).andReturn().getResponse().getContentAsString()).path("csrfToken").asString();
        String root="/api/v2/projects/"+f.project()+"/github/repositories/"+f.link();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(root+"/import-settings")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        String body="{\"revision\":1,\"enabled\":true,\"labelIds\":[10],\"assigneeIds\":[77,88]}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(root+"/import-settings").session(session).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(root+"/import-settings").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(root+"/import-settings").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(root+"/task-import-previews").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content("{}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.total").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(root+"/task-imports").session(session).header("X-CSRF-Token",csrf).header("Idempotency-Key","api").contentType("application/json").content("{\"revision\":2,\"issueIds\":[601]}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
        var apiBody=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        var api=json.readTree(apiBody);String template="/api/v2/projects/{projectId}/github/repositories/{linkId}";
        assertTrue(api.path("paths").path(template+"/task-imports").path("post").path("responses").has("202"));
        assertTrue(api.path("paths").path(template).path("delete").path("responses").has("202"));
        assertTrue(api.path("paths").path("/api/v2/tasks/{taskId}/github-issue").path("delete").path("responses").has("204"));
        var sourceSchema=api.path("components").path("schemas").path("TaskResponse").path("properties").path("source");
        assertFalse(sourceSchema.has("$ref"));assertEquals(2,sourceSchema.path("anyOf").size());
        for(String route:List.of("/import-settings","/status-mapping","/task-import-previews","/status-mapping-previews","/sync-settings","/cleanup-requests"))assertTrue(api.path("paths").has(template+route));
        Path output=Path.of("build/reports/github-api/openapi.json");Files.createDirectories(output.getParent());Files.writeString(output,apiBody);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/swagger-ui/index.html")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
    @Test void realHttpReceiverPersistsSignedDeliveryWithoutBrowserSessionAndRejectsTampering() throws Exception {
        String root="http://localhost:"+serverPort;UUID delivery=UUID.randomUUID();
        byte[] payload="{\"action\":\"opened\",\"installation\":{\"id\":999999},\"repository\":{\"id\":999999},\"issue\":{\"number\":1}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(var http=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build()){
            var account=java.net.http.HttpRequest.newBuilder(java.net.URI.create(root+"/api/v2/integrations/github/account")).timeout(java.time.Duration.ofSeconds(10)).GET().build();
            assertEquals(401,http.send(account,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
            for(String signature:List.of(signature(payload),"sha256="+"0".repeat(64))){
                var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(root+"/api/integrations/github/webhooks")).timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type","application/json").header("X-GitHub-Event","issues").header("X-GitHub-Delivery",delivery.toString()).header("X-Hub-Signature-256",signature).POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(payload)).build();
                var response=http.send(request,java.net.http.HttpResponse.BodyHandlers.discarding());assertEquals(signature.equals(signature(payload))?202:401,response.statusCode());assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
            }
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from github_webhook_inbox where delivery_id=?",Integer.class,delivery));
    }
    private String state(GitHubAuthorizationService.AuthorizationRequest request) {
        return Arrays.stream(java.net.URI.create(request.authorizationUrl()).getQuery().split("&"))
                .filter(v -> v.startsWith("state=")).findFirst().orElseThrow().substring(6);
    }
    private void stub(long githubId) {
        when(client.exchange(anyString(), anyString())).thenReturn(new GitHubAuthorizationClient.Tokens("access-secret", "refresh-secret",
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(86400)));
        when(client.identity("access-secret")).thenReturn(new GitHubAuthorizationClient.Identity(githubId, "octocat"));
    }
    @Test void oauthStateIsSessionBoundSingleUseAndSecretsAreEncrypted() {
        UUID user = user(); stub(100);
        String state = state(service.begin(user, "session-a"));
        UUID other = user();
        assertThrows(GitHubException.class, () -> service.complete(other, "session-a", state, "code", null));
        assertThrows(GitHubException.class, () -> service.complete(user, "session-b", state, "code", null));
        verify(client, never()).exchange(anyString(), anyString());
        service.complete(user, "session-a", state, "code", null);
        assertEquals(100L, service.account(user).githubUserId());
        assertEquals("access-secret", service.accessToken(user));
        String cipher = jdbc.queryForObject("select encrypted_access_token from github_accounts where user_id=?", String.class, user);
        assertFalse(cipher.contains("access-secret"));
        assertThrows(GitHubException.class, () -> service.complete(user, "session-a", state, "code", null));
        String expired = state(service.begin(user, "session-a"));
        jdbc.update("update github_oauth_states set expires_at=now()-interval '1 second' where user_id=?", user);
        assertEquals("GITHUB_INVALID_STATE", assertThrows(GitHubException.class, () -> service.complete(user, "session-a", expired, "code", null)).code());
    }
    @Test void callbackCannotReplaceExistingGithubAccount() {
        UUID user = user(); stub(200);
        service.complete(user, "session", state(service.begin(user, "session")), "first", null);
        stub(201);
        assertEquals("GITHUB_ACCOUNT_ALREADY_CONNECTED", assertThrows(GitHubException.class,
                () -> service.complete(user, "session", state(service.begin(user, "session")), "second", null)).code());
        assertEquals(200L, service.account(user).githubUserId());
    }
    @Test void refreshClaimSerializesAndExpiredClaimRequiresReauthorization() {
        UUID user = user(); stub(300);
        service.complete(user, "session", state(service.begin(user, "session")), "code", null);
        jdbc.update("update github_accounts set expires_at=now()-interval '1 second' where user_id=?", user);
        Instant now = Instant.now(); var first = store.claimRefresh(user, now);
        assertNotNull(first.claim());
        assertEquals("GITHUB_REFRESH_IN_PROGRESS", assertThrows(GitHubException.class, () -> store.claimRefresh(user, now)).code());
        assertTrue(store.claimRefresh(user, now.plusSeconds(61)).reauthorization());
        assertFalse(store.finishRefresh(user, first.claim(), first.version(), "late", "late", now.plusSeconds(3600), now.plusSeconds(7200), now.plusSeconds(62)));
        assertTrue(service.account(user).reauthorizationRequired());
    }
    @Test void consumedStateCannotBeRetriedAfterRemoteFailure() {
        UUID user = user(); String state = state(service.begin(user, "session"));
        when(client.exchange(anyString(), anyString())).thenThrow(new GitHubException("GITHUB_UNAVAILABLE"));
        assertThrows(GitHubException.class, () -> service.complete(user, "session", state, "code", null));
        assertEquals("GITHUB_INVALID_STATE", assertThrows(GitHubException.class,
                () -> service.complete(user, "session", state, "code", null)).code());
    }
    @Test void concurrentWorkersCannotBothClaimTokenRefresh() throws Exception {
        UUID owner = user(); stub(350);
        service.complete(owner, "session", state(service.begin(owner, "session")), "code", null);
        jdbc.update("update github_accounts set expires_at=now()-interval '1 second' where user_id=?", owner);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> attempt = () -> {
                start.await();
                try { return store.claimRefresh(owner, Instant.now()).claim() == null ? "missing" : "claimed"; }
                catch (GitHubException ex) { return ex.code(); }
            };
            var first = executor.submit(attempt); var second = executor.submit(attempt); start.countDown();
            assertEquals(Set.of("claimed", "GITHUB_REFRESH_IN_PROGRESS"), Set.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)));
        }
    }
    @Test void localDispatchDeferralDoesNotConsumeRefreshCredential() {
        UUID owner = user(); stub(360);
        service.complete(owner, "session", state(service.begin(owner, "session")), "code", null);
        jdbc.update("update github_accounts set expires_at=now()-interval '1 second' where user_id=?", owner);
        when(client.refresh("refresh-secret")).thenThrow(new com.kopite.devspace.github.domain.GitHubRequestDeferredException(Instant.now().plusSeconds(1)));
        assertThrows(com.kopite.devspace.github.domain.GitHubRequestDeferredException.class, () -> service.accessToken(owner));
        assertEquals("connected", service.account(owner).status());
        assertNull(jdbc.queryForObject("select refresh_claim from github_accounts where user_id=?", UUID.class, owner));
        assertNotNull(jdbc.queryForObject("select encrypted_refresh_token from github_accounts where user_id=?", String.class, owner));
    }
    @Test void credentialDispatchLeaseAndRateLimitSurviveCompetingWorkers() {
        String hash = "b".repeat(64); UUID first = httpDispatch.acquire(hash);
        assertThrows(com.kopite.devspace.github.domain.GitHubRequestDeferredException.class, () -> httpDispatch.acquire(hash));
        jdbc.update("update github_http_dispatch set lease_until=? where credential_hash=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), hash);
        UUID second = httpDispatch.acquire(hash); httpDispatch.release(hash, first, null);
        assertEquals(second, jdbc.queryForObject("select claim from github_http_dispatch where credential_hash=?", UUID.class, hash));
        Instant retry = Instant.now().plusSeconds(120); httpDispatch.release(hash, second, retry);
        var failure = assertThrows(com.kopite.devspace.github.domain.GitHubRateLimitException.class, () -> httpDispatch.acquire(hash));
        assertTrue(failure.retryAt().isAfter(Instant.now().plusSeconds(110)));
    }
    @Test void repositoryConnectionIsScopedUniqueAndReplayed() {
        UUID user = user(); stub(400);
        service.complete(user, "session", state(service.begin(user, "session")), "code", null);
        UUID workspace = jdbc.queryForObject("select id from workspaces where owner_user_id=?", UUID.class, user);
        UUID project = UUID.randomUUID();
        jdbc.update("insert into projects(id,workspace_id,name,subtitle,stack,progress,current_milestone,repository_url,status,revision,created_at,updated_at) values(?,?,?,'','Java',0,'','','active',1,now(),now())", project, workspace, "GitHub project");
        var repo = new GitHubRepositoryClient.Repository(42, "owner/repo", "https://github.com/owner/repo", true, true);
        when(repositories.verify("access-secret", 10, 42)).thenReturn(repo);
        var first = connections.connect(user, project, 10, 42, "same-key");
        assertEquals(first.id(), connections.connect(user, project, 10, 42, "same-key").id());
        assertEquals("GITHUB_REPOSITORY_ALREADY_CONNECTED", assertThrows(GitHubException.class,
                () -> connections.connect(user, project, 10, 42, "different-key")).code());
        UUID other = user();
        assertEquals("RESOURCE_NOT_FOUND", assertThrows(GitHubException.class, () -> connections.list(other, project)).code());
        assertEquals("GITHUB_ACCOUNT_IN_USE", assertThrows(GitHubException.class, () -> service.disconnect(user)).code());
        jdbc.update("update github_repository_links set access_expires_at=now()-interval '1 second' where id=?", first.id());
        when(repositories.verify("access-secret", 10, 42)).thenThrow(new GitHubException("GITHUB_UNAVAILABLE"));
        var hidden = connections.get(user, project, first.id());
        assertNull(hidden.fullName()); assertNull(hidden.htmlUrl()); assertEquals("check_pending", hidden.accessStatus());
    }
    @Test void explicitAccountDisconnectionClearsTokensAndAllowsNewIdentity() {
        UUID user = user(); stub(500);
        service.complete(user, "session", state(service.begin(user, "session")), "code", null);
        service.disconnect(user);
        assertEquals("disconnected", service.account(user).status());
        assertNull(jdbc.queryForObject("select encrypted_access_token from github_accounts where user_id=?", String.class, user));
        stub(501);
        service.complete(user, "session", state(service.begin(user, "session")), "code", null);
        assertEquals(501L, service.account(user).githubUserId());
    }
    @Test void accessVerificationIsSingleFlightAndRevocationFencesLateSuccess() {
        UUID owner = user(); stub(800);
        service.complete(owner, "session", state(service.begin(owner, "session")), "code", null);
        UUID workspace = jdbc.queryForObject("select id from workspaces where owner_user_id=?", UUID.class, owner);
        UUID project = UUID.randomUUID();
        jdbc.update("insert into projects(id,workspace_id,name,subtitle,stack,progress,current_milestone,repository_url,status,revision,created_at,updated_at) values(?,?,?,'','Java',0,'','','active',1,now(),now())", project, workspace, "Access test");
        var repo = new GitHubRepositoryClient.Repository(842, "owner/private", "https://github.com/owner/private", true, true);
        when(repositories.verify("access-secret", 810, 842)).thenAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return repo;
        });
        var link = connections.connect(owner, project, 810, 842, "access-test");
        jdbc.update("update github_repository_links set access_expires_at=now()-interval '1 second' where id=?", link.id());
        Instant now = Instant.now();
        var first = accessStore.claim(owner, link.id(), now).orElseThrow();
        assertTrue(accessStore.claim(owner, link.id(), now).isEmpty());
        accessStore.invalidateInstallation(810, 842L, now);
        accessStore.finish(first, repo, null, now.plusSeconds(1));
        assertEquals("check_pending", jdbc.queryForObject("select access_status from github_repository_links where id=?", String.class, link.id()));
        assertEquals("owner/private", connections.get(owner, project, link.id()).fullName());
        var old = service.credential(owner);
        service.complete(owner, "session", state(service.begin(owner, "session")), "new-code", null);
        assertFalse(service.rejectCredential(owner, old.version()));
        assertEquals("connected", service.account(owner).status());
        assertTrue(service.rejectCredential(owner, service.credential(owner).version()));
        var hidden = connections.get(owner, project, link.id());
        assertNull(hidden.fullName()); assertEquals("reauthorization_required", hidden.accessStatus());
    }
    @Test void installationRevokedDuringConnectCannotCreateAnAuthorizedLink() {
        UUID owner = user(); stub(900);
        service.complete(owner, "session", state(service.begin(owner, "session")), "code", null);
        UUID workspace = jdbc.queryForObject("select id from workspaces where owner_user_id=?", UUID.class, owner);
        UUID project = UUID.randomUUID();
        jdbc.update("insert into projects(id,workspace_id,name,subtitle,stack,progress,current_milestone,repository_url,status,revision,created_at,updated_at) values(?,?,?,'','Java',0,'','','active',1,now(),now())", project, workspace, "Revocation test");
        when(repositories.verify("access-secret", 910, 942)).thenAnswer(invocation -> {
            accessStore.invalidateInstallation(910, null, Instant.now());
            return new GitHubRepositoryClient.Repository(942, "owner/private", "https://github.com/owner/private", true, true);
        });
        assertEquals("GITHUB_ACCESS_DENIED", assertThrows(GitHubException.class, () -> connections.connect(owner, project, 910, 942, "revoked")).code());
        assertTrue(connections.list(owner, project).isEmpty());
    }
    @Test void lateAuthorizationCannotUndoExplicitDisconnection() {
        UUID user = user(); stub(600);
        String state = state(service.begin(user, "session"));
        when(client.identity("access-secret")).thenAnswer(invocation -> {
            service.disconnect(user);
            return new GitHubAuthorizationClient.Identity(600, "octocat");
        });
        assertEquals("GITHUB_INVALID_STATE", assertThrows(GitHubException.class,
                () -> service.complete(user, "session", state, "code", null)).code());
        assertEquals("disconnected", service.account(user).status());
    }
    @Test void staleUnauthorizedResponseRetriesLatestCredentialWithoutRevokingIt() {
        UUID owner = user(); stub(1000);
        service.complete(owner, "session", state(service.begin(owner, "session")), "code", null);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var result = service.authorized(owner, token -> {
            if (calls.getAndIncrement() == 0) {
                service.complete(owner, "session", state(service.begin(owner, "session")), "new-code", null);
                throw new GitHubException("GITHUB_REAUTHORIZATION_REQUIRED");
            }
            return "ok";
        });
        assertEquals("ok", result.value()); assertEquals(2, calls.get());
        assertEquals("connected", service.account(owner).status());
    }
    @Test void httpRequiresSessionAndCsrfAndCallbackUsesFixedDestination() throws Exception {
        UUID user = user(); stub(700);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v2/integrations/github/account"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        var session = new org.springframework.mock.web.MockHttpSession();
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new com.kopite.devspace.auth.application.InternalUserPrincipal(user, "Owner"), null, List.of()));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/integrations/github/authorization-requests").session(session))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        String csrf = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/csrf").session(session))
                .andReturn().getResponse().getContentAsString()).get("csrfToken").asString();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/integrations/github/authorization-requests")
                .session(session).header("X-CSRF-Token", csrf))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn().getResponse();
        String url = json.readTree(response.getContentAsString()).get("authorizationUrl").asString();
        String state = Arrays.stream(java.net.URI.create(url).getQuery().split("&")).filter(s -> s.startsWith("state=")).findFirst().orElseThrow().substring(6);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v2/integrations/github/callback")
                .session(session).param("state", state).param("code", "code"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isSeeOther())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "https://example.test/settings/github?github=connected"));
    }
    private String signature(byte[] bytes) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("test-only-webhook-secret-with-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(bytes));
    }
    @Test void collectionPersistsPageProgressAndExpiredWorkerCannotApply() {
        jdbc.update("update github_sync_jobs set state='completed',claim=null,lease_until=null where state in ('queued','running')");
        UUID owner = user(); stub(1200);
        service.complete(owner, "session", state(service.begin(owner, "session")), "code", null);
        UUID workspace = jdbc.queryForObject("select id from workspaces where owner_user_id=?", UUID.class, owner);
        UUID project = UUID.randomUUID();
        jdbc.update("insert into projects(id,workspace_id,name,subtitle,stack,progress,current_milestone,repository_url,status,revision,created_at,updated_at) values(?,?,?,'','Java',0,'','','active',1,now(),now())", project, workspace, "Collection test");
        when(repositories.verify("access-secret", 1210, 1242)).thenReturn(new GitHubRepositoryClient.Repository(1242, "owner/repo", "https://github.com/owner/repo", true, true));
        var link = connections.connect(owner, project, 1210, 1242, "collection");
        var issue = new com.kopite.devspace.github.application.port.GitHubSourceClient.Issue(1, 1, "Source", "Body", "open", null,
                "https://github.com/owner/repo/issues/1", List.of(10L), List.of(), "a".repeat(64), false, Instant.now());
        when(source.collect(eq(1210L), eq(1242L), eq("owner/repo"), anyString(), eq(1), any(), eq(100))).thenAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            String stage = invocation.getArgument(3);
            return new com.kopite.devspace.github.application.port.GitHubSourceClient.Page(stage.equals("open") ? List.of(issue) : List.of(),
                    stage.equals("labels") ? List.of(new com.kopite.devspace.github.application.port.GitHubSourceClient.Label(10, "Backlog", "ffffff")) : List.of(), false);
        });
        syncService.runOnce(); syncService.runOnce(); syncService.runOnce(); syncService.runOnce();
        assertEquals("ready", connections.get(owner, project, link.id()).status());
        assertEquals("Source", jdbc.queryForObject("select title from github_issue_snapshots where link_id=?", String.class, link.id()));
        var filter = new com.kopite.devspace.github.application.port.GitHubIssueQueries.Filter("open", List.of(10L), List.of(), 20);
        var found = issueService.issues(owner, project, link.id(), filter, null);
        assertEquals(1, found.total()); assertEquals("Source", found.items().getFirst().title());
        assertEquals("initial_scope_collected", found.coverage().state());
        assertEquals(0, issueService.issues(owner, project, link.id(), new com.kopite.devspace.github.application.port.GitHubIssueQueries.Filter(null, List.of(11L), List.of(), 20), null).total());
        assertEquals("Backlog", issueService.labels(owner, project, link.id(), 20, null).items().getFirst().name());
        UUID job = syncService.request(owner, project, link.id(), "manual");
        assertEquals(job, syncService.request(owner, project, link.id(), "manual"));
        assertEquals("GITHUB_RATE_LIMITED", assertThrows(GitHubException.class, () -> syncService.request(owner, project, link.id(), "other")).code());
        Instant now = Instant.now();
        var old = syncStore.claim(now).orElseThrow(); var replacement = syncStore.claim(now.plusSeconds(61)).orElseThrow();
        assertNotEquals(old.nonce(), replacement.nonce());
        syncStore.apply(old, new com.kopite.devspace.github.application.port.GitHubSourceClient.Page(List.of(), List.of(), false), now.plusSeconds(62));
        assertEquals(replacement.nonce(), jdbc.queryForObject("select claim from github_sync_jobs where id=?", UUID.class, job));
    }
    @Test void webhookRequiresExactSignaturePersistsOnceAndImmediatelyRevokesAccess() throws Exception {
        UUID owner = user(); stub(1100);
        service.complete(owner, "session", state(service.begin(owner, "session")), "code", null);
        UUID delivery = UUID.randomUUID();
        byte[] body = "{\"action\":\"revoked\",\"sender\":{\"id\":1100}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/integrations/github/webhooks")
                .header("X-GitHub-Delivery", delivery.toString()).header("X-GitHub-Event", "github_app_authorization")
                .contentType("application/json").content(body);
        mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        assertEquals("connected", service.account(owner).status());
        request.header("X-Hub-Signature-256", signature(body));
        mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
        long version = jdbc.queryForObject("select token_version from github_accounts where user_id=?", Long.class, owner);
        mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
        assertEquals(1, jdbc.queryForObject("select count(*) from github_webhook_inbox where delivery_id=?", Integer.class, delivery));
        assertEquals(version, jdbc.queryForObject("select token_version from github_accounts where user_id=?", Long.class, owner));
        assertEquals("reauthorization_required", service.account(owner).status());
        byte[] changed = "{\"action\":\"revoked\",\"sender\":{\"id\":1101}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/integrations/github/webhooks")
                .header("X-GitHub-Delivery", UUID.randomUUID().toString()).header("X-GitHub-Event", "github_app_authorization")
                .header("X-Hub-Signature-256", signature(body)).content(changed))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/integrations/github/webhooks")
                .header("X-GitHub-Delivery", delivery.toString()).header("X-GitHub-Event", "github_app_authorization")
                .header("X-Hub-Signature-256", signature(changed)).content(changed))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/integrations/github/webhooks").content(new byte[2 * 1024 * 1024 + 1]))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPayloadTooLarge());
    }
    @Test void recoveryPersistsWatermarkAndRequestsMissingDeliveryOutsideTransaction() {
        jdbc.update("update github_delivery_recovery set watermark=?,scan_started_at=null,cursor=null,next_run_at=?,claim=null,lease_until=null,gap_pending=false,after_link=null",
                java.sql.Timestamp.from(Instant.now().minusSeconds(120)), java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        UUID guid = UUID.randomUUID();
        when(deliveryClient.deliveries(null)).thenAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return new com.kopite.devspace.github.application.port.GitHubDeliveryClient.Page(List.of(
                    new com.kopite.devspace.github.application.port.GitHubDeliveryClient.Delivery(123456, guid, Instant.now(), 500, "issues", 1L, 2L)), null);
        });
        doAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()); return null;
        }).when(deliveryClient).redeliver(123456);
        recoveryService.runOnce(); recoveryService.runOnce(); recoveryService.runOnce();
        verify(deliveryClient).redeliver(123456);
        assertEquals("completed", jdbc.queryForObject("select state from github_redelivery_requests where delivery_guid=?", String.class, guid));
        assertNotNull(jdbc.queryForObject("select watermark from github_delivery_recovery", java.sql.Timestamp.class));
    }
    @Test void webhookDatabaseFailureNeverAcknowledgesDelivery() throws Exception {
        UUID delivery = UUID.randomUUID(); byte[] body = "{\"zen\":\"test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.execute("alter table github_webhook_inbox add constraint ck_test_receipt_failure check(delivery_id<>'" + delivery + "'::uuid)");
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/integrations/github/webhooks")
                    .header("X-GitHub-Delivery", delivery.toString()).header("X-GitHub-Event", "ping")
                    .header("X-Hub-Signature-256", signature(body)).content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());
            assertEquals(0, jdbc.queryForObject("select count(*) from github_webhook_inbox where delivery_id=?", Integer.class, delivery));
        } finally { jdbc.execute("alter table github_webhook_inbox drop constraint ck_test_receipt_failure"); }
    }
}
