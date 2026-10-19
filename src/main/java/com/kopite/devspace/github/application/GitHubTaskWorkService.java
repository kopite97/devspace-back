package com.kopite.devspace.github.application;

import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(propagation=Propagation.NEVER)
public class GitHubTaskWorkService {
    private final GitHubTaskWorkStore store;
    private final GitHubLinkStore links;
    private final GitHubAccessService access;
    private final Clock projectClock;
    public UUID request(UUID user,UUID project,UUID link,long revision,List<Long> ids,String key){links.get(user,project,link);access.refresh(user,link);return store.requestImport(user,link,revision,ids,key,projectClock.instant());}
    public void runOnce(){
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        for(int processed=0;processed<50&&System.nanoTime()<deadline;processed++){
        var work=store.next(projectClock.instant());if(work.isEmpty())return;
        var value=work.get();
        if(processed>0&&!"apply".equals(value.kind()))return;
        try{access.refreshForWorker(value.user(),value.link(),false);store.apply(value,projectClock.instant());}
        catch(GitHubException e){var now=projectClock.instant();store.defer(value,e.code(),e instanceof GitHubRateLimitException rate?rate.retryAt():now.plusSeconds(30),now);}
        catch(RuntimeException e){var now=projectClock.instant();store.defer(value,"GITHUB_TASK_APPLY_FAILED",now.plusSeconds(30),now);}
        if(!"apply".equals(value.kind()))return;
        }
    }
}
