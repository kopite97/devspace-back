package com.kopite.devspace.compatibility.application;
// Historical bridge test fixture only.
public enum CreationResource {
    PROJECT("projects"),TASK("tasks"),JOURNAL("journals"),MILESTONE("milestones"),LINK("links");
    private final String path;
    CreationResource(String path){this.path=path;}
    public String path(){return path;}
    public String table(){return name().toLowerCase(java.util.Locale.ROOT)+"_create_idempotency";}
}
