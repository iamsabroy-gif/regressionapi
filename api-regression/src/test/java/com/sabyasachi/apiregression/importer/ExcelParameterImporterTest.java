package com.sabyasachi.apiregression.importer;

import com.sabyasachi.apiregression.payload.OverrideException;
import com.sabyasachi.apiregression.payload.ReasonCode;
import com.sabyasachi.apiregression.testsupport.XlsxFixtureBuilder;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExcelParameterImporterTest {

    @Test
    void testLoadWorkbook(@TempDir Path tempDir) throws Exception {
        Path excelPath = tempDir.resolve("test_params.xlsx");

        XlsxFixtureBuilder.create()
                .sheet("getPetById")
                .number("petId", 42)
                .bool("verbose", true)
                .number("petId", 99) // Duplicate row
                .and()
                .sheet("PUT _pets_{petId}")
                .text("body.name", "Doggie")
                .text("body.tag", "<null>")
                .text("body.desc", "<empty>")
                .and()
                .sheet("Notes")
                .hidden()
                .text("param1", "value1")
                .build(excelPath);

        List<SheetParams> sheets = ExcelParameterImporter.load(excelPath);
        // 2 active sheets + 1 hidden sheet with status=SKIPPED
        assertEquals(3, sheets.size());

        SheetParams sheet1 = sheets.stream()
                .filter(s -> "getPetById".equals(s.getSheetName()))
                .findFirst().orElseThrow();
        assertEquals("getPetById", sheet1.getOperationId());
        // Both rows are kept; petId row 1 is duplicate, row 3 is winner
        assertEquals(3, sheet1.getRows().size());

        ParamRow firstPetId = sheet1.getRows().get(0);
        assertTrue(firstPetId.isDuplicate());
        assertEquals(4, firstPetId.getSupersededByRow()); // row 4 in excel

        ParamRow secondPetId = sheet1.getRows().get(2);
        assertFalse(secondPetId.isDuplicate());
        assertEquals("99", secondPetId.getRawValue());

        SheetParams sheet2 = sheets.stream()
                .filter(s -> s.getSheetName().startsWith("PUT"))
                .findFirst().orElseThrow();
        assertEquals("PUT", sheet2.getMethod());
        assertEquals("/pets/{petId}", sheet2.getPath());

        ParamRow nullRow = sheet2.getRows().stream()
                .filter(r -> "body.tag".equals(r.getParameter()))
                .findFirst().orElseThrow();
        assertEquals("<null>", nullRow.getRawValue());

        ParamRow emptyRow = sheet2.getRows().stream()
                .filter(r -> "body.desc".equals(r.getParameter()))
                .findFirst().orElseThrow();
        assertEquals("<empty>", emptyRow.getRawValue());

        SheetParams hiddenSheet = sheets.stream()
                .filter(s -> "Notes".equals(s.getSheetName()))
                .findFirst().orElseThrow();
        assertEquals("SKIPPED", hiddenSheet.getStatus());
        assertEquals(ReasonCode.HIDDEN_SHEET.name(), hiddenSheet.getSkipReason());
    }

    @Test
    void testBlankVsNullVsEmpty(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("blank_null_empty.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Sheet1")
                .blank("blankField")
                .text("nullField", "<null>")
                .text("emptyField", "<empty>")
                .build(p);

        List<SheetParams> sheets = ExcelParameterImporter.load(p);
        List<ParamRow> rows = sheets.get(0).getRows();
        assertEquals(3, rows.size());

        assertEquals(ParamRow.CellKind.BLANK, rows.get(0).getCellKindEnum());
        assertNull(rows.get(0).getRawValue());

        assertEquals("<null>", rows.get(1).getRawValue());
        assertEquals("<empty>", rows.get(2).getRawValue());
    }

    @Test
    void testIntegralNumericStripsTrailingZero(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("numbers.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Sheet1")
                .number("intVal", 10.0)
                .number("decimalVal", 10.5)
                .build(p);

        List<SheetParams> sheets = ExcelParameterImporter.load(p);
        List<ParamRow> rows = sheets.get(0).getRows();
        assertEquals("10", rows.get(0).getRawValue());
        assertEquals("10.5", rows.get(1).getRawValue());
    }

    @Test
    void testNumericAbove1e15IsTooPrecise(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("precision.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Sheet1")
                .number("tooBig", 1e15)
                .number("okBig", 999999999999999.0)
                .build(p);

        List<SheetParams> sheets = ExcelParameterImporter.load(p);
        List<ParamRow> rows = sheets.get(0).getRows();
        assertEquals(ParamRow.CellKind.TOO_PRECISE, rows.get(0).getCellKindEnum());
        assertTrue(rows.get(0).getCellKindEnum().isErrorKind());
        assertEquals(ParamRow.CellKind.NUMERIC, rows.get(1).getCellKindEnum());
    }

    @Test
    void testDateAndDateTimeFormatting(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("dates.xlsx");
        Date d = new Date(1700000000000L);
        XlsxFixtureBuilder.create()
                .sheet("Sheet1")
                .date("dob", d, false)
                .date("timestamp", d, true)
                .build(p);

        List<SheetParams> sheets = ExcelParameterImporter.load(p);
        List<ParamRow> rows = sheets.get(0).getRows();
        assertEquals(ParamRow.CellKind.DATE, rows.get(0).getCellKindEnum());
        assertNotNull(rows.get(0).getRawValue());
        assertEquals(ParamRow.CellKind.DATE, rows.get(1).getCellKindEnum());
        assertNotNull(rows.get(1).getRawValue());
    }

    @Test
    void testFormulaCachingAndErrors(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("formulas.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Sheet1")
                .formula("calc", "1+1", 2)
                .formulaError("err", "1/0", FormulaError.DIV0)
                .formulaNoCache("noCache", "A1+A2")
                .build(p);

        List<SheetParams> sheets = ExcelParameterImporter.load(p);
        List<ParamRow> rows = sheets.get(0).getRows();
        assertEquals(ParamRow.CellKind.NUMERIC, rows.get(0).getCellKindEnum());
        assertEquals("2", rows.get(0).getRawValue());

        assertEquals(ParamRow.CellKind.ERROR, rows.get(1).getCellKindEnum());
        assertEquals(ParamRow.CellKind.NO_CACHE, rows.get(2).getCellKindEnum());
    }

    @Test
    void testTargetMetadataAndColumnOrder(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("meta.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("CustomSheet")
                .target("POST /customers")
                .meta("location", "query")
                .text("qParam", "searchVal")
                .build(p);

        List<SheetParams> sheets = ExcelParameterImporter.load(p);
        SheetParams s = sheets.get(0);
        assertEquals("POST /customers", s.getTarget());
        assertEquals("query", s.getDefaultLocation());
        assertEquals("query", s.getRows().get(0).getExplicitLocation());
    }

    @Test
    void testUnknownColumnWarning(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("unknown_col.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("parameter");
            h.createCell(1).setCellValue("value");
            h.createCell(2).setCellValue("customMetadataCol");

            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("p1");
            r.createCell(1).setCellValue("v1");
            r.createCell(2).setCellValue("extra");

            try (FileOutputStream fos = new FileOutputStream(p.toFile())) {
                wb.write(fos);
            }
        }

        List<SheetParams> sheets = ExcelParameterImporter.load(p);
        SheetParams s = sheets.get(0);
        assertFalse(s.getSheetWarnings().isEmpty());
        assertTrue(s.getSheetWarnings().get(0).contains("Unknown column header"));
    }

    @Test
    void testEnabledNIsDisabled(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("disabled.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Sheet1")
                .enabled("p1", "v1", "N")
                .enabled("p2", "v2", "Y")
                .build(p);

        List<SheetParams> sheets = ExcelParameterImporter.load(p);
        assertFalse(sheets.get(0).getRows().get(0).isEnabled());
        assertTrue(sheets.get(0).getRows().get(1).isEnabled());
    }

    @Test
    void testDuplicateCaseInsensitiveRowLastWins(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("case_dup.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Sheet1")
                .text("CITY", "Springfield")
                .text("city", "Shelbyville")
                .build(p);

        List<SheetParams> sheets = ExcelParameterImporter.load(p);
        List<ParamRow> rows = sheets.get(0).getRows();
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).isDuplicate());
        assertFalse(rows.get(1).isDuplicate());
        assertEquals("Shelbyville", rows.get(1).getRawValue());
    }

    @Test
    void testVeryHiddenSheetAlwaysSkipped(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("very_hidden.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Visible")
                .text("p1", "v1")
                .and()
                .sheet("Secret")
                .veryHidden()
                .text("p2", "v2")
                .build(p);

        List<SheetParams> sheets = ExcelParameterImporter.load(p, false); // even with skipHiddenSheets=false
        assertEquals(1, sheets.size());
        assertEquals("Visible", sheets.get(0).getSheetName());
    }

    @Test
    void testMergedRegionOnlyTopLeftPreserved(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("merged.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Sheet1")
                .text("paramA", "MergedVal")
                .text("paramB", "ShouldBeBlank")
                .merge(1, 1, 2, 1) // row 1 to row 2, col 1 (value col)
                .build(p);

        List<SheetParams> sheets = ExcelParameterImporter.load(p);
        List<ParamRow> rows = sheets.get(0).getRows();
        assertEquals("MergedVal", rows.get(0).getRawValue());
        assertNull(rows.get(1).getRawValue());
        assertEquals(ParamRow.CellKind.BLANK, rows.get(1).getCellKindEnum());
        assertTrue(rows.get(1).getWarnings().get(0).contains("merged region"));
    }

    @Test
    void testRowCapExceededMarksNotApplied(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("row_cap.xlsx");
        var builder = XlsxFixtureBuilder.create().sheet("Sheet1");
        for (int i = 0; i < 15; i++) {
            builder.text("param" + i, "val" + i);
        }
        builder.build(p);

        List<SheetParams> sheets = new ExcelParameterImporter().parse(p, true, 10);
        SheetParams s = sheets.get(0);
        assertEquals("NOT_APPLIED", s.getStatus());
        assertEquals(ReasonCode.ROW_CAP_EXCEEDED.name(), s.getSkipReason());
        assertTrue(s.getRows().isEmpty(), "Rows should be empty when row cap exceeded");
    }

    @Test
    void testXlsFileRejected(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("test.xls");
        Files.writeString(p, "mock xls content");

        OverrideException ex = assertThrows(OverrideException.class, () -> ExcelParameterImporter.load(p));
        assertEquals(ReasonCode.WORKBOOK_UNSUPPORTED, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("Save As .xlsx"));
    }

    @Test
    void testEncryptedWorkbookRejectedWithMessage(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("encrypted.xlsx");
        XlsxFixtureBuilder.createEncryptedWorkbook(p, "password123");

        OverrideException ex = assertThrows(OverrideException.class, () -> ExcelParameterImporter.load(p));
        assertEquals(ReasonCode.WORKBOOK_ENCRYPTED, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("password-protected or encrypted"));
    }

    @Test
    void testLegacyOpPathParamHeadersRejected(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("legacy_headers.xlsx");
        XlsxFixtureBuilder.create()
                .legacyHeaders(true)
                .sheet("Sheet1")
                .text("param1", "val1")
                .build(p);

        OverrideException ex = assertThrows(OverrideException.class, () -> ExcelParameterImporter.load(p));
        assertEquals(ReasonCode.WORKBOOK_UNSUPPORTED, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("Invalid column header"));
    }

    @Test
    void testWhitespaceAndHiddenRowWarnings(@TempDir Path tempDir) throws Exception {
        Path p = tempDir.resolve("warnings.xlsx");
        XlsxFixtureBuilder.create()
                .sheet("Sheet1")
                .text("spaced", "  padded value  ")
                .hiddenRow(2)
                .text("hiddenR", "val2")
                .build(p);

        List<SheetParams> sheets = ExcelParameterImporter.load(p);
        List<ParamRow> rows = sheets.get(0).getRows();
        assertTrue(rows.get(0).getWarnings().stream().anyMatch(w -> w.contains("whitespace")));
        assertTrue(rows.get(1).getWarnings().stream().anyMatch(w -> w.contains("hidden or filtered")));
    }
}
