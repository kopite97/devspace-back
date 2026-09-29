package com.kopite.devspace.document.domain;

public class DocumentException extends RuntimeException {
    private final String code;
    private final String field;
    public DocumentException(String code,String field) { super(code);this.code=code;this.field=field; }
    public String code(){return code;}
    public String field(){return field;}
    public static DocumentException invalid(String field){return new DocumentException("VALIDATION_ERROR",field);}
    public static DocumentException missing(){return new DocumentException("RESOURCE_NOT_FOUND",null);}
}
