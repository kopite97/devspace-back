package com.kopite.devspace.compatibility.application;
// Historical bridge test fixture only.
import java.time.Instant;
import java.util.*;
public interface LegacyReplayStore {
    record Replay(String hash,String body,int status,Instant expiresAt,boolean legacy,UUID id,UUID projectId) {}
    Optional<Replay> find(UUID workspace,CreationResource resource,String key);
}
