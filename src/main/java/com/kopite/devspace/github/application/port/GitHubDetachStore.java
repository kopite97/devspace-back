package com.kopite.devspace.github.application.port;
import java.time.Instant;
import java.util.*;
public interface GitHubDetachStore {
    UUID request(UUID user,UUID project,UUID link,long revision,String disposition,Instant now);
    UUID taskLink(UUID user,UUID task);
    void convert(UUID user,UUID task,long revision,Instant now);
    Optional<Work> next(Instant now);
    void apply(Work work,Proof proof,Instant now);
    void stop(Work work,String error,Instant now);
    record Work(UUID id,UUID link,UUID user,long installation,long repository,long generation,String disposition){}
    record Proof(long accountVersion,long installationVersion,Instant expiresAt){}
}
