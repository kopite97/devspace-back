package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.domain.GitHubException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

@Component
public class GitHubHttpTransport {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final JsonMapper json;
    private final GitHubProperties settings;
    private final GitHubHttpDispatch dispatch;
    @org.springframework.beans.factory.annotation.Autowired
    public GitHubHttpTransport(GitHubProperties settings, JsonMapper json, GitHubHttpDispatch dispatch) { this.settings = settings; this.json = json; this.dispatch = dispatch; }
    // Standalone HTTP fault tests do not create a database-backed dispatch gate.
    GitHubHttpTransport(GitHubProperties settings, JsonMapper json) { this(settings, json, null); }

    JsonNode request(URI uri, String method, String token, String contentType, String body) {
        return response(uri, method, token, contentType, body).body();
    }
    record Response(JsonNode body, HttpHeaders headers) {}
    Response response(URI uri, String method, String token, String contentType, String body) {
        return guarded(uri, method, token, contentType, body, token == null ? "oauth:" + settings.getClientId() : token);
    }
    JsonNode requestApp(URI uri, String method, String token, String contentType, String body) { return responseApp(uri, method, token, contentType, body).body(); }
    Response responseApp(URI uri, String method, String token, String contentType, String body) {
        return guarded(uri, method, token, contentType, body, "app:" + settings.getClientId());
    }
    private Response guarded(URI uri, String method, String token, String contentType, String body, String credential) {
        if (dispatch == null) return exchange(uri, method, token, contentType, body);
        String hash;
        try { hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(credential.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        var claim = dispatch.acquire(hash); java.time.Instant retry = null;
        try { return exchange(uri, method, token, contentType, body); }
        catch (com.kopite.devspace.github.domain.GitHubRateLimitException ex) { retry = ex.retryAt(); throw ex; }
        finally { dispatch.release(hash, claim, retry); }
    }
    private Response exchange(URI uri, String method, String token, String contentType, String body) {
        try {
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/x-www-form-urlencoded".equals(contentType) ? "application/json" : "application/vnd.github+json")
                    .header("User-Agent", "DevSpace-GitHub-App")
                    .header("X-GitHub-Api-Version", settings.getApiVersion());
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body));
            var pending = client.sendAsync(builder.build(), info -> new LimitedBody());
            HttpResponse<byte[]> response;
            try { response = pending.get(10, TimeUnit.SECONDS); }
            catch (TimeoutException ex) { pending.cancel(true); throw new GitHubException("GITHUB_UNAVAILABLE"); }
            catch (InterruptedException ex) { pending.cancel(true); throw ex; }
            int status = response.statusCode();
            if (status == 429 || (status == 403 && (response.headers().firstValue("Retry-After").isPresent()
                    || response.headers().firstValue("X-RateLimit-Remaining").orElse("1").equals("0"))))
                throw new com.kopite.devspace.github.domain.GitHubRateLimitException(retryAt(response.headers()));
            if (status == 401) throw new GitHubException("GITHUB_REAUTHORIZATION_REQUIRED");
            if (status == 403 || status == 404) throw new GitHubException("GITHUB_ACCESS_DENIED", status);
            if (status < 200 || status >= 300) throw new GitHubException("GITHUB_UNAVAILABLE", status);
            return new Response(response.body().length == 0 ? json.nullNode() : json.readTree(response.body()), response.headers());
        } catch (GitHubException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new GitHubException("GITHUB_UNAVAILABLE"); }
        catch (ExecutionException ex) {
            Throwable cause = ex;
            for (int i = 0; i < 5 && cause != null; i++, cause = cause.getCause())
                if (cause instanceof ResponseLimitException) throw new GitHubException("GITHUB_RESPONSE_TOO_LARGE");
            throw new GitHubException("GITHUB_UNAVAILABLE");
        }
        catch (Exception ex) { throw new GitHubException("GITHUB_UNAVAILABLE"); }
    }

    private static java.time.Instant retryAt(HttpHeaders headers) {
        var now = java.time.Instant.now(); var retry = now.plusSeconds(60);
        try {
            String value = headers.firstValue("Retry-After").orElse("");
            if (!value.isEmpty()) {
                java.time.Instant parsed;
                try { parsed = now.plusSeconds(Long.parseLong(value)); }
                catch (NumberFormatException ex) { parsed = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
                if (parsed.isAfter(retry)) retry = parsed;
            }
            if (headers.firstValue("X-RateLimit-Remaining").orElse("1").equals("0")) {
                var reset = java.time.Instant.ofEpochSecond(Long.parseLong(headers.firstValue("X-RateLimit-Reset").orElse("0")));
                if (reset.isAfter(retry)) retry = reset;
            }
        } catch (RuntimeException ignored) { /* retain conservative default on malformed upstream headers */ }
        return retry;
    }

    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long received;
        private boolean failed;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (var buffer : buffers) received += buffer.remaining();
            if (received > MAX_BYTES) {
                failed = true; subscription.cancel(); delegate.onError(new ResponseLimitException());
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!failed) { failed = true; delegate.onError(error); } }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }
    private static class ResponseLimitException extends RuntimeException {}
}
