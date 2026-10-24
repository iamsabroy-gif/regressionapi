package com.sabyasachi.apiregression.runner;

import com.sabyasachi.apiregression.client.HttpRunner;
import com.sabyasachi.apiregression.model.RunOptions;
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
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void testSharedRunnerRetryCountIsNotMutatedAcrossRuns() {
        AtomicInteger attempts = new AtomicInteger(0);
        server.createContext("/api/flaky-test", exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                // Fail with 503
                String err = "{\"error\": \"temporary_failure\"}";
                exchange.sendResponseHeaders(503, err.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                String ok = "{\"status\": \"ok\"}";
                exchange.sendResponseHeaders(200, ok.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(ok.getBytes(StandardCharsets.UTF_8));
                }
            }
        });

        try {
            HttpRunner baseHttpRunner = new HttpRunner();
            assertEquals(0, baseHttpRunner.getRetryCount(), "Initial retry count must be 0");

            SuiteRunner suiteRunner = new SuiteRunner(baseHttpRunner, new TokenProviderResolver(baseHttpRunner));

            // Run 1: with retryCount = 1
            TestSuite suite1 = new TestSuite("Suite 1");
            RunOptions opts1 = new RunOptions();
            opts1.setRetryCount(1);
            suite1.setRunOptions(opts1);
            TestCase tc1 = new TestCase("Flaky Call 1", "GET", "http://localhost:" + port + "/api/flaky-test");
            tc1.setExpectedStatus(200);
            suite1.setTestCases(List.of(tc1));

            List<TestResult> res1 = suiteRunner.run(suite1);
            assertEquals(1, res1.size());
            assertTrue(res1.get(0).isPassed(), "Run 1 should succeed on retry");
            assertEquals(2, attempts.get(), "Attempt 1 failed, attempt 2 succeeded due to retry");

            // Base runner should NOT have been mutated
            assertEquals(0, baseHttpRunner.getRetryCount(), "Base HttpRunner retryCount must remain 0 after run 1");

            // Run 2: with retryCount = 0
            attempts.set(0);
            TestSuite suite2 = new TestSuite("Suite 2");
            RunOptions opts2 = new RunOptions();
            opts2.setRetryCount(0);
            suite2.setRunOptions(opts2);
            TestCase tc2 = new TestCase("Flaky Call 2", "GET", "http://localhost:" + port + "/api/flaky-test");
            tc2.setExpectedStatus(200);
            suite2.setTestCases(List.of(tc2));

            List<TestResult> res2 = suiteRunner.run(suite2);
            assertEquals(1, res2.size());
            assertFalse(res2.get(0).isPassed(), "Run 2 should fail because retryCount is 0");
            assertEquals(1, attempts.get(), "Should only attempt once without retry");
            assertEquals(503, res2.get(0).getActualStatus());

            // Base runner still 0
            assertEquals(0, baseHttpRunner.getRetryCount(), "Base HttpRunner retryCount must remain 0 after run 2");
        } finally {
            server.removeContext("/api/flaky-test");
        }
    }

    @Test
    void testThreadFailurePreservesTestCaseNameAndHandlesNullMessage() {
        HttpRunner failingRunner = new HttpRunner() {
            @Override
            public ExecutionResponse execute(TestCase testCase, Map<String, String> variables, int effectiveRetryCount) {
                if ("Failing Case".equals(testCase.getName())) {
                    throw new AssertionError(); // null message, Error not Exception
                }
                return new ExecutionResponse(200, Map.of(), "{}", 10L, "http://localhost", List.of(), List.of());
            }
        };

        SuiteRunner suiteRunner = new SuiteRunner(failingRunner, new TokenProviderResolver(failingRunner));

        TestSuite suite = new TestSuite("Parallel Suite");
        RunOptions opts = new RunOptions();
        opts.setThreads(2); // Enable thread pool
        suite.setRunOptions(opts);

        TestCase tc1 = new TestCase("Passing Case", "GET", "http://localhost/pass");
        tc1.setCategory("Health");
        tc1.setExpectedStatus(200);

        TestCase tc2 = new TestCase("Failing Case", "POST", "http://localhost/fail");
        tc2.setCategory("Payments");
        tc2.setExpectedStatus(201);

        suite.setTestCases(List.of(tc1, tc2));

        AtomicInteger completedCount = new AtomicInteger(0);
        SuiteRunner.SuiteRunListener listener = new SuiteRunner.SuiteRunListener() {
            @Override
            public void onTestCompleted(int currentIndex, int totalCount, TestResult result) {
                completedCount.incrementAndGet();
            }
        };

        List<TestResult> results = suiteRunner.run(suite, listener);
        assertEquals(2, results.size());
        assertEquals(2, completedCount.get(), "Listener should be notified for all tests including thread failures");

        TestResult res1 = results.get(0);
        assertEquals("Passing Case", res1.getTestCaseName());
        assertTrue(res1.isPassed());

        TestResult res2 = results.get(1);
        assertEquals("Failing Case", res2.getTestCaseName(), "Failing test name must be preserved");
        assertEquals("Payments", res2.getCategory(), "Category must be preserved");
        assertEquals("POST", res2.getMethod(), "Method must be preserved");
        assertEquals(201, res2.getExpectedStatus(), "Expected status must be preserved");
        assertFalse(res2.isPassed());
        assertNotNull(res2.getErrorMessage());
        assertFalse(res2.getErrorMessage().contains("null"), "Error message should not contain 'null': " + res2.getErrorMessage());
        assertTrue(res2.getErrorMessage().contains("AssertionError"), "Error message should mention AssertionError: " + res2.getErrorMessage());
    }
}
