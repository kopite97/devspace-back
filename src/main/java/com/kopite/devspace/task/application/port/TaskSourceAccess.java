package com.kopite.devspace.task.application.port;

import com.kopite.devspace.task.application.model.TaskSourceSnapshot;
import java.util.UUID;

public interface TaskSourceAccess {
    void prepare(UUID user);
    TaskSourceSnapshot requireAccessible(UUID workspace, UUID task);
    String scopeVersion(UUID workspace);
    boolean unavailable(UUID workspace);
    void deleted(UUID workspace, UUID task);
    void restored(UUID workspace, UUID task);
}
