package com.kopite.devspace.github.application.port;

import java.time.Instant;
import java.util.*;

public interface GitHubTaskWorkStore {
    UUID requestImport(UUID user,UUID link,long revision,List<Long> ids,String key,Instant now);
    Optional<Work> next(Instant now);
    void apply(Work work,Instant now);
    void defer(Work work,String code,Instant retryAt,Instant now);
    record Work(UUID id,UUID link,UUID user,String kind) {
        public Work(UUID id,UUID link,UUID user){this(id,link,user,null);}
    }
}
