package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.domain.GitHubException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
class GitHubSourceCursor {
    private final byte[] key;
    GitHubSourceCursor(@Value("${app.project.cursor-signing-key}") String key) { this.key = key.getBytes(StandardCharsets.UTF_8); }
    String encode(UUID link, String scope, String filter, long after) {
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString((link + "\n" + scope + "\n" + filter + "\n" + after).getBytes(StandardCharsets.UTF_8));
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(body));
    }
    long decode(UUID link, String scope, String filter, String cursor) {
        if (cursor == null) return 0;
        try {
            if (cursor.length() > 4096) throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(mac(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) throw new IllegalArgumentException();
            String[] fields = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split("\n", -1);
            if (fields.length != 4 || !fields[0].equals(link.toString()) || !fields[2].equals(filter)) throw new IllegalArgumentException();
            if (!fields[1].equals(scope)) throw new GitHubException("GITHUB_ACCESS_SCOPE_CHANGED");
            long value = Long.parseLong(fields[3]); if (value <= 0) throw new IllegalArgumentException(); return value;
        } catch (GitHubException ex) { throw ex; }
        catch (RuntimeException ex) { throw new GitHubException("GITHUB_INVALID_CURSOR"); }
    }
    private byte[] mac(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) { throw new IllegalStateException("Cursor signing unavailable"); }
    }
}
