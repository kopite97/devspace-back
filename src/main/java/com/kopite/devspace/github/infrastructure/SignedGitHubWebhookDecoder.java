package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.application.port.GitHubWebhookDecoder;
import com.kopite.devspace.github.domain.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
public class SignedGitHubWebhookDecoder implements GitHubWebhookDecoder {
    private final GitHubProperties settings;
    private final JsonMapper json;
    public SignedGitHubWebhookDecoder(GitHubProperties settings, JsonMapper json) { this.settings = settings; this.json = json; }
    @Override public GitHubWebhook decode(String signature, String delivery, String event, byte[] payload) {
        if (!settings.isEnabled()) throw new GitHubException("GITHUB_NOT_CONFIGURED");
        if (signature == null || !signature.matches("sha256=[0-9a-fA-F]{64}")) throw new GitHubException("GITHUB_INVALID_SIGNATURE");
        if (payload.length > 2 * 1024 * 1024) throw new GitHubException("GITHUB_PAYLOAD_TOO_LARGE");
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal(payload), HexFormat.of().parseHex(signature.substring(7))))
                throw new GitHubException("GITHUB_INVALID_SIGNATURE");
            if (delivery == null || !delivery.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
                    || event == null || !event.matches("[a-z_]{1,64}")) throw new GitHubException("VALIDATION_ERROR");
            JsonNode root = json.readTree(payload);
            if (root == null || !root.isObject()) throw new GitHubException("VALIDATION_ERROR");
            String action = root.path("action").asString("");
            if (action.length() > 64) throw new GitHubException("VALIDATION_ERROR");
            return new GitHubWebhook(UUID.fromString(delivery), event, action, id(root.path("installation").path("id")),
                    id(root.path("repository").path("id")), id(root.path("sender").path("id")),
                    id(root.path("issue").path("number")), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)),
                    new String(payload, StandardCharsets.UTF_8));
        } catch (GitHubException ex) { throw ex; }
        catch (Exception ex) { throw new GitHubException("VALIDATION_ERROR"); }
    }
    private static Long id(JsonNode node) { long value = node.asLong(0); return value > 0 ? value : null; }
}
