package com.sabyasachi.apiregression.runner;

import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestResult;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SuiteRunnerTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
    private static final AtomicBoolean simulateTokenFailure = new AtomicBoolean(false);

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        // 1. Authman mock endpoint: returns { "access_token": "abc123" } or 500 when simulated
        server.createContext("/authman/token", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (simulateTokenFailure.get()) {
                    String err = "{\"error\": \"invalid_credentials\"}";
                    exchange.sendResponseHeaders(401, err.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(err.getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }

                String response = "{\"access_token\": \"abc123\", \"token_type\": \"Bearer\", \"expires_in\": 3600}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        // 2. Resource endpoint: checks for Authorization: Bearer abc123
        server.createContext("/api/protected-resource", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                receivedAuthHeader.set(auth);

                if ("Bearer abc123".equals(auth)) {
                    String response = "{\"status\": \"success\", \"data\": \"protected info\"}";
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } else {
                    String response = "{\"error\": \"unauthorized\"}";
                    exchange.sendResponseHeaders(401, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                    }
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
    void testAuthmanTokenResolutionAndInjectionOnWire(@TempDir Path tempDir) throws IOException {
        simulateTokenFailure.set(false);
        receivedAuthHeader.set(null);

        TestSuite suite = new TestSuite("Authman Flow Suite");
        suite.setBaseUrl("http://localhost:" + port);

        // Token provider TestCase
        TestCase tokenProvider = new TestCase("Authman Token API", "POST", "{{baseUrl}}/authman/token");
        tokenProvider.setTokenProvider(true);
        tokenProvider.setTokenJsonPath("$.access_token");
        tokenProvider.setTokenVariableName("authToken");
        tokenProvider.setExpectedStatus(200);

        // Dependent TestCase
        TestCase protectedCall = new TestCase("Get Protected Resource", "GET", "{{baseUrl}}/api/protected-resource");
        protectedCall.setHeaders(Map.of("Authorization", "Bearer {{authToken}}"));
        protectedCall.setUsesTokenFrom(List.of("authToken"));
        protectedCall.setExpectedStatus(200);
        protectedCall.setExpectedBodyTemplate("{\"status\": \"success\", \"data\": \"protected info\"}");

        suite.setTestCases(List.of(tokenProvider, protectedCall));

        SuiteRunner runner = new SuiteRunner();
        List<TestResult> results = runner.run(suite);

        assertEquals(2, results.size());

        // Token Provider result
        TestResult tokenResult = results.get(0);
        assertTrue(tokenResult.isPassed(), "Token provider should pass");
        assertEquals("abc123", tokenResult.getTokenExtracted());

        // Dependent call result
        TestResult protectedResult = results.get(1);
        assertTrue(protectedResult.isPassed(), "Dependent call should pass: " + protectedResult.getDiffSummary());
        assertEquals(200, protectedResult.getActualStatus());

        // CRITICAL WIRE ASSERTION: Confirm the dependent call actually received "Bearer abc123" on the wire!
        assertEquals("Bearer abc123", receivedAuthHeader.get(), "Protected endpoint must receive resolved token on wire!");

        // Also test ReportWriter
        ReportWriter writer = new ReportWriter();
        Path htmlReport = tempDir.resolve("report.html");
        Path jsonReport = tempDir.resolve("report.json");
        writer.writeHtml(results, htmlReport);
        writer.writeJson(results, jsonReport);

        assertTrue(Files.exists(htmlReport));
        assertTrue(Files.exists(jsonReport));
        String htmlContent = Files.readString(htmlReport);
        assertTrue(htmlContent.contains("API Regression Test Report"));
        assertTrue(htmlContent.contains("Authman Token API"));
        assertTrue(htmlContent.contains("Get Protected Resource"));
        assertTrue(htmlContent.contains("PASS"));
    }

    @Test
    void testFailedTokenProviderFailsDependentsWithClearReason() {
        simulateTokenFailure.set(true);
        receivedAuthHeader.set(null);

        TestSuite suite = new TestSuite("Broken Authman Suite");
        suite.setBaseUrl("http://localhost:" + port);

        TestCase tokenProvider = new TestCase("Broken Authman API", "POST", "{{baseUrl}}/authman/token");
        tokenProvider.setTokenProvider(true);
        tokenProvider.setTokenJsonPath("$.access_token");
        tokenProvider.setTokenVariableName("authToken");
        tokenProvider.setExpectedStatus(200);

        TestCase protectedCall = new TestCase("Dependent Call", "GET", "{{baseUrl}}/api/protected-resource");
        protectedCall.setHeaders(Map.of("Authorization", "Bearer {{authToken}}"));
        protectedCall.setUsesTokenFrom(List.of("authToken"));
        protectedCall.setExpectedStatus(200);

        suite.setTestCases(List.of(tokenProvider, protectedCall));

        SuiteRunner runner = new SuiteRunner();
        List<TestResult> results = runner.run(suite);

        assertEquals(2, results.size());

        TestResult providerResult = results.get(0);
        assertFalse(providerResult.isPassed());

        TestResult depResult = results.get(1);
        assertFalse(depResult.isPassed());
        // Verify clear error reason instead of generic 401
        assertNotNull(depResult.getErrorMessage());
        assertTrue(depResult.getErrorMessage().contains("Token resolution failed for required token [authToken]"),
                "Dependent call must have clear token failure reason: " + depResult.getErrorMessage());

        // Ensure the dependent call was never sent over the wire!
        assertNull(receivedAuthHeader.get(), "Dependent endpoint should not even be called if token failed");
    }
}
