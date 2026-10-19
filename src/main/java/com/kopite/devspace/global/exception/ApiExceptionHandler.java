package com.kopite.devspace.global.exception;
import com.kopite.devspace.project.application.exception.InvalidProjectCursorException;
import com.kopite.devspace.project.application.exception.ProjectIdempotencyConflictException;
import com.kopite.devspace.project.application.exception.ProjectNotFoundException;

import com.kopite.devspace.global.response.ApiError;
import com.kopite.devspace.project.domain.ProjectRevisionConflictException;
import com.kopite.devspace.project.domain.ProjectValidationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(com.kopite.devspace.github.domain.GitHubException.class)
    ResponseEntity<ApiError> github(com.kopite.devspace.github.domain.GitHubException ex) {
        int status = switch (ex.code()) {
            case "GITHUB_INVALID_SIGNATURE" -> 401;
            case "GITHUB_PAYLOAD_TOO_LARGE" -> 413;
            case "GITHUB_DELIVERY_CONFLICT", "GITHUB_ACCESS_SCOPE_CHANGED", "GITHUB_SYNC_PAUSED", "GITHUB_LABEL_UNAVAILABLE" -> 409;
            case "RESOURCE_NOT_FOUND", "GITHUB_ACCESS_DENIED" -> 404;
            case "GITHUB_ACCOUNT_ALREADY_CONNECTED", "GITHUB_ACCOUNT_NOT_CONNECTED", "GITHUB_REAUTHORIZATION_REQUIRED", "GITHUB_REFRESH_IN_PROGRESS",
                    "PROJECT_ARCHIVED", "REVISION_CONFLICT", "IDEMPOTENCY_KEY_REUSED", "GITHUB_REPOSITORY_ALREADY_CONNECTED", "GITHUB_LINK_INACTIVE", "GITHUB_ACCOUNT_IN_USE" -> 409;
            case "GITHUB_NOT_CONFIGURED", "GITHUB_UNAVAILABLE", "GITHUB_CREDENTIALS_UNAVAILABLE", "GITHUB_RESPONSE_TOO_LARGE" -> 503;
            case "GITHUB_RATE_LIMITED", "GITHUB_LINK_LIMIT_EXCEEDED", "GITHUB_IMPORT_QUEUE_FULL" -> 429;
            default -> 400;
        };
        var response = error(status, ex.code(), "GitHub operation could not be completed", Map.of());
        if (ex instanceof com.kopite.devspace.github.domain.GitHubRateLimitException rate)
            return ResponseEntity.status(status).headers(response.getHeaders()).header("Retry-After",
                    Long.toString(Math.max(1, java.time.Duration.between(java.time.Instant.now(), rate.retryAt()).toSeconds() + 1))).body(response.getBody());
        return response;
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge(){return error(413,"PAYLOAD_TOO_LARGE","Upload exceeds the configured limit",Map.of());}
    @ExceptionHandler({org.springframework.web.multipart.MultipartException.class,org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<ApiError> invalidUpload(){return error(400,"VALIDATION_ERROR","Invalid multipart upload",Map.of());}
    @ExceptionHandler(com.kopite.devspace.document.domain.DocumentException.class)
    ResponseEntity<ApiError> document(com.kopite.devspace.document.domain.DocumentException ex) {
        int status=switch(ex.code()){case "RESOURCE_NOT_FOUND"->404;case "REVISION_CONFLICT","ATTACHMENT_IN_USE","ATTACHMENT_DELETION_PENDING"->409;case "PAYLOAD_TOO_LARGE"->413;case "STORAGE_UNAVAILABLE"->503;default->400;};
        return error(status,ex.code(),"Document request could not be completed",ex.field()==null?Map.of():Map.of(ex.field(),"Invalid, missing or unsupported value"));
    }
    @ExceptionHandler(com.kopite.devspace.widget.domain.WidgetException.class)
    ResponseEntity<ApiError> widget(com.kopite.devspace.widget.domain.WidgetException ex) {
        int status=switch(ex.code()){case "RESOURCE_NOT_FOUND"->404;case "REVISION_CONFLICT","WIDGET_IN_USE","IDEMPOTENCY_KEY_REUSED"->409;default->400;};
        return error(status,ex.code(),"Widget or layout request could not be completed",ex.field()==null?Map.of():Map.of(ex.field(),"invalid, missing, duplicate or unsupported value"));
    }
    @ExceptionHandler(com.kopite.devspace.compatibility.application.ApiVersionRetiredException.class)
    ResponseEntity<ApiError> retiredVersion() {
        return error(410,"API_VERSION_RETIRED","This API version accepts no new operations; use the current API",Map.of());
    }
    @ExceptionHandler(com.kopite.devspace.projectcategory.domain.CategoryValidationException.class)
    ResponseEntity<ApiError> categoryInvalid(com.kopite.devspace.projectcategory.domain.CategoryValidationException ex) {
        return error(400,"VALIDATION_ERROR","Request validation failed",Map.of(ex.getField(),ex.getMessage()));
    }
    @ExceptionHandler(com.kopite.devspace.projectcategory.application.CategoryNotFoundException.class)
    ResponseEntity<ApiError> categoryNotFound() {return notFound();}
    @ExceptionHandler(com.kopite.devspace.projectcategory.application.CategoryQuotaException.class)
    ResponseEntity<ApiError> categoryQuota(com.kopite.devspace.projectcategory.application.CategoryQuotaException ex) {return error(429,"QUOTA_EXCEEDED",ex.getMessage(),Map.of());}
    @ExceptionHandler(com.kopite.devspace.projectcategory.domain.CategoryConflictException.class)
    ResponseEntity<ApiError> categoryConflict(com.kopite.devspace.projectcategory.domain.CategoryConflictException ex) {
        if("CATEGORY_NAME_CONFLICT".equals(ex.getCode()))return error(409,ex.getCode(),"Category name is already used",Map.of("name","must be unique within the workspace"));
        if("CATEGORY_IN_USE".equals(ex.getCode()))return error(409,ex.getCode(),"Reassign or clear referencing Projects explicitly before deleting this Category",Map.of());
        return error(409,ex.getCode(),"Category operation conflicts with current state",Map.of());
    }
    @ExceptionHandler(com.kopite.devspace.dashboard.domain.DashboardValidationException.class)
    ResponseEntity<ApiError> dashboardInvalid(com.kopite.devspace.dashboard.domain.DashboardValidationException ex) {
        return error(400,"VALIDATION_ERROR","Request validation failed",Map.of(ex.getField(),ex.getMessage()));
    }
    @ExceptionHandler(com.kopite.devspace.dashboard.presentation.dto.UnsupportedDashboardSchemaException.class)
    ResponseEntity<ApiError> dashboardSchema() {return error(400,"UNSUPPORTED_SCHEMA_VERSION","Only schemaVersion 1 is supported",Map.of());}
    @ExceptionHandler(com.kopite.devspace.dashboard.domain.DashboardConflictException.class)
    ResponseEntity<ApiError> dashboardConflict() {return revision();}
    @ExceptionHandler(com.kopite.devspace.dashboard.application.DashboardNotFoundException.class)
    ResponseEntity<ApiError> dashboardNotFound() {return notFound();}
    @ExceptionHandler(com.kopite.devspace.overview.application.OverviewValidationException.class)
    ResponseEntity<ApiError> overviewInvalid(com.kopite.devspace.overview.application.OverviewValidationException ex) {
        return error(400,"VALIDATION_ERROR","Invalid query parameter",Map.of(ex.getField(),ex.getMessage()));
    }
    @ExceptionHandler(com.kopite.devspace.link.domain.LinkValidationException.class)
    ResponseEntity<ApiError> invalidLink(com.kopite.devspace.link.domain.LinkValidationException exception) {
        return error(400,"VALIDATION_ERROR","Request validation failed",Map.of(exception.getField(),exception.getMessage()));
    }
    @ExceptionHandler(com.kopite.devspace.link.application.exception.LinkNotFoundException.class)
    ResponseEntity<ApiError> linkNotFound() { return notFound(); }
    @ExceptionHandler(com.kopite.devspace.link.domain.LinkConflictException.class)
    ResponseEntity<ApiError> linkConflict(com.kopite.devspace.link.domain.LinkConflictException exception) {
        return error(409,exception.getCode(),"Link operation conflicts with current state",Map.of());
    }
    @ExceptionHandler(com.kopite.devspace.link.application.exception.LinkQuotaException.class)
    ResponseEntity<ApiError> linkQuota(com.kopite.devspace.link.application.exception.LinkQuotaException exception) { return error(429,"QUOTA_EXCEEDED",exception.getMessage(),Map.of()); }
    @ExceptionHandler(com.kopite.devspace.milestone.domain.MilestoneValidationException.class)
    ResponseEntity<ApiError> invalidMilestone(com.kopite.devspace.milestone.domain.MilestoneValidationException exception) {
        return error(400,"VALIDATION_ERROR","Request validation failed",Map.of(exception.getField(),exception.getMessage()));
    }
    @ExceptionHandler(com.kopite.devspace.milestone.application.exception.MilestoneNotFoundException.class)
    ResponseEntity<ApiError> milestoneNotFound() { return notFound(); }
    @ExceptionHandler(com.kopite.devspace.milestone.domain.MilestoneConflictException.class)
    ResponseEntity<ApiError> milestoneConflict(com.kopite.devspace.milestone.domain.MilestoneConflictException exception) {
        return error(409,exception.getCode(),"Milestone operation conflicts with current state",Map.of());
    }
    @ExceptionHandler(com.kopite.devspace.journal.domain.JournalValidationException.class)
    ResponseEntity<ApiError> invalidJournal(com.kopite.devspace.journal.domain.JournalValidationException exception) {
        return error(400,"VALIDATION_ERROR","Request validation failed",Map.of(exception.getField(),exception.getMessage()));
    }
    @ExceptionHandler(com.kopite.devspace.journal.application.exception.JournalNotFoundException.class)
    ResponseEntity<ApiError> journalNotFound() { return notFound(); }
    @ExceptionHandler(com.kopite.devspace.journal.domain.JournalConflictException.class)
    ResponseEntity<ApiError> journalConflict(com.kopite.devspace.journal.domain.JournalConflictException exception) {
        return error(409,exception.getCode(),"Journal operation conflicts with current state",Map.of());
    }
    @ExceptionHandler(com.kopite.devspace.milestone.application.exception.InvalidMilestoneCursorException.class)
    ResponseEntity<ApiError> milestoneCursor() { return cursor(); }
    @ExceptionHandler(com.kopite.devspace.journal.application.exception.InvalidJournalCursorException.class)
    ResponseEntity<ApiError> journalCursor() { return cursor(); }
    @ExceptionHandler(com.kopite.devspace.task.application.exception.InvalidTaskCursorException.class)
    ResponseEntity<ApiError> taskCursor() { return cursor(); }
    @ExceptionHandler(com.kopite.devspace.task.domain.TaskValidationException.class)
    ResponseEntity<ApiError> invalidTask(com.kopite.devspace.task.domain.TaskValidationException exception) {
        return error(400, "VALIDATION_ERROR", "Request validation failed", Map.of(exception.getField(), exception.getMessage()));
    }

    @ExceptionHandler(com.kopite.devspace.task.application.exception.TaskNotFoundException.class)
    ResponseEntity<ApiError> taskNotFound() { return notFound(); }

    @ExceptionHandler(com.kopite.devspace.task.domain.TaskConflictException.class)
    ResponseEntity<ApiError> taskConflict(com.kopite.devspace.task.domain.TaskConflictException exception) {
        return error(409, exception.getCode(), "Task operation conflicts with current state", Map.of());
    }
    @ExceptionHandler(ProjectValidationException.class)
    ResponseEntity<ApiError> invalid(ProjectValidationException exception) {
        return error(400, "VALIDATION_ERROR", "Request validation failed", Map.of(exception.getField(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidFields(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(field -> errors.putIfAbsent(field.getField(), field.getDefaultMessage()));
        return error(400, "VALIDATION_ERROR", "Request validation failed", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if(cause instanceof com.kopite.devspace.document.domain.DocumentException validation)return document(validation);
            if(cause instanceof com.kopite.devspace.projectcategory.domain.CategoryValidationException validation) return categoryInvalid(validation);
            if(cause instanceof com.kopite.devspace.dashboard.presentation.dto.UnsupportedDashboardSchemaException) return dashboardSchema();
            if(cause instanceof com.kopite.devspace.dashboard.domain.DashboardValidationException validation) return dashboardInvalid(validation);
            if(cause instanceof com.kopite.devspace.link.domain.LinkValidationException validation) return invalidLink(validation);
            if(cause instanceof com.kopite.devspace.milestone.domain.MilestoneValidationException validation) return invalidMilestone(validation);
            if(cause instanceof com.kopite.devspace.journal.domain.JournalValidationException validation) return invalidJournal(validation);
            if (cause instanceof com.kopite.devspace.task.domain.TaskValidationException validation) return invalidTask(validation);
            if (cause instanceof ProjectValidationException validation) return invalid(validation);
        }
        return error(400, "VALIDATION_ERROR", "Malformed request body", Map.of());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> invalidParameter(Exception exception) {
        return error(400, "VALIDATION_ERROR", "Invalid or missing request parameter", Map.of());
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    ResponseEntity<ApiError> notFound() { return error(404, "RESOURCE_NOT_FOUND", "Resource not found", Map.of()); }

    @ExceptionHandler(InvalidProjectCursorException.class)
    ResponseEntity<ApiError> cursor() { return error(400, "INVALID_CURSOR", "Cursor is invalid for this request", Map.of()); }

    @ExceptionHandler(ProjectRevisionConflictException.class)
    ResponseEntity<ApiError> revision() { return error(409, "REVISION_CONFLICT", "Reload the resource before updating", Map.of()); }

    @ExceptionHandler(ProjectIdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotency() { return error(409, "IDEMPOTENCY_KEY_REUSED", "Key was already used with another request", Map.of()); }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ApiError> routeNotFound() { return notFound(); }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method() { return error(405, "METHOD_NOT_ALLOWED", "HTTP method is not supported", Map.of()); }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType() { return error(415, "UNSUPPORTED_MEDIA_TYPE", "Content type is not supported", Map.of()); }

    @ExceptionHandler(com.kopite.devspace.document.domain.DocumentIntegrityException.class)
    ResponseEntity<ApiError> documentIntegrity(com.kopite.devspace.document.domain.DocumentIntegrityException ex) {
        var body=ApiError.of("INTERNAL_ERROR","Request could not be completed",Map.of());
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class).error("Document integrity failure; requestId={}, documentId={}, reason={}",body.requestId(),ex.documentId(),ex.getMessage());
        return ResponseEntity.status(500).cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected() { return error(500, "INTERNAL_ERROR", "Request could not be completed", Map.of()); }

    private ResponseEntity<ApiError> error(int status, String code, String message, Map<String, String> fields) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiError.of(code, message, fields));
    }
}
