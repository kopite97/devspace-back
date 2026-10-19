package com.kopite.devspace.github.application.port;

import com.kopite.devspace.github.domain.*;
import java.time.Instant;
import java.util.*;

public interface GitHubTaskSettingsStore {
    Settings get(UUID user,UUID link,Instant now);
    Settings configure(UUID user,UUID link,long revision,boolean enabled,GitHubImportCriteria criteria,Instant now);
    Settings mapping(UUID user,UUID link,long revision,GitHubTaskMapping mapping,Instant now);
    Settings pause(UUID user,UUID link,long revision,boolean paused,Instant now);
    Preview preview(UUID user,UUID link,List<Long> ids,GitHubTaskMapping mapping,int limit,Long after,Instant now);
    record Settings(long revision,boolean enabled,long generation,boolean baselineReady,GitHubImportCriteria criteria,GitHubTaskMapping mapping,boolean paused) {}
    record Candidate(long issueId,long issueNumber,UUID taskId,String status,String statusReason,List<Long> conflictLabelIds,String errorCode,boolean matches,boolean excluded) {}
    record Preview(List<Candidate> items,long total,Long nextAfterIssueId,long revision,boolean baselineReady) {}
}
