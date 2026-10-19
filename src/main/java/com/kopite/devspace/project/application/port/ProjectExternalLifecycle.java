package com.kopite.devspace.project.application.port;
import java.time.Instant;
import java.util.UUID;
public interface ProjectExternalLifecycle {
    void statusChanged(UUID workspace,UUID project,String previous,String current,Instant now);
}
