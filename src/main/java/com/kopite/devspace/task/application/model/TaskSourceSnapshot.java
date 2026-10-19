package com.kopite.devspace.task.application.model;

import java.time.Instant;
import java.util.*;

public record TaskSourceSnapshot(String type, UUID linkId, long issueId, long issueNumber, String repository,
        String htmlUrl, String statusReason, List<Long> conflictLabelIds, String contentError, String issueState,
        String issueStateReason, boolean paused, Instant sourceUpdatedAt, Instant lastAppliedAt, List<String> editableFields) {}
