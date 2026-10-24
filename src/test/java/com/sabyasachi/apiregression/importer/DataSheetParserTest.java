package com.sabyasachi.apiregression.importer;

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

public class DataSheetParserTest {

    @Test
    void testDataSheetNormal3Runs(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("normal_3_runs.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Valid Customer");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("CustId");
            header.createCell(1).setCellValue("accountNum");
            header.createCell(2).setCellValue("run");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("100001");
            r1.createCell(1).setCellValue("87654321");
            r1.createCell(2).setCellValue("run-1");

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("100002");
            r2.createCell(1).setCellValue("87654322");
            r2.createCell(2).setCellValue("run-2");

            Row r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue("100003");
            r3.createCell(1).setCellValue("87654323");
            r3.createCell(2).setCellValue("run-3");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        List<SheetParams> results = ExcelParameterImporter.load(file);
        assertEquals(3, results.size());

        for (int i = 0; i < 3; i++) {
            SheetParams sp = results.get(i);
            assertEquals("Valid Customer", sp.getSheetName());
            assertEquals("Valid Customer", sp.getTarget());
            assertEquals(i + 1, sp.getRunIndex());
            assertEquals("run-" + (i + 1), sp.getRunLabel());
            assertEquals(2, sp.getRows().size());
            assertEquals("CustId", sp.getRows().get(0).getParameter());
            assertEquals("10000" + (i + 1), sp.getRows().get(0).getRawValue());
            assertEquals("accountNum", sp.getRows().get(1).getParameter());
            assertEquals("8765432" + (i + 1), sp.getRows().get(1).getRawValue());
        }
    }

    @Test
    void testDataSheetShortColumnWarning(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("short_cols.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("ShortCols");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("paramA");
            header.createCell(1).setCellValue("paramB");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("valA1");
            // paramB blank

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("valA2");
            // paramB blank

            Row r3 = sheet.createRow(3);
            // paramA blank
            // paramB blank, but need at least one param non-blank to not be a spacer row
            // wait: if paramA and paramB are both blank, it's a spacer!
            // Let's add paramC with values on all 3 rows:
            header.createCell(2).setCellValue("paramC");
            r1.createCell(2).setCellValue("c1");
            r2.createCell(2).setCellValue("c2");
            r3.createCell(2).setCellValue("c3");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        List<SheetParams> results = ExcelParameterImporter.load(file);
        assertEquals(3, results.size());

        SheetParams firstRun = results.get(0);
        List<String> warnings = firstRun.getSheetWarnings();

        boolean foundParamA = warnings.stream().anyMatch(w -> w.contains("paramA") && w.contains("2 of 3 runs have a value"));
        boolean foundParamB = warnings.stream().anyMatch(w -> w.contains("paramB") && w.contains("has no values; ignored"));

        assertTrue(foundParamA, "Expected warning for paramA having 2 of 3 runs: " + warnings);
        assertTrue(foundParamB, "Expected warning for paramB having no values: " + warnings);
    }

    @Test
    void testDataSheetSpacerRowSkipped(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("spacer_row.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("SpacerTest");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("paramA");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("val1");

            // Row 2 is completely blank (spacer)
            sheet.createRow(2);

            Row r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue("val2");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        List<SheetParams> results = ExcelParameterImporter.load(file);
        assertEquals(2, results.size());
        assertEquals("val1", results.get(0).getRows().get(0).getRawValue());
        assertEquals("val2", results.get(1).getRows().get(0).getRawValue());

        SheetParams firstRun = results.get(0);
        boolean foundSpacer = firstRun.getSheetWarnings().stream().anyMatch(w -> w.contains("Spacer row at Excel row 3 skipped"));
        assertTrue(foundSpacer, "Expected spacer warning: " + firstRun.getSheetWarnings());
    }

    @Test
    void testDataSheetDuplicateParameterColumn(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("dup_col.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("DupColTest");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("cust_id");
            header.createCell(1).setCellValue("CUST_ID");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("1001");
            r1.createCell(1).setCellValue("1002");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        List<SheetParams> results = ExcelParameterImporter.load(file);
        assertEquals(1, results.size());
        SheetParams sp = results.get(0);
        assertEquals("NOT_APPLIED", sp.getStatus());
        assertEquals("DUPLICATE", sp.getSkipReason());
        assertTrue(sp.getSheetWarnings().stream().anyMatch(w -> w.contains("DUPLICATE")), "Expected DUPLICATE warning: " + sp.getSheetWarnings());
    }

    @Test
    void testDataSheetMaxRowsCap(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("max_rows.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("MaxRowsTest");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("paramA");

            for (int i = 1; i <= 1001; i++) {
                Row r = sheet.createRow(i);
                r.createCell(0).setCellValue("val" + i);
            }

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        List<SheetParams> results = ExcelParameterImporter.load(file);
        assertEquals(1, results.size());
        SheetParams sp = results.get(0);
        assertEquals("NOT_APPLIED", sp.getStatus());
        assertEquals("ROW_CAP_EXCEEDED", sp.getSkipReason());
    }

    @Test
    void testDataSheetMaxColsCap(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("max_cols.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("MaxColsTest");
            Row header = sheet.createRow(0);
            for (int c = 0; c < 51; c++) {
                header.createCell(c).setCellValue("param_" + c);
            }

            Row r = sheet.createRow(1);
            for (int c = 0; c < 51; c++) {
                r.createCell(c).setCellValue("v" + c);
            }

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        List<SheetParams> results = ExcelParameterImporter.load(file);
        assertEquals(1, results.size());
        SheetParams sp = results.get(0);
        assertEquals("NOT_APPLIED", sp.getStatus());
        assertEquals("ROW_CAP_EXCEEDED", sp.getSkipReason());
    }

    @Test
    void testDataSheetHeaderLengthCap(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("header_length.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("HeaderLenTest");
            Row header = sheet.createRow(0);
            String longHeader = "a".repeat(129);
            header.createCell(0).setCellValue(longHeader);

            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("val");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        List<SheetParams> results = ExcelParameterImporter.load(file);
        assertEquals(1, results.size());
        SheetParams sp = results.get(0);
        assertEquals("NOT_APPLIED", sp.getStatus());
        assertEquals("ROW_CAP_EXCEEDED", sp.getSkipReason());
    }

    @Test
    void testDataSheetExpectedStatusParsed(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("expected_status.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("StatusTest");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("userId");
            header.createCell(1).setCellValue("expectedStatus");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("1001");
            r1.createCell(1).setCellValue(200);

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("unknown");
            r2.createCell(1).setCellValue("404");

            try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
                wb.write(fos);
            }
        }

        List<SheetParams> results = ExcelParameterImporter.load(file);
        assertEquals(2, results.size());
        assertEquals(Integer.valueOf(200), results.get(0).getRunExpectedStatus());
        assertEquals(Integer.valueOf(404), results.get(1).getRunExpectedStatus());
    }
}
