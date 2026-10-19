package com.kopite.devspace.github.domain;

import java.util.UUID;

public record GitHubWebhook(UUID delivery, String type, String action, Long installation, Long repository,
        Long githubUser, Long issueNumber, String hash, String payload) {
    @Override public String toString() { return "GitHubWebhook[delivery=" + delivery + ",type=" + type + "]"; }
}
