package com.kopite.devspace.widget.application;
import com.kopite.devspace.overview.application.*;
import com.kopite.devspace.task.application.query.*;
import com.kopite.devspace.journal.application.query.*;
import com.kopite.devspace.milestone.application.query.*;
import com.kopite.devspace.link.application.query.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.*;
import java.util.*;
import java.util.function.BiFunction;

/** Local read adapters only; all queries join the caller's RR transaction. */
@Configuration(proxyBeanMethods=false) @RequiredArgsConstructor
public class LocalWidgetDataHandlers {
    private final OverviewQueryService overview;private final TaskQueryService tasks;private final JournalQueryService journals;
    private final MilestoneQueryService milestones;private final LinkQueryService links;
    private record Request(LocalWidgetConfig config,String cursor){}
    private WidgetDataHandler handler(String type,boolean paged,BiFunction<UUID,Request,WidgetDataHandler.Result> read) {
        return new WidgetDataHandler(){public String type(){return type;}public boolean paginated(){return paged;}public Result read(UUID user,WidgetConfig config,String cursor){return read.apply(user,new Request((LocalWidgetConfig)config,cursor));}};
    }
    private WidgetPayload.Statistics stats(TaskQueryService.Stats s){return new WidgetPayload.Statistics(s.counts().getOrDefault("todo",0L),s.counts().getOrDefault("doing",0L),s.counts().getOrDefault("done",0L),s.total(),s.asOf(),s.externalDataUnavailable());}
    @Bean WidgetDataHandler overviewData(){return handler("overview",false,(u,r)->{
        var s=r.config().selection();var v=overview.get(u,new OverviewFilter(s.categoryFilter(),s.projectId()));
        return new WidgetDataHandler.Result(new WidgetPayload.Overview(v.projects(),stats(v.tasks())),null,v.tasks().total()==0&&v.projects().stream().allMatch(c->c.active()+c.archived()==0),null);
    });}
    @Bean WidgetDataHandler boardData(){return handler("board",true,(u,r)->{
        var s=r.config().selection();var f=new TaskListFilter(s.categoryFilter(),s.projectId(),"all","",null,false,r.config().limit()==null?20:r.config().limit());
        var page=tasks.list(u,f,r.cursor());var statistics=tasks.stats(u,f);
        var columns=List.of("todo","doing","done").stream().map(status->new WidgetPayload.Column(status,page.items().stream().filter(t->t.status().equals(status)).toList())).toList();
        return new WidgetDataHandler.Result(new WidgetPayload.Board(columns,stats(statistics)),new WidgetDataEnvelope.Page(page.total(),page.nextCursor()),page.items().isEmpty(),null);
    });}
    @Bean WidgetDataHandler journalData(){return handler("journal",true,(u,r)->{
        var s=r.config().selection();var p=journals.list(u,new JournalListFilter(s.categoryFilter(),s.projectId(),"all","",null,null,"newest",r.config().limit()==null?3:r.config().limit()),r.cursor());
        return new WidgetDataHandler.Result(new WidgetPayload.Journal(p.items()),new WidgetDataEnvelope.Page(p.total(),p.nextCursor()),p.items().isEmpty(),null);
    });}
    @Bean WidgetDataHandler milestoneData(){return handler("milestone",true,(u,r)->{
        var s=r.config().selection();var p=milestones.list(u,new MilestoneListFilter(s.categoryFilter(),s.projectId(),"all","open",r.config().limit()==null?2:r.config().limit()),r.cursor());
        return new WidgetDataHandler.Result(new WidgetPayload.Milestone(p.items()),new WidgetDataEnvelope.Page(p.total(),p.nextCursor()),p.items().isEmpty(),null);
    });}
    @Bean WidgetDataHandler linksData(){return handler("links",false,(u,r)->{
        var s=r.config().selection();var p=links.list(u,new LinkListFilter(s.categoryFilter(),s.projectId(),"all",""));
        return new WidgetDataHandler.Result(new WidgetPayload.Links(p.items(),p.collectionRevision()),null,p.items().isEmpty(),null);
    });}
    @Bean WidgetDataHandler deployData(){return handler("deploy",false,(u,r)->WidgetDataHandler.Result.unavailable("NOT_CONFIGURED"));}
}
