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
}
