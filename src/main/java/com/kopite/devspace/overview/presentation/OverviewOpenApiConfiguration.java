package com.kopite.devspace.overview.presentation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;
import java.util.*;
@Configuration(proxyBeanMethods=false)
public class OverviewOpenApiConfiguration {
    @Bean OpenApiCustomizer overviewContractSchemas() {
        return api->{
            for(String name:List.of("OverviewResponse","OverviewProjectCounts","OverviewProjectCategoryCounts","OverviewTaskCounts")) {
                Schema<?> s=api.getComponents().getSchemas().get(name);
                if(s!=null){s.setRequired(List.copyOf(s.getProperties().keySet()));s.setAdditionalProperties(false);}
            }
            var path=api.getPaths().get("/api/v2/overview");
            var example=new LinkedHashMap<String,Object>();
            example.put("category","all");example.put("projectId",null);
            var bucket=new LinkedHashMap<String,Object>();bucket.put("categoryId",null);bucket.put("total",2);bucket.put("archived",1);
            example.put("projects",Map.of("total",2,"archived",1,"byCategory",List.of(bucket)));
            example.put("tasks",Map.of("todo",2,"doing",1,"done",3,"total",6));example.put("asOf","2026-09-13T00:00:00Z");example.put("externalDataUnavailable",false);
            if(path!=null)path.getGet().getResponses().get("200").getContent().get("application/json").addExamples("workspace",new Example().value(example));
        };
    }
}
