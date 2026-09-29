package com.kopite.devspace.document.presentation.dto;
import com.kopite.devspace.document.domain.DocumentException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

public final class DocumentRequests {
    private DocumentRequests(){}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @JsonDeserialize(using=CreateParser.class) @Schema(name="CreateDocumentRequest",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Create(@NotNull @Size(max=200) String title,@NotNull JsonNode content){}
    @JsonDeserialize(using=UpdateParser.class) @Schema(name="UpdateDocumentRequest",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Update(@NotNull @Size(max=200) String title,@NotNull JsonNode content,@NotNull @Size(max=100) List<UUID> attachmentIds){}
    public static UUID uuid(String value,String field) {
        try{var id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw DocumentException.invalid(field);return id;}
        catch(IllegalArgumentException|NullPointerException e){throw DocumentException.invalid(field);}
    }
    private static JsonNode tree(JsonParser p,int depth) {
        if(depth>100&&(p.currentToken()==JsonToken.START_OBJECT||p.currentToken()==JsonToken.START_ARRAY))throw DocumentException.invalid("content");
        if(p.currentToken()==JsonToken.START_OBJECT){var n=JSON.createObjectNode();var seen=new HashSet<String>();
            while(p.nextToken()!=JsonToken.END_OBJECT){if(p.currentToken()!=JsonToken.PROPERTY_NAME)throw DocumentException.invalid("body");String key=p.currentName();if(depth==0&&!seen.add(key))throw DocumentException.invalid(key);p.nextToken();n.set(key,tree(p,depth+1));}return n;}
        if(p.currentToken()==JsonToken.START_ARRAY){var n=JSON.createArrayNode();while(p.nextToken()!=JsonToken.END_ARRAY){if(p.currentToken()==null)throw DocumentException.invalid("body");n.add(tree(p,depth+1));}return n;}
        if(p.currentToken()==null)throw DocumentException.invalid("body");var f=JSON.getNodeFactory();
        return switch(p.currentToken()) {
            case VALUE_STRING->f.stringNode(p.getString());case VALUE_NUMBER_INT->f.numberNode(p.getBigIntegerValue());
            case VALUE_NUMBER_FLOAT->f.numberNode(p.getDecimalValue());case VALUE_TRUE->f.booleanNode(true);
            case VALUE_FALSE->f.booleanNode(false);case VALUE_NULL->f.nullNode();default->throw DocumentException.invalid("body");
        };
    }
    private static JsonNode body(JsonParser p,String... allowed) {
        var n=tree(p,0);if(p.nextToken()!=null||!n.isObject())throw DocumentException.invalid("body");var fields=Set.of(allowed);
        for(var entry:n.properties())if(!fields.contains(entry.getKey())||entry.getValue().isNull())throw DocumentException.invalid(entry.getKey());
        if(n.get("title")==null||!n.get("title").isString())throw DocumentException.invalid("title");
        if(n.get("content")==null||!n.get("content").isArray())throw DocumentException.invalid("content");return n;
    }
    public static class CreateParser extends ValueDeserializer<Create> {
        public Create deserialize(JsonParser p,DeserializationContext c){var n=body(p,"title","content");return new Create(n.get("title").asString(),n.get("content"));}
    }
    public static class UpdateParser extends ValueDeserializer<Update> {
        public Update deserialize(JsonParser p,DeserializationContext c) {
            var n=body(p,"title","content","attachmentIds");var ids=n.get("attachmentIds");
            if(ids==null||!ids.isArray()||ids.size()>100)throw DocumentException.invalid("attachmentIds");
            var values=new LinkedHashSet<UUID>();
            for(var id:ids){if(!id.isString()||!values.add(uuid(id.asString(),"attachmentIds")))throw DocumentException.invalid("attachmentIds");}
            return new Update(n.get("title").asString(),n.get("content"),List.copyOf(values));
        }
    }
}
