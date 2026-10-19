package com.kopite.devspace.github.application.port;
import java.time.Instant;
import java.util.*;
public interface GitHubRecoveryStore {
    Optional<Claim> claim(Instant now);
    void accept(Claim claim, GitHubDeliveryClient.Page page, Instant now);
    void finishRedelivery(Claim claim, boolean success, Instant retryAt, Instant now);
    void fail(Claim claim, String code, Instant retryAt, Instant now);
    record Claim(UUID nonce, String cursor, Instant watermark, Instant startedAt, Long redeliveryId) {}
}
