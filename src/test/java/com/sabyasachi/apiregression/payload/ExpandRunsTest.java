package com.sabyasachi.apiregression.payload;

import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestSuite;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExpandRunsTest {

    private final ParameterOverrideService service = new ParameterOverrideService();

    @Test
    void testExpand3RunsAppendsAfterTemplate(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("runs.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("CustomerAPI");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("query.custId");
            header.createCell(1).setCellValue("run");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("1001");
            r1.createCell(1).setCellValue("run-1");

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("1002");
            r2.createCell(1).setCellValue("run-2");

            Row r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue("1003");
            r3.createCell(1).setCellValue("run-3");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        TestSuite suite = new TestSuite();
        TestCase t = new TestCase("CustomerAPI", "GET", "http://example.com/api/customer");
        TestCase u = new TestCase("OtherAPI", "GET", "http://example.com/api/other");
        suite.setTestCases(List.of(t, u));

        OverrideOptions options = new OverrideOptions();
        OverrideReport report = service.apply(suite, file, null, options);

        assertEquals(3, report.getSheets().size());
        assertEquals(5, suite.getTestCases().size());

        TestCase tc0 = suite.getTestCases().get(0);
        assertEquals("CustomerAPI", tc0.getName());
        assertTrue(tc0.isTemplate());

        TestCase tc1 = suite.getTestCases().get(1);
        assertEquals("CustomerAPI [run-1]", tc1.getName());
        assertFalse(tc1.isTemplate());
        assertEquals("CustomerAPI", tc1.getDerivedFrom());
        assertEquals("run-1", tc1.getRunLabel());
        assertTrue(tc1.getUrl().contains("custId=1001"));

        TestCase tc2 = suite.getTestCases().get(2);
        assertEquals("CustomerAPI [run-2]", tc2.getName());
        assertEquals("run-2", tc2.getRunLabel());
        assertTrue(tc2.getUrl().contains("custId=1002"));

        TestCase tc3 = suite.getTestCases().get(3);
        assertEquals("CustomerAPI [run-3]", tc3.getName());
        assertEquals("run-3", tc3.getRunLabel());
        assertTrue(tc3.getUrl().contains("custId=1003"));

        TestCase tc4 = suite.getTestCases().get(4);
        assertEquals("OtherAPI", tc4.getName());
        assertFalse(tc4.isTemplate());
    }

    @Test
    void testReapplyReplacesPriorRuns(@TempDir Path tempDir) throws IOException {
        Path file3 = tempDir.resolve("runs3.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("CustomerAPI");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("query.custId");
            header.createCell(1).setCellValue("run");

            for (int i = 1; i <= 3; i++) {
                Row r = sheet.createRow(i);
                r.createCell(0).setCellValue("100" + i);
                r.createCell(1).setCellValue("run-" + i);
            }
            try (FileOutputStream fos = new FileOutputStream(file3.toFile())) {
                wb.write(fos);
            }
        }

        TestSuite suite = new TestSuite();
        TestCase t = new TestCase("CustomerAPI", "GET", "http://example.com/api/customer");
        TestCase u = new TestCase("OtherAPI", "GET", "http://example.com/api/other");
        suite.setTestCases(List.of(t, u));

        service.apply(suite, file3, null, new OverrideOptions());
        assertEquals(5, suite.getTestCases().size());

        Path file2 = tempDir.resolve("runs2.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("CustomerAPI");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("query.custId");
            header.createCell(1).setCellValue("run");

            for (int i = 1; i <= 2; i++) {
                Row r = sheet.createRow(i);
                r.createCell(0).setCellValue("200" + i);
                r.createCell(1).setCellValue("new-run-" + i);
            }
            try (FileOutputStream fos = new FileOutputStream(file2.toFile())) {
                wb.write(fos);
            }
        }

        service.apply(suite, file2, null, new OverrideOptions());
        assertEquals(4, suite.getTestCases().size());

        assertEquals("CustomerAPI", suite.getTestCases().get(0).getName());
        assertEquals("CustomerAPI [new-run-1]", suite.getTestCases().get(1).getName());
        assertEquals("CustomerAPI [new-run-2]", suite.getTestCases().get(2).getName());
        assertEquals("OtherAPI", suite.getTestCases().get(3).getName());
    }

    @Test
    void testPerRunErrorIsolation(@TempDir Path tempDir) throws IOException {
        // Spec with schema expecting integer for query param custId
        Path specFile = tempDir.resolve("spec.json");
        String specJson = """
        {
          "openapi": "3.0.0",
          "info": { "title": "Cust", "version": "1.0" },
          "paths": {
            "/api/customer": {
              "get": {
                "operationId": "CustomerAPI",
                "parameters": [
                  {
                    "name": "custId",
                    "in": "query",
                    "schema": { "type": "integer" }
                  }
                ],
                "responses": { "200": { "description": "ok" } }
              }
            }
          }
        }
        """;
        java.nio.file.Files.writeString(specFile, specJson);

        Path file = tempDir.resolve("error_runs.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("CustomerAPI");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("query.custId");
            header.createCell(1).setCellValue("run");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("1001");
            r1.createCell(1).setCellValue("run-1");

            // Row 2: invalid integer "not_a_number" -> triggers TYPE_COERCION_FAILED
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("not_a_number");
            r2.createCell(1).setCellValue("run-2");

            Row r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue("1003");
            r3.createCell(1).setCellValue("run-3");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        TestSuite suite = new TestSuite();
        TestCase t = new TestCase("CustomerAPI", "GET", "http://example.com/api/customer");
        t.setOperationId("CustomerAPI");
        suite.setTestCases(List.of(t));

        OverrideOptions options = new OverrideOptions();
        options.setApplyWithErrors(false);

        OverrideReport report = service.apply(suite, file, specFile, options);

        assertEquals(3, report.getSheets().size());
        assertEquals("APPLIED", report.getSheets().get(0).getStatus());
        assertEquals("ERROR", report.getSheets().get(1).getStatus());
        assertEquals("APPLIED", report.getSheets().get(2).getStatus());

        // Template T + 2 derived cases (run-1 and run-3)
        assertEquals(3, suite.getTestCases().size());
        assertEquals("CustomerAPI", suite.getTestCases().get(0).getName());
        assertEquals("CustomerAPI [run-1]", suite.getTestCases().get(1).getName());
        assertEquals("CustomerAPI [run-3]", suite.getTestCases().get(2).getName());
    }

    @Test
    void testDryRunDoesNotMutateSuite(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("dry_runs.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("CustomerAPI");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("query.custId");
            header.createCell(1).setCellValue("run");

            for (int i = 1; i <= 3; i++) {
                Row r = sheet.createRow(i);
                r.createCell(0).setCellValue("100" + i);
                r.createCell(1).setCellValue("run-" + i);
            }
            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        TestSuite suite = new TestSuite();
        TestCase t = new TestCase("CustomerAPI", "GET", "http://example.com/api/customer");
        suite.setTestCases(List.of(t));

        OverrideOptions options = new OverrideOptions();
        OverrideReport report = service.dryRun(suite, file, null, options);

        assertEquals(3, report.getSheets().size());
        assertEquals("CustomerAPI [run-1]", report.getSheets().get(0).getTargetTestCase());
        assertEquals("CustomerAPI [run-2]", report.getSheets().get(1).getTargetTestCase());
        assertEquals("CustomerAPI [run-3]", report.getSheets().get(2).getTargetTestCase());

        // Suite was NOT mutated
        assertEquals(1, suite.getTestCases().size());
        assertFalse(suite.getTestCases().get(0).isTemplate());
        assertNull(suite.getTestCases().get(0).getDerivedFrom());
    }

    @Test
    void testExpectedStatusOverride(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("expected_status.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("CustomerAPI");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("query.custId");
            header.createCell(1).setCellValue("run");
            header.createCell(2).setCellValue("expectedStatus");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("1001");
            r1.createCell(1).setCellValue("run-1");
            // expectedStatus blank -> inherits 200

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("9999");
            r2.createCell(1).setCellValue("run-2");
            r2.createCell(2).setCellValue(404);

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        TestSuite suite = new TestSuite();
        TestCase t = new TestCase("CustomerAPI", "GET", "http://example.com/api/customer");
        t.setExpectedStatus(200);
        suite.setTestCases(List.of(t));

        service.apply(suite, file, null, new OverrideOptions());

        assertEquals(3, suite.getTestCases().size());
        assertEquals(200, suite.getTestCases().get(0).getExpectedStatus());
        assertEquals(200, suite.getTestCases().get(1).getExpectedStatus());
        assertEquals(404, suite.getTestCases().get(2).getExpectedStatus());
    }
}
