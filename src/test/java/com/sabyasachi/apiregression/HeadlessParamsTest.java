package com.sabyasachi.apiregression;

import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sabyasachi.apiregression.store.SuiteRepository;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class HeadlessParamsTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final List<String> receivedUris = new ArrayList<>();

    @BeforeEach
    void setupServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            synchronized (receivedUris) {
                receivedUris.add(exchange.getRequestURI().toString());
            }
            byte[] resp = "{\"status\":\"ok\"}".getBytes();
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
    void testRunSuiteWithParamsSuccess(@TempDir Path tempDir) throws IOException {
        String baseUrl = "http://localhost:" + port;

        // 1. Suite file with 1 test case
        Path suitePath = tempDir.resolve("suite.json");
        TestSuite suite = new TestSuite("MySuite");
        TestCase tc = new TestCase("GetCust", "GET", baseUrl + "/customer");
        suite.setTestCases(List.of(tc));
        new SuiteRepository().save(suite, suitePath);

        // 2. Params workbook with 3 runs
        Path workbookPath = tempDir.resolve("params.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("GetCust");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("query.id");
            h.createCell(1).setCellValue("run");

            for (int i = 1; i <= 3; i++) {
                Row r = sheet.createRow(i);
                r.createCell(0).setCellValue("100" + i);
                r.createCell(1).setCellValue("cust-" + i);
            }
            try (FileOutputStream fos = new FileOutputStream(workbookPath.toFile())) {
                wb.write(fos);
            }
        }

        String[] args = new String[]{
                "--run", suitePath.toString(),
                "--params", workbookPath.toString()
        };

        int exitCode = Main.runHeadlessSuite(args);
        assertEquals(0, exitCode);

        // All 3 derived runs should have executed against the server
        assertEquals(3, requestCount.get());
        synchronized (receivedUris) {
            assertTrue(receivedUris.contains("/customer?id=1001"));
            assertTrue(receivedUris.contains("/customer?id=1002"));
            assertTrue(receivedUris.contains("/customer?id=1003"));
        }
    }

    @Test
    void testRunSuiteWithParamsErrorAbortsBeforeHttp(@TempDir Path tempDir) throws IOException {
        String baseUrl = "http://localhost:" + port;

        // 1. Suite file
        Path suitePath = tempDir.resolve("suite.json");
        TestSuite suite = new TestSuite("MySuite");
        TestCase tc = new TestCase("GetCust", "GET", baseUrl + "/customer");
        suite.setTestCases(List.of(tc));
        new SuiteRepository().save(suite, suitePath);

        // 2. Error workbook: duplicate parameter columns -> whole sheet NOT_APPLIED / DUPLICATE
        Path workbookPath = tempDir.resolve("error_params.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("GetCust");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("query.id");
            h.createCell(1).setCellValue("QUERY.ID"); // duplicate parameter column!

            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("1001");
            r.createCell(1).setCellValue("1002");

            try (FileOutputStream fos = new FileOutputStream(workbookPath.toFile())) {
                wb.write(fos);
            }
        }

        String[] args = new String[]{
                "--run", suitePath.toString(),
                "--params", workbookPath.toString()
        };

        int exitCode = Main.runHeadlessSuite(args);
        assertEquals(2, exitCode);

        // Zero HTTP requests sent because override errors aborted the run before HTTP
        assertEquals(0, requestCount.get());
    }

    @Test
    void testApplyParamsSuccessWritesOutFile(@TempDir Path tempDir) throws IOException {
        Path suitePath = tempDir.resolve("suite.json");
        TestSuite suite = new TestSuite("MySuite");
        TestCase tc = new TestCase("GetCust", "GET", "http://example.com/customer");
        suite.setTestCases(List.of(tc));
        new SuiteRepository().save(suite, suitePath);

        Path workbookPath = tempDir.resolve("params.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("GetCust");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("query.id");
            h.createCell(1).setCellValue("run");

            for (int i = 1; i <= 2; i++) {
                Row r = sheet.createRow(i);
                r.createCell(0).setCellValue("200" + i);
                r.createCell(1).setCellValue("run-" + i);
            }
            try (FileOutputStream fos = new FileOutputStream(workbookPath.toFile())) {
                wb.write(fos);
            }
        }

        Path outPath = tempDir.resolve("applied_suite.json");
        String[] args = new String[]{
                "--apply-params", suitePath.toString(), workbookPath.toString(),
                "--out", outPath.toString()
        };

        int exitCode = Main.applyParamsCommand(args);
        assertEquals(0, exitCode);

        assertTrue(Files.exists(outPath));
        TestSuite appliedSuite = new SuiteRepository().load(outPath);
        // Original template (template=true) + 2 derived cases
        assertEquals(3, appliedSuite.getTestCases().size());
        assertEquals("GetCust", appliedSuite.getTestCases().get(0).getName());
        assertTrue(appliedSuite.getTestCases().get(0).isTemplate());
        assertEquals("GetCust [run-1]", appliedSuite.getTestCases().get(1).getName());
        assertEquals("GetCust [run-2]", appliedSuite.getTestCases().get(2).getName());
    }

    @Test
    void testApplyParamsErrorReturnsExitCode2(@TempDir Path tempDir) throws IOException {
        Path suitePath = tempDir.resolve("suite.json");
        TestSuite suite = new TestSuite("MySuite");
        TestCase tc = new TestCase("GetCust", "GET", "http://example.com/customer");
        suite.setTestCases(List.of(tc));
        new SuiteRepository().save(suite, suitePath);

        // Error workbook: duplicate column
        Path workbookPath = tempDir.resolve("error_params.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("GetCust");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("query.id");
            h.createCell(1).setCellValue("QUERY.ID");

            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("1001");
            r.createCell(1).setCellValue("1002");

            try (FileOutputStream fos = new FileOutputStream(workbookPath.toFile())) {
                wb.write(fos);
            }
        }

        String[] args = new String[]{
                "--apply-params", suitePath.toString(), workbookPath.toString()
        };

        int exitCode = Main.applyParamsCommand(args);
        assertEquals(2, exitCode);
    }
}
