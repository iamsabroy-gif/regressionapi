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
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ParameterOverrideServiceTest {

    @Test
    void testDryRunAndApplyOverrides(@TempDir Path tempDir) throws Exception {
        // Setup TestCase
        TestCase tc = new TestCase();
        tc.setName("Update Existing Pet");
        tc.setOperationId("updatePet");
        tc.setMethod("PUT");
        tc.setUrl("https://api.example.com/v1/pets/10");
        Map<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Content-Type", "application/json");
        tc.setHeaders(hdrs);
        tc.setBodyTemplate("{\n  \"id\": 10,\n  \"name\": \"Fluffy\",\n  \"status\": \"available\",\n  \"price\": 19.99\n}");

        TestSuite suite = new TestSuite("Pet Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        // Create Excel Workbook
        Path excelPath = tempDir.resolve("params.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("updatePet");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("parameter");
            h.createCell(1).setCellValue("value");
            h.createCell(2).setCellValue("type");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("body.name");
            r1.createCell(1).setCellValue("Max");
            r1.createCell(2).setCellValue("string");

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("body.price");
            r2.createCell(1).setCellValue(29.99);
            r2.createCell(2).setCellValue("number");

            Row r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue("header.X-Trace-Id");
            r3.createCell(1).setCellValue("trace-abc-123");
            r3.createCell(2).setCellValue("string");

            Row r4 = sheet.createRow(4);
            r4.createCell(0).setCellValue("query.dryRun");
            r4.createCell(1).setCellValue(true);
            r4.createCell(2).setCellValue("boolean");

            Row r5 = sheet.createRow(5);
            r5.createCell(0).setCellValue("path.petId");
            r5.createCell(1).setCellValue(99);
            r5.createCell(2).setCellValue("integer");

            try (FileOutputStream fos = new FileOutputStream(excelPath.toFile())) {
                wb.write(fos);
            }
        }

        Path specPath = Path.of("src/test/resources/specs/petstore-oas3.json");

        OverrideOptions options = new OverrideOptions();
        options.setConstraintPolicy(ConstraintPolicy.WARN);
        ParameterOverrideService service = new ParameterOverrideService();

        // 1. Dry run
        OverrideReport report = service.dryRun(suite, excelPath, specPath, options);
        assertNotNull(report);
        assertEquals(1, report.getSheets().size());
        assertEquals(0, report.getTotalErrors());
        assertEquals(5, report.getEntries().size());

        // Ensure TestCase was NOT modified during dryRun
        assertEquals("https://api.example.com/v1/pets/10", tc.getUrl());
        assertTrue(tc.getBodyTemplate().contains("\"Fluffy\""));
        assertNull(tc.getBaseline());

        // 2. Apply
        OverrideReport appliedReport = service.apply(suite, excelPath, specPath, options);
        assertNotNull(appliedReport);
        assertNotNull(tc.getBaseline(), "Baseline snapshot must be captured on apply");
        assertNotNull(tc.getLastOverride(), "Last override provenance must be recorded");
        assertEquals(5, tc.getLastOverride().getModifications() + tc.getLastOverride().getAdditions());

        // Verify mutations
        assertEquals("https://api.example.com/v1/pets/99?dryRun=true", tc.getUrl());
        assertEquals("trace-abc-123", tc.getHeaders().get("X-Trace-Id"));
        assertTrue(tc.getBodyTemplate().contains("Max"));
        assertTrue(tc.getBodyTemplate().contains("29.99"));
        // Check untouched field invariance
        assertTrue(tc.getBodyTemplate().contains("\"available\""));

        // 3. Re-apply Idempotency (Never stacks)
        OverrideReport secondApply = service.apply(suite, excelPath, specPath, options);
        assertNotNull(secondApply);
        // URL should still be identical, not having double ?dryRun=true&dryRun=true
        assertEquals("https://api.example.com/v1/pets/99?dryRun=true", tc.getUrl());

        // 4. Revert to baseline
        tc.revertToBaseline();
        assertEquals("https://api.example.com/v1/pets/10", tc.getUrl());
        assertFalse(tc.getHeaders().containsKey("X-Trace-Id"));
        assertTrue(tc.getBodyTemplate().contains("Fluffy"));
        assertFalse(tc.getBodyTemplate().contains("Max"));
    }
}
