package com.kopite.devspace.github.domain;

import java.util.*;

public record GitHubTaskMapping(Long todoLabelId, Long doingLabelId, Long doneLabelId) {
    public GitHubTaskMapping {
        var ids = java.util.stream.Stream.of(todoLabelId, doingLabelId, doneLabelId).filter(Objects::nonNull).toList();
        if (ids.stream().anyMatch(id -> id <= 0) || ids.stream().distinct().count() != ids.size()) throw new GitHubException("VALIDATION_ERROR");
    }
    public record Decision(String status, String reason, List<Long> conflicts, Long decisionLabelId) {}
    public Decision decide(String state, String reason, Collection<Long> issueLabels, Set<Long> availableLabels, String previous, Long previousLabel) {
        String fallback = previous == null ? "todo" : previous;
        if (state.equals("closed")) {
            if ("completed".equals(reason)) return new Decision("done", null, List.of(), null);
            return new Decision(fallback, "not_planned".equals(reason) ? "not_planned" : "unknown_closure", List.of(), previousLabel);
        }
        Map<Long,String> mapping = new LinkedHashMap<>();
        if (todoLabelId != null) mapping.put(todoLabelId,"todo");
        if (doingLabelId != null) mapping.put(doingLabelId,"doing");
        if (doneLabelId != null) mapping.put(doneLabelId,"done");
        var matched = mapping.keySet().stream().filter(id -> issueLabels.contains(id) && availableLabels.contains(id)).toList();
        if (matched.size() > 1) return new Decision(fallback,"mapping_conflict",matched,previousLabel);
        if (matched.size() == 1) return new Decision(mapping.get(matched.getFirst()),null,List.of(),matched.getFirst());
        boolean affected = mapping.keySet().stream().anyMatch(id -> !availableLabels.contains(id) && (id.equals(previousLabel) || issueLabels.contains(id)));
        return affected ? new Decision(fallback,"mapping_error",List.of(),previousLabel) : new Decision("todo",null,List.of(),null);
    }
}
