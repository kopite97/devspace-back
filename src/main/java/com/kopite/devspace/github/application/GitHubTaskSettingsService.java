package com.kopite.devspace.github.application;

import com.kopite.devspace.github.application.port.*;
import com.kopite.devspace.github.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import lombok.RequiredArgsConstructor;
import java.time.Clock;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(propagation=Propagation.NEVER)
public class GitHubTaskSettingsService {
    private final GitHubLinkStore links;
    private final GitHubAccessService access;
    private final GitHubTaskSettingsStore store;
    private final Clock projectClock;
    private void prepare(UUID user,UUID project,UUID link){links.get(user,project,link);access.refresh(user,link);}
    public GitHubTaskSettingsStore.Settings get(UUID user,UUID project,UUID link){prepare(user,project,link);return store.get(user,link,projectClock.instant());}
    public GitHubTaskSettingsStore.Settings pause(UUID user,UUID project,UUID link,long revision,boolean paused){prepare(user,project,link);return store.pause(user,link,revision,paused,projectClock.instant());}
    public GitHubTaskSettingsStore.Settings configure(UUID user,UUID project,UUID link,long revision,boolean enabled,GitHubImportCriteria criteria){prepare(user,project,link);return store.configure(user,link,revision,enabled,criteria,projectClock.instant());}
    public GitHubTaskSettingsStore.Settings mapping(UUID user,UUID project,UUID link,long revision,GitHubTaskMapping mapping){prepare(user,project,link);return store.mapping(user,link,revision,mapping,projectClock.instant());}
    public GitHubTaskSettingsStore.Preview preview(UUID user,UUID project,UUID link,List<Long> ids,GitHubTaskMapping mapping,int limit,Long after){prepare(user,project,link);return store.preview(user,link,ids,mapping,limit,after,projectClock.instant());}
}
