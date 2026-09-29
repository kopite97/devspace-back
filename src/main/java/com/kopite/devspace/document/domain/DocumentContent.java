package com.kopite.devspace.document.domain;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Opaque JSON: only syntax and the outer array are domain invariants. */
public record DocumentContent(String json) {
    private static final JsonMapper JSON=JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public DocumentContent {
        if(json==null)throw DocumentException.invalid("content");
        try {
            var node=JSON.readTree(json);
            if(node==null||!node.isArray())throw DocumentException.invalid("content");
        } catch(JacksonException e){throw DocumentException.invalid("content");}
    }
}
