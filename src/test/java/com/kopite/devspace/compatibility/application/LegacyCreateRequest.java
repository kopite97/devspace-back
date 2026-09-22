package com.kopite.devspace.compatibility.application;
// Historical bridge test fixture only.
import java.util.UUID;
public record LegacyCreateRequest(CreationResource resource,String hash,UUID projectId) {}
