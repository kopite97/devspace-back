package com.kopite.devspace.global.response;

import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WorkspaceOpenApiConfiguration {
    @Bean OpenApiCustomizer workspaceObservationHeader() {
        return api -> api.getPaths().forEach((path, item) -> {
            if(path.contains("/github/")||path.endsWith("/github-issue"))return;
            if (!path.startsWith("/api/v2/") && !path.startsWith("/api/v1/project-categories") && !path.startsWith("/api/v1/widgets") && !path.startsWith("/api/v3/dashboards/")) return;
            item.readOperations().forEach(operation -> operation.getResponses().forEach((status, response) -> {
                if (!status.equals("200") && !status.equals("201")) return;
                response.addHeaderObject("X-Workspace-Data-Revision", new Header()
                    .description("Workspace observation counter from the response transaction. Decimal string; do not convert to a JavaScript Number. Present for normal reads and new mutations; omitted on creation replays, which return the original snapshot. Not a resource edit revision.")
                    .schema(new StringSchema().pattern("^[0-9]+$").example("42")));
            }));
        });
    }
}
