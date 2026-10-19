package com.kopite.devspace.github.infrastructure;

import com.kopite.devspace.github.domain.GitHubException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class GitHubHttpTests {
    @Test void responseBodyStallIsCancelledWithinRequestDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            try { Thread.sleep(12000); exchange.getResponseBody().write(new byte[100]); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var transport = new GitHubHttpTransport(new GitHubProperties(), JsonMapper.builder().build());
            long start = System.nanoTime();
            assertEquals("GITHUB_UNAVAILABLE", assertThrows(GitHubException.class, () -> transport.request(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stall"), "GET", null, null, null)).code());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds() < 12);
        } finally { server.stop(0); }
    }
    @Test void boundedResponsesAndNoRedirectedCredentials() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var redirected = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/ok", exchange -> {
            assertEquals("Bearer test-secret", exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"id\":42}".getBytes(); exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location", "/leak"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/leak", exchange -> { redirected.set(true); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/large", exchange -> {
            byte[] body = new byte[4 * 1024 * 1024 + 1]; exchange.sendResponseHeaders(200, body.length);
            try { exchange.getResponseBody().write(body); } catch (java.io.IOException expected) { } finally { exchange.close(); }
        });
        server.createContext("/limited", exchange -> { exchange.getResponseHeaders().set("Retry-After", "60"); exchange.sendResponseHeaders(403, -1); exchange.close(); });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var transport = new GitHubHttpTransport(new GitHubProperties(), JsonMapper.builder().build());
            assertEquals(42, transport.request(URI.create(base + "/ok"), "GET", "test-secret", null, null).get("id").asInt());
            assertThrows(GitHubException.class, () -> transport.request(URI.create(base + "/redirect"), "GET", "test-secret", null, null));
            assertFalse(redirected.get());
            assertEquals("GITHUB_RESPONSE_TOO_LARGE", assertThrows(GitHubException.class, () -> transport.request(URI.create(base + "/large"), "GET", null, null, null)).code());
            assertEquals("GITHUB_RATE_LIMITED", assertThrows(GitHubException.class,
                    () -> transport.request(URI.create(base + "/limited"), "GET", null, null, null)).code());
        } finally { server.stop(0); }
    }
}
