package com.kopite.devspace.compatibility.presentation;

import com.kopite.devspace.compatibility.application.*;
import com.kopite.devspace.project.application.command.*;
import com.kopite.devspace.project.domain.ProjectValidationException;
import com.kopite.devspace.task.presentation.dto.CreateTaskRequest;
import com.kopite.devspace.journal.presentation.dto.CreateJournalRequest;
import com.kopite.devspace.milestone.presentation.dto.CreateMilestoneRequest;
import com.kopite.devspace.link.domain.LinkValues;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.*;

/** Preserves historical request fingerprint vectors; never registered in the runtime. */
@RequiredArgsConstructor
public class LegacyCreateParser {
    private final JsonMapper json;
    public LegacyCreateRequest parse(CreationResource resource,String body) {
        return switch(resource) {
            case PROJECT -> project(body);
            case LINK -> link(body);
            case TASK -> {
                var c=json.readValue(body,CreateTaskRequest.class).command();c.values();
                yield new LegacyCreateRequest(resource,LegacyFingerprint.of(1,c.title(),c.projectId(),c.description(),c.status(),c.priority(),c.tag()),c.projectId());
            }
            case JOURNAL -> {
                var c=json.readValue(body,CreateJournalRequest.class).command();c.values();
                yield new LegacyCreateRequest(resource,LegacyFingerprint.of(1,c.title(),c.projectId(),c.body(),c.entryDate()),c.projectId());
            }
            case MILESTONE -> {
                var c=json.readValue(body,CreateMilestoneRequest.class).command();c.values();
                yield new LegacyCreateRequest(resource,LegacyFingerprint.of(1,c.title(),c.projectId(),c.dueDatePresent(),c.dueDate(),c.completed()),c.projectId());
            }
        };
    }
    private LegacyCreateRequest project(String body) {
        var f=fields(body,Set.of("name","subtitle","scope","stack","progress","currentMilestone","repositoryUrl","categoryId"));
        String scope=(String)f.get("scope");
        if(!"unity".equals(scope)&&!"server".equals(scope))throw invalid("scope");
        var selection=new ProjectCategorySelection(f.containsKey("categoryId"),(String)f.get("categoryId"));
        new CreateProjectCommand((String)f.get("name"),(String)f.get("subtitle"),(String)f.get("stack"),
            (BigDecimal)f.get("progress"),(String)f.get("currentMilestone"),(String)f.get("repositoryUrl"),selection).values();
        var values=new ArrayList<Object>();for(String k:List.of("name","subtitle","scope","stack","progress","currentMilestone","repositoryUrl"))values.add(f.get(k));
        if(selection.present())values.add(selection.rawValue());
        return new LegacyCreateRequest(CreationResource.PROJECT,LegacyFingerprint.of(selection.present()?2:1,values.toArray()),null);
    }
    private LegacyCreateRequest link(String body) {
        var f=fields(body,Set.of("label","description","url","scope"));String scope=(String)f.getOrDefault("scope","all");
        if(!Set.of("all","unity","server").contains(scope))throw invalid("scope");
        new LinkValues((String)f.get("label"),(String)f.getOrDefault("description",""),(String)f.get("url"),null);
        return new LegacyCreateRequest(CreationResource.LINK,LegacyFingerprint.of(1,f.get("label"),f.get("description"),f.get("url"),f.get("scope")),null);
    }
    private Map<String,Object> fields(String body,Set<String> allowed) {
        try(var p=json.createParser(body)) {
            if(p.nextToken()!=JsonToken.START_OBJECT)throw invalid("body");var fields=new HashMap<String,Object>();
            while(p.nextToken()!=JsonToken.END_OBJECT) {
                if(p.currentToken()!=JsonToken.PROPERTY_NAME)throw invalid("body");
                String name=p.currentName();var token=p.nextToken();
                if(!allowed.contains(name)||fields.containsKey(name))throw invalid(name);
                if(name.equals("progress")) {
                    if(token!=JsonToken.VALUE_NUMBER_INT&&token!=JsonToken.VALUE_NUMBER_FLOAT)throw invalid(name);
                    fields.put(name,p.getDecimalValue());
                } else if(name.equals("categoryId")&&token==JsonToken.VALUE_NULL)fields.put(name,null);
                else {if(token!=JsonToken.VALUE_STRING)throw invalid(name);fields.put(name,p.getString());}
            }
            if(p.nextToken()!=null)throw invalid("body");return fields;
        }
    }
    private ProjectValidationException invalid(String field){return new ProjectValidationException(field,"invalid historical creation request");}
}
