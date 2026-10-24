package com.sabyasachi.apiregression.runner;

import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestResult;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class RunnerTemplateTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final List<String> receivedPaths = new ArrayList<>();

    @BeforeEach
    void setupServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            synchronized (receivedPaths) {
                receivedPaths.add(exchange.getRequestURI().toString());
            }
            byte[] resp = "{\"token\":\"abc-123\",\"status\":\"ok\"}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDownServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testRunnerSkipsTemplateCases() {
        String baseUrl = "http://localhost:" + port;

        TestSuite suite = new TestSuite("TemplateRunnerSuite");

        // 1 Token provider
        TestCase tp = new TestCase("AuthToken", "POST", baseUrl + "/auth");
        tp.setTokenProvider(true);
        tp.setTokenVariableName("token");
        tp.setTokenJsonPath("$.token");

        // 1 Template test case (should NOT execute)
        TestCase template = new TestCase("CustomerTemplate", "GET", baseUrl + "/template");
        template.setTemplate(true);

        // 3 Derived test cases
        TestCase d1 = new TestCase("CustomerTemplate [1]", "GET", baseUrl + "/customer?id=1");
        d1.setDerivedFrom("CustomerTemplate");
        d1.setRunLabel("1");

        TestCase d2 = new TestCase("CustomerTemplate [2]", "GET", baseUrl + "/customer?id=2");
        d2.setDerivedFrom("CustomerTemplate");
        d2.setRunLabel("2");

        TestCase d3 = new TestCase("CustomerTemplate [3]", "GET", baseUrl + "/customer?id=3");
        d3.setDerivedFrom("CustomerTemplate");
        d3.setRunLabel("3");

        // 1 Standalone regular test case
        TestCase standalone = new TestCase("HealthCheck", "GET", baseUrl + "/health");

        suite.setTestCases(List.of(tp, template, d1, d2, d3, standalone));

        List<String> completedTestNames = new ArrayList<>();
        SuiteRunner.SuiteRunListener listener = new SuiteRunner.SuiteRunListener() {
            @Override
            public void onTestStarted(int current, int total, TestCase tc) {}

            @Override
            public void onTestCompleted(int current, int total, TestResult result) {
                completedTestNames.add(result.getTestCaseName());
                assertEquals(5, total, "Total count should be exactly 5, excluding template");
            }

            @Override
            public void onSuiteFinished(List<TestResult> results) {}
        };

        SuiteRunner runner = new SuiteRunner();
        List<TestResult> results = runner.run(suite, listener);

        // Total executed results = 5 (1 token provider + 3 derived + 1 standalone)
        assertEquals(5, results.size());
        assertEquals(5, completedTestNames.size());

        // Verify template never executed
        assertFalse(completedTestNames.contains("CustomerTemplate"), "Template case should not appear in test results");
        synchronized (receivedPaths) {
            assertFalse(receivedPaths.contains("/template"), "Template endpoint should not have been requested");
            assertTrue(receivedPaths.contains("/auth"));
            assertTrue(receivedPaths.contains("/customer?id=1"));
            assertTrue(receivedPaths.contains("/customer?id=2"));
            assertTrue(receivedPaths.contains("/customer?id=3"));
            assertTrue(receivedPaths.contains("/health"));
        }
    }
}
