package com.sabyasachi.apiregression.client;

import com.sabyasachi.apiregression.model.Auth;
import com.sabyasachi.apiregression.model.TestCase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class HttpRunnerTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicReference<String> lastReceivedAuth = new AtomicReference<>();
    private static final AtomicReference<String> lastReceivedBody = new AtomicReference<>();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/test-get", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                lastReceivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String response = "{\"status\": \"ok\", \"message\": \"get successful\"}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        server.createContext("/test-post", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                lastReceivedBody.set(body);
                lastReceivedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));

                String response = "{\"created\": true, \"received\": " + body + "}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(201, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        server.createContext("/test-huge", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = "A".repeat(50000).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                } catch (IOException ignored) {}
            }
        });

        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testGetExecution() throws Exception {
        HttpRunner runner = new HttpRunner();

        TestCase tc = new TestCase("Get Test", "GET", "http://localhost:" + port + "/test-get");
        tc.setAuth(Auth.bearer("my_secret_token"));

        HttpRunner.ExecutionResponse res = runner.execute(tc, Map.of());

        assertEquals(200, res.getStatusCode());
        assertTrue(res.getBody().contains("get successful"));
        assertEquals("Bearer my_secret_token", lastReceivedAuth.get());
        assertTrue(res.getLatencyMs() >= 0);
    }

    @Test
    void testPostWithVariableSubstitution() throws Exception {
        HttpRunner runner = new HttpRunner();

        TestCase tc = new TestCase("Post Test", "POST", "{{baseUrl}}/test-post");
        tc.setHeaders(Map.of("Content-Type", "application/json"));
        tc.setBodyTemplate("{\"name\": \"{{userName}}\", \"role\": \"admin\"}");

        Map<String, String> vars = Map.of(
                "baseUrl", "http://localhost:" + port,
                "userName", "Sabyasachi"
        );

        HttpRunner.ExecutionResponse res = runner.execute(tc, vars);

        assertEquals(201, res.getStatusCode());
        assertTrue(res.getBody().contains("\"created\": true"));
        assertEquals("{\"name\": \"Sabyasachi\", \"role\": \"admin\"}", lastReceivedBody.get());
    }

    @Test
    void testDefaultMaxBodyBytesIs5MB() {
        HttpRunner runner = new HttpRunner();
        assertEquals(5 * 1024 * 1024, runner.getMaxBodyBytes());
    }

    @Test
    void testResponseSizeCappedWithWarning() throws Exception {
        HttpRunner runner = new HttpRunner();
        runner.setMaxBodyBytes(1024); // Cap at 1 KB

        TestCase tc = new TestCase("Huge Response Test", "GET", "http://localhost:" + port + "/test-huge");
        HttpRunner.ExecutionResponse res = runner.execute(tc, Map.of());

        assertEquals(200, res.getStatusCode());
        assertEquals(1024, res.getBody().length(), "Body should be truncated to exactly 1024 bytes");
        assertTrue(res.getWarnings().stream().anyMatch(w -> w.contains("Response body truncated")),
                "Should have truncation warning: " + res.getWarnings());
        assertTrue(res.getWarnings().stream().anyMatch(w -> w.contains("1 KB")),
                "Warning should mention limit: " + res.getWarnings());
    }

    @Test
    void testResponseBelowCapNotTruncated() throws Exception {
        HttpRunner runner = new HttpRunner();
        runner.setMaxBodyBytes(1024); // Cap at 1 KB

        TestCase tc = new TestCase("Get Test", "GET", "http://localhost:" + port + "/test-get");
        HttpRunner.ExecutionResponse res = runner.execute(tc, Map.of());

        assertEquals(200, res.getStatusCode());
        assertTrue(res.getBody().contains("get successful"));
        assertFalse(res.getWarnings().stream().anyMatch(w -> w.contains("Response body truncated")),
                "Should not have truncation warning for small body");
    }
}
