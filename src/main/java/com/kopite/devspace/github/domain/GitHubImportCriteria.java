package com.kopite.devspace.github.domain;

import java.util.*;

public record GitHubImportCriteria(List<Long> labelIds, List<Long> assigneeIds) {
    public GitHubImportCriteria {
        labelIds = normalize(labelIds); assigneeIds = normalize(assigneeIds);
    }
    private static List<Long> normalize(List<Long> ids) {
        if (ids == null || ids.size() > 20 || ids.stream().anyMatch(id -> id == null || id <= 0)) throw new GitHubException("VALIDATION_ERROR");
        return ids.stream().distinct().sorted().toList();
    }
    public boolean matches(Collection<Long> labels, Collection<Long> assignees) {
        return labels.containsAll(labelIds) && (assigneeIds.isEmpty() || assigneeIds.stream().anyMatch(assignees::contains));
    }
}
