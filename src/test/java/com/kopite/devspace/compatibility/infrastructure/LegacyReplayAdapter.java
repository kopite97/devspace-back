package com.kopite.devspace.compatibility.infrastructure;
import com.kopite.devspace.compatibility.application.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Historical bridge fixture, excluded from application artifacts and component scanning. */
@RequiredArgsConstructor
public class LegacyReplayAdapter implements LegacyReplayStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public Optional<Replay> find(UUID workspace,CreationResource resource,String key) {
        return jdbc.query("select request_hash,response_body,response_status,expires_at from "+resource.table()
            +" where workspace_id=? and method='POST' and path=? and key=?",(r,n)->{
                String body=r.getString(2);var tree=json.readTree(body);
                var value=resource==CreationResource.LINK?tree.path("item"):tree;
                String project=value.path("projectId").asString(null);
                return new Replay(r.getString(1),body,r.getInt(3),r.getTimestamp(4).toInstant(),value.has("scope"),
                    UUID.fromString(value.path("id").asString()),project==null?null:UUID.fromString(project));
            },workspace,"/api/v1/"+resource.path(),key).stream().findFirst();
    }
}
