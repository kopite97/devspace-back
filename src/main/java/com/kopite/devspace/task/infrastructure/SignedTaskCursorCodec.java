package com.kopite.devspace.task.infrastructure;
import com.kopite.devspace.task.application.exception.InvalidTaskCursorException;
import com.kopite.devspace.task.application.model.TaskCursor;
import com.kopite.devspace.task.application.port.TaskCursorCodec;
import com.kopite.devspace.task.application.query.TaskListFilter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class SignedTaskCursorCodec implements TaskCursorCodec {
    private final byte[] signingKey;

    public SignedTaskCursorCodec(@Value("${app.project.cursor-signing-key}") String signingKey) {
        this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
        if (this.signingKey.length < 32) throw new IllegalArgumentException("Task cursor signing key must be at least 32 UTF-8 bytes");
    }

    @Override public String encode(UUID workspace, TaskListFilter filter, TaskCursor position) {
        boolean scoped=filter.accessScope()!=null&&!filter.accessScope().equals("none");
        String payload = (scoped?"v3.":"v2.") + Base64.getUrlEncoder().withoutPadding().encodeToString(
                (position.createdAt() + "/" + position.id()).getBytes(StandardCharsets.UTF_8));
        if(scoped)payload+="."+filter.accessScope();
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(workspace, filter, payload));
    }

    @Override public TaskCursor decode(UUID workspace, TaskListFilter filter, String cursor) {
        try {
            if (cursor.length() > 512) throw new InvalidTaskCursorException();
            String[] parts = cursor.split("\\.", -1);
            boolean scoped=parts.length==4&&parts[0].equals("v3");
            if (!scoped&&(parts.length != 3 || !parts[0].equals("v2"))) throw new InvalidTaskCursorException();
            if(scoped&&!parts[2].matches("[0-9a-f]{64}"))throw new InvalidTaskCursorException();
            var original=filter.withAccessScope(scoped?parts[2]:null);
            String payload=parts[0]+"."+parts[1]+(scoped?"."+parts[2]:"");
            byte[] supplied = Base64.getUrlDecoder().decode(parts[scoped?3:2]);
            if (!MessageDigest.isEqual(sign(workspace, original, payload), supplied)) throw new InvalidTaskCursorException();
            String current=filter.accessScope()==null?"none":filter.accessScope();
            if(!(scoped?parts[2]:"none").equals(current))throw new com.kopite.devspace.task.domain.TaskConflictException("TASK_ACCESS_SCOPE_CHANGED");
            String[] position = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).split("/", -1);
            if (position.length != 2) throw new InvalidTaskCursorException();
            var decoded = new TaskCursor(Instant.parse(position[0]), UUID.fromString(position[1]));
            if (!encode(workspace, filter, decoded).equals(cursor)) throw new InvalidTaskCursorException();
            return decoded;
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new InvalidTaskCursorException();
        }
    }

    private byte[] sign(UUID workspace, TaskListFilter filter, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            // All context fields before query have fixed/enum syntax; query is length-prefixed to avoid ambiguous framing.
            String bound = "tasks:" + workspace + ":" + filter.category() + ":" + filter.projectId() + ":" + filter.projectStatus() + ":" + filter.deleted() + ":" + filter.status() + ":" + filter.limit()
                    + ":createdAt-desc,id-desc:" + filter.query().length() + ":" + filter.query() + ":" + payload;
            if (filter.sourceReason() != null) bound += ":sourceReason:" + filter.sourceReason();
            if (filter.accessScope() != null && !filter.accessScope().equals("none")) bound += ":accessScope:" + filter.accessScope();
            return mac.doFinal(bound.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException("Cannot sign project cursor", impossible); }
    }
}
