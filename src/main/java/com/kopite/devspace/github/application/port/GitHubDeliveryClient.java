package com.kopite.devspace.github.application.port;
import java.time.Instant;
import java.util.*;
public interface GitHubDeliveryClient {
    Page deliveries(String cursor);
    void redeliver(long delivery);
    record Delivery(long id, UUID guid, Instant deliveredAt, int status, String event, Long installation, Long repository) {}
    record Page(List<Delivery> items, String nextCursor) { public Page { items = List.copyOf(items); } }
}
