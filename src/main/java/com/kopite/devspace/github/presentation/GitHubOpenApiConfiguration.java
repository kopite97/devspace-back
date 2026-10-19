package com.kopite.devspace.github.presentation;

import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;
import java.util.*;

@Configuration(proxyBeanMethods=false)
public class GitHubOpenApiConfiguration {
    @Bean OpenApiCustomizer githubNullableFields(){return api->{
        Map<String,List<String>> fields=Map.ofEntries(
            Map.entry("TaskResponse",List.of("source")),Map.entry("TaskSnapshot",List.of("source")),
            Map.entry("GitHubLinkSnapshot",List.of("fullName","htmlUrl","accessExpiresAt")),
            Map.entry("AccountView",List.of("githubUserId","login","installationUrl")),
            Map.entry("Issue",List.of("reason")),Map.entry("Job",List.of("errorCode")),Map.entry("Item",List.of("taskId","errorCode")),
            Map.entry("TaskSourceSnapshot",List.of("statusReason","contentError","issueStateReason")),
            Map.entry("Candidate",List.of("taskId","statusReason","errorCode")),Map.entry("Preview",List.of("nextAfterIssueId")),
            Map.entry("SyncStatus",List.of("latestJob","lastTaskAppliedAt","latestCollectionJob","latestTaskJob")),
            Map.entry("Coverage",List.of("lastSuccessfulSyncAt")),Map.entry("Issues",List.of("nextCursor")),Map.entry("Labels",List.of("nextCursor")),
            Map.entry("PageInstallation",List.of("nextPage")),Map.entry("PageRepository",List.of("nextPage")),
            Map.entry("GitHubTaskMapping",List.of("todoLabelId","doingLabelId","doneLabelId")),
            Map.entry("Mapping",List.of("todoLabelId","doingLabelId","doneLabelId")),
            Map.entry("MappingPreview",List.of("todoLabelId","doingLabelId","doneLabelId","limit","afterIssueId")),
            Map.entry("ImportPreview",List.of("issueIds","limit","afterIssueId")));
        fields.forEach((name,names)->{
            Schema<?> schema=api.getComponents().getSchemas().get(name);if(schema==null||schema.getProperties()==null)return;
            for(String field:names){Schema<?> property=schema.getProperties().get(field);if(property==null)continue;
                if(property.get$ref()!=null){String ref=property.get$ref();property.set$ref(null);property.setType(null);property.setTypes(null);property.setAnyOf(List.of(new Schema<>().$ref(ref),new Schema<>().type("null")));}
                else{Set<String> types=new LinkedHashSet<>();if(property.getTypes()!=null)types.addAll(property.getTypes());else if(property.getType()!=null)types.add(property.getType());types.add("null");property.setType(null);property.setTypes(types);}
            }
        });
    };}
    @Bean OpenApiCustomizer githubHttpContract(){return api->api.getPaths().forEach((path,item)->{
        if(!path.contains("github"))return;
        item.readOperationsMap().forEach((method,operation)->{
            String verb=method.name();boolean webhook=path.equals("/api/integrations/github/webhooks");
            String status="200";
            if(verb.equals("POST")&&(path.endsWith("/authorization-requests")||path.endsWith("/repositories")))status="201";
            if(verb.equals("POST")&&(webhook||path.endsWith("/sync-requests")||path.endsWith("/issue-fetches")||path.endsWith("/task-imports")||path.endsWith("/cleanup-requests")))status="202";
            if(verb.equals("DELETE"))status=path.endsWith("/account")||path.endsWith("/github-issue")?"204":"202";
            if(path.endsWith("/callback"))status="303";
            if(!status.equals("200")){
                ApiResponse success=operation.getResponses().remove("200");if(success==null)success=new ApiResponse();
                success.setDescription(status.equals("202")?"Accepted durable background job":status.equals("303")?"Fixed configured return URL":status.equals("204")?"Completed without response body":"Created");
                if(status.equals("204")||status.equals("303"))success.setContent(null);
                operation.getResponses().addApiResponse(status,success);
            }
            for(String code:List.of("400","401","403","404","409","429","503"))operation.getResponses().addApiResponse(code,new ApiResponse().description("ApiError; see error code for recovery action").content(new Content().addMediaType("application/json",new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError")))));
            if(webhook){operation.setSecurity(List.of());operation.getResponses().addApiResponse("413",new ApiResponse().description("GITHUB_PAYLOAD_TOO_LARGE").content(new Content().addMediaType("application/json",new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError")))));}
            else if(!verb.equals("GET")){
                var parameters=operation.getParameters();
                if(parameters==null||parameters.stream().noneMatch(p->p.getName().equals("X-CSRF-Token")))operation.addParametersItem(new Parameter().name("X-CSRF-Token").in("header").required(true).schema(new StringSchema()));
            }
        });
    });}
}
