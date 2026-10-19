package com.kopite.devspace.github.application.port;
import com.kopite.devspace.github.domain.GitHubWebhook;
public interface GitHubWebhookDecoder {
    GitHubWebhook decode(String signature, String delivery, String event, byte[] payload);
}
