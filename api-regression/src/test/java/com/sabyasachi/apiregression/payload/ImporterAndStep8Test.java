package com.sabyasachi.apiregression.payload;

import com.sabyasachi.apiregression.client.HttpRunner;
import com.sabyasachi.apiregression.model.RunOptions;
import com.sabyasachi.apiregression.model.RunResultRow;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestResult;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sabyasachi.apiregression.runner.SuiteRunner;
import com.sabyasachi.apiregression.testsupport.XlsxFixtureBuilder;
import com.sabyasachi.apiregression.ui.ApplyResultFormatter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ImporterAndStep8Test {

    private static TestCase createTestCase(String name, String method, String url, int status) {
        TestCase tc = new TestCase(name, method, url);
        tc.setExpectedStatus(status);
        return tc;
    }

    @Test
    void m9_workbookChangedBetweenPreviewAndApply_isRejected(@TempDir Path tempDir) throws Exception {
        Path wb = tempDir.resolve("params.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("createCustomer")
                .target("POST /customers")
                .text("name", "Alice")
                .build(wb);

        TestSuite suite = new TestSuite("Suite 1");
        TestCase tc = createTestCase("createCustomer", "POST", "http://localhost/customers", 200);
        tc.setBodyTemplate("{\"name\":\"orig\"}");
        suite.addTestCase(tc);

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideOptions options = new OverrideOptions();
        OverrideReport dryRunReport = service.dryRun(suite, wb, null, options);

        // Mutate workbook on disk
        XlsxFixtureBuilder.create()
                .sheet("createCustomer")
                .target("POST /customers")
                .text("name", "Bob")
                .build(wb);

        // Apply with stale dryRunReport should be rejected
        OverrideException ex = assertThrows(OverrideException.class,
                () -> service.apply(suite, wb, null, options, dryRunReport));
        assertEquals(ReasonCode.WORKBOOK_UNSUPPORTED, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("Workbook changed since preview"));
    }

    @Test
    void m9_successCountsComeFromApplyReport() {
        OverrideReport applyReport = new OverrideReport();
        SheetSummary s1 = new SheetSummary("S1", "Target1");
        s1.setStatus("APPLIED");
        s1.setModified(2);
        s1.setAdded(1);

        SheetSummary s2 = new SheetSummary("S2", "Target2");
        s2.setStatus("NOT_APPLIED");
        s2.setErrors(1);

        SheetSummary s3 = new SheetSummary("S3", "Target3");
        s3.setStatus("SKIPPED");

        applyReport.getSheets().addAll(List.of(s1, s2, s3));

        String formatted = ApplyResultFormatter.format(applyReport);
        assertTrue(formatted.contains("Sheets: 1 applied"));
        assertTrue(formatted.contains("1 not applied"));
        assertTrue(formatted.contains("1 skipped"));
        assertTrue(formatted.contains("Modified: 2"));
        assertTrue(formatted.contains("Added: 1"));
        assertTrue(formatted.contains("Errors: 1"));
    }

    @Test
    void manualEditsWarning_whenRequestChangedAfterApply(@TempDir Path tempDir) throws Exception {
        Path wb = tempDir.resolve("params.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("createCustomer")
                .target("POST /customers")
                .text("name", "Alice")
                .build(wb);

        TestSuite suite = new TestSuite("Suite 1");
        TestCase tc = createTestCase("createCustomer", "POST", "http://localhost/customers", 200);
        tc.setBodyTemplate("{\"name\":\"orig\"}");
        suite.addTestCase(tc);

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideOptions options = new OverrideOptions();

        // 1. Apply workbook
        service.apply(suite, wb, null, options);
        assertNotNull(tc.getLastOverride());
        assertNotNull(tc.getLastOverride().getProducedFingerprint());

        // 2. Dry run with untouched request -> NO warning
        OverrideReport r1 = service.dryRun(suite, wb, null, options);
        assertTrue(r1.getSheets().get(0).getSheetWarnings().stream()
                .noneMatch(w -> w.contains("MANUAL_EDITS_DISCARDED")));

        // 3. Mutate request manually (e.g. edit URL)
        tc.setUrl("http://localhost/customers?edited=true");

        // 4. Dry run with modified request -> MANUAL_EDITS_DISCARDED warning present
        OverrideReport r2 = service.dryRun(suite, wb, null, options);
        assertTrue(r2.getSheets().get(0).getSheetWarnings().stream()
                .anyMatch(w -> w.contains("MANUAL_EDITS_DISCARDED")));

        // 5. Promote to baseline clears lastOverride and warning
        tc.promoteToBaseline();
        assertNull(tc.getLastOverride());

        OverrideReport r3 = service.dryRun(suite, wb, null, options);
        assertTrue(r3.getSheets().get(0).getSheetWarnings().stream()
                .noneMatch(w -> w.contains("MANUAL_EDITS_DISCARDED")));
    }

    @Test
    void revertToBaseline_clearsLastOverride() {
        TestCase tc = createTestCase("test", "GET", "http://localhost/api", 200);
        tc.promoteToBaseline();

        com.sabyasachi.apiregression.model.OverrideProvenance prov = new com.sabyasachi.apiregression.model.OverrideProvenance();
        prov.setModified(1);
        tc.setLastOverride(prov);
        assertNotNull(tc.getLastOverride());

        tc.revertToBaseline();
        assertNull(tc.getLastOverride(), "revertToBaseline must clear lastOverride");
    }

    @Test
    void isPartialOverridePredicate() {
        TestCase tc = createTestCase("test", "GET", "http://localhost", 200);
        assertFalse(RunResultRow.isPartialOverride(tc));

        com.sabyasachi.apiregression.model.OverrideProvenance prov = new com.sabyasachi.apiregression.model.OverrideProvenance();
        prov.setErrors(0);
        tc.setLastOverride(prov);
        assertFalse(RunResultRow.isPartialOverride(tc));

        prov.setErrors(2);
        assertTrue(RunResultRow.isPartialOverride(tc));
    }

    @Test
    void step8_timeoutHonoured() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {}
            byte[] resp = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            HttpRunner runner = new HttpRunner(Duration.ofMillis(300));
            TestCase tc = createTestCase("slowCase", "GET", "http://127.0.0.1:" + port + "/slow", 200);

            assertThrows(Exception.class, () -> runner.execute(tc, Collections.emptyMap()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void step8_retryOnlyIdempotent() throws Exception {
        AtomicInteger getAttempts = new AtomicInteger(0);
        AtomicInteger postAttempts = new AtomicInteger(0);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api", exchange -> {
            String m = exchange.getRequestMethod();
            int attempt;
            if ("GET".equalsIgnoreCase(m)) {
                attempt = getAttempts.incrementAndGet();
            } else {
                attempt = postAttempts.incrementAndGet();
            }

            if (attempt == 1) {
                // First attempt returns 503
                exchange.sendResponseHeaders(503, 0);
                exchange.close();
            } else {
                // Subsequent attempt returns 200
                byte[] resp = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            HttpRunner runner = new HttpRunner(Duration.ofSeconds(2), 2);

            // 1. GET is idempotent: retries and succeeds on attempt 2
            TestCase getTc = createTestCase("getApi", "GET", "http://127.0.0.1:" + port + "/api", 200);
            var getResp = runner.execute(getTc, Collections.emptyMap());
            assertEquals(200, getResp.getStatusCode());
            assertEquals(2, getAttempts.get());

            // 2. POST is NOT idempotent: fails immediately with 503 without retry
            TestCase postTc = createTestCase("postApi", "POST", "http://127.0.0.1:" + port + "/api", 200);
            postTc.setBodyTemplate("{\"val\":1}");
            var postResp = runner.execute(postTc, Collections.emptyMap());
            assertEquals(503, postResp.getStatusCode());
            assertEquals(1, postAttempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void step8_poolRunsInParallel_resultOrderPreserved() throws Exception {
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/test", exchange -> {
            int current = active.incrementAndGet();
            maxConcurrent.updateAndGet(m -> Math.max(m, current));
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}
            finally {
                active.decrementAndGet();
            }
            byte[] resp = "{\"val\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            TestSuite suite = new TestSuite("Parallel Suite");
            suite.getRunOptions().setThreads(4);

            for (int i = 0; i < 4; i++) {
                TestCase tc = createTestCase("Case_" + i, "GET", "http://127.0.0.1:" + port + "/test", 200);
                tc.setExpectedBodyTemplate("{\"val\":1}");
                suite.addTestCase(tc);
            }

            SuiteRunner suiteRunner = new SuiteRunner();
            long start = System.currentTimeMillis();
            List<TestResult> results = suiteRunner.run(suite);
            long elapsed = System.currentTimeMillis() - start;

            // 4 cases * 200ms executed in 4 threads will finish in < 600ms, and max concurrent >= 2
            assertTrue(maxConcurrent.get() >= 2, "Expected concurrent requests >= 2, got " + maxConcurrent.get());
            assertTrue(elapsed < 700, "Expected parallel execution in < 700ms, took " + elapsed + "ms");

            // Result order must match original test case order
            assertEquals(4, results.size());
            for (int i = 0; i < 4; i++) {
                assertEquals("Case_" + i, results.get(i).getTestCaseName());
                assertTrue(results.get(i).isPassed());
            }
        } finally {
            server.stop(0);
        }
    }
}
