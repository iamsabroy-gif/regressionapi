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

public class SheetToApiTest {

    private final ParameterOverrideService service = new ParameterOverrideService();

    @Test
    void testMatchingByName(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("match_name.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("GetUserAPI");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("query.id");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("123");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        TestSuite suite = new TestSuite();
        TestCase tc = new TestCase("GetUserAPI", "GET", "http://example.com/user");
        suite.setTestCases(List.of(tc));

        OverrideReport report = service.dryRun(suite, file, null, new OverrideOptions());
        assertEquals(1, report.getSheets().size());
        assertEquals("APPLIED", report.getSheets().get(0).getStatus());
        assertEquals("GetUserAPI [1]", report.getSheets().get(0).getTargetTestCase());
    }

    @Test
    void testMatchingByOperationId(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("match_opid.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("op_getUser");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("query.id");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("123");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        TestSuite suite = new TestSuite();
        TestCase tc = new TestCase("User Retrieval", "GET", "http://example.com/user");
        tc.setOperationId("op_getUser");
        suite.setTestCases(List.of(tc));

        OverrideReport report = service.dryRun(suite, file, null, new OverrideOptions());
        assertEquals(1, report.getSheets().size());
        assertEquals("APPLIED", report.getSheets().get(0).getStatus());
        assertEquals("User Retrieval [1]", report.getSheets().get(0).getTargetTestCase());
    }

    @Test
    void testMatchingWithTargetMetadataRow(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("target_row.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("ShortSheetName");
            // Row 0 has metadata: target in col 0, exact long name in col 1
            Row meta = sheet.createRow(0);
            meta.createCell(0).setCellValue("target");
            meta.createCell(1).setCellValue("Exact Full Name Of Target API");

            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("query.id");

            Row r1 = sheet.createRow(2);
            r1.createCell(0).setCellValue("123");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        TestSuite suite = new TestSuite();
        TestCase tc = new TestCase("Exact Full Name Of Target API", "GET", "http://example.com/user");
        suite.setTestCases(List.of(tc));

        OverrideReport report = service.dryRun(suite, file, null, new OverrideOptions());
        assertEquals(1, report.getSheets().size());
        assertEquals("APPLIED", report.getSheets().get(0).getStatus());
        assertEquals("Exact Full Name Of Target API [1]", report.getSheets().get(0).getTargetTestCase());
    }

    @Test
    void testUnmatchedTarget(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("unmatched.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("UnknownAPI");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("query.id");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("123");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        TestSuite suite = new TestSuite();
        TestCase tc = new TestCase("ExistingAPI", "GET", "http://example.com/user");
        suite.setTestCases(List.of(tc));

        OverrideReport report = service.dryRun(suite, file, null, new OverrideOptions());
        assertEquals(1, report.getSheets().size());
        assertEquals("UNMATCHED", report.getSheets().get(0).getStatus());
    }

    @Test
    void testAmbiguousTarget(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("ambiguous.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("UserAPI");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("query.id");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("123");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        TestSuite suite = new TestSuite();
        TestCase tc1 = new TestCase("UserAPI", "GET", "http://example.com/user1");
        TestCase tc2 = new TestCase("UserAPI", "POST", "http://example.com/user2");
        suite.setTestCases(List.of(tc1, tc2));

        OverrideReport report = service.dryRun(suite, file, null, new OverrideOptions());
        assertEquals(1, report.getSheets().size());
        assertEquals("AMBIGUOUS_TARGET", report.getSheets().get(0).getStatus());
    }

    @Test
    void testConflictDifferentSheetsSameTestCase(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("conflict.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            // Sheet 1: classic sheet
            Sheet s1 = wb.createSheet("SheetA");
            Row meta1 = s1.createRow(0);
            meta1.createCell(0).setCellValue("target");
            meta1.createCell(1).setCellValue("CommonAPI");
            Row h1 = s1.createRow(1);
            h1.createCell(0).setCellValue("parameter");
            h1.createCell(1).setCellValue("value");
            Row r1 = s1.createRow(2);
            r1.createCell(0).setCellValue("query.a");
            r1.createCell(1).setCellValue("1");

            // Sheet 2: data-driven sheet targeting the same CommonAPI
            Sheet s2 = wb.createSheet("SheetB");
            Row meta2 = s2.createRow(0);
            meta2.createCell(0).setCellValue("target");
            meta2.createCell(1).setCellValue("CommonAPI");
            Row h2 = s2.createRow(1);
            h2.createCell(0).setCellValue("query.b");
            Row r2 = s2.createRow(2);
            r2.createCell(0).setCellValue("2");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        TestSuite suite = new TestSuite();
        TestCase tc = new TestCase("CommonAPI", "GET", "http://example.com/api");
        suite.setTestCases(List.of(tc));

        OverrideReport report = service.dryRun(suite, file, null, new OverrideOptions());
        assertEquals(2, report.getSheets().size());
        assertEquals("APPLIED", report.getSheets().get(0).getStatus());
        assertEquals("CONFLICT", report.getSheets().get(1).getStatus());
    }
}
