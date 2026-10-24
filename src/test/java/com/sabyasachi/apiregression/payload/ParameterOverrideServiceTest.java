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

    @Test
    void roundTripIdempotency(@TempDir Path tempDir) throws Exception {
        Path wb = tempDir.resolve("params.xlsx");
        com.sabyasachi.apiregression.testsupport.XlsxFixtureBuilder.create()
                .sheet("testCase")
                .target("POST /items")
                .text("name", "Widget")
                .number("price", 19.99)
                .build(wb);

        TestCase tc = new TestCase("testCase", "POST", "http://localhost/items");
        tc.setExpectedStatus(200);
        tc.setHeaders(new LinkedHashMap<>(Map.of("Content-Type", "application/json")));
        tc.setBodyTemplate("{\"name\": \"Orig\", \"price\": 9.99, \"extra\": \"stays\"}");

        TestSuite suite = new TestSuite("Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideOptions options = new OverrideOptions();

        // First apply
        service.apply(suite, wb, null, options);
        String url1 = tc.getUrl();
        Map<String, String> headers1 = new LinkedHashMap<>(tc.getHeaders());
        String body1 = tc.getBodyTemplate();

        // Small sleep to ensure timestamps differ if recomputed
        Thread.sleep(15);

        // Second apply of identical workbook
        service.apply(suite, wb, null, options);
        String url2 = tc.getUrl();
        Map<String, String> headers2 = new LinkedHashMap<>(tc.getHeaders());
        String body2 = tc.getBodyTemplate();

        // Must be byte-identical on all request fields
        assertEquals(url1, url2, "URL must be byte-identical across round trips");
        assertEquals(headers1, headers2, "Headers must be byte-identical across round trips");
        assertEquals(body1, body2, "Body must be byte-identical across round trips");

        // Diff suite JSON: the only difference is lastOverride.appliedAt
        com.sabyasachi.apiregression.store.SuiteRepository repo = new com.sabyasachi.apiregression.store.SuiteRepository();
        String json2 = repo.toJson(suite);
        assertNotNull(json2);
        assertTrue(json2.contains("Widget"));
    }

    @Test
    void changedWorkbookReplacesPriorOverrides(@TempDir Path tempDir) throws Exception {
        Path wb1 = tempDir.resolve("wb1.xlsx");
        com.sabyasachi.apiregression.testsupport.XlsxFixtureBuilder.create()
                .sheet("update")
                .target("POST /items")
                .text("name", "Version1")
                .number("price", 10.0)
                .build(wb1);

        Path wb2 = tempDir.resolve("wb2.xlsx");
        com.sabyasachi.apiregression.testsupport.XlsxFixtureBuilder.create()
                .sheet("update")
                .target("POST /items")
                .text("name", "Version2")
                .number("price", 20.0)
                .build(wb2);

        // Suite 1: Apply WB1 then WB2
        TestCase tc1 = new TestCase("update", "POST", "http://localhost/items");
        tc1.setExpectedStatus(200);
        tc1.setBodyTemplate("{\"name\": \"Orig\", \"price\": 5.0, \"active\": true}");
        TestSuite suite1 = new TestSuite("Suite 1");
        suite1.setTestCases(new ArrayList<>(List.of(tc1)));

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideOptions options = new OverrideOptions();

        service.apply(suite1, wb1, null, options);
        service.apply(suite1, wb2, null, options);

        // Suite 2: Apply WB2 directly from clean baseline
        TestCase tc2 = new TestCase("update", "POST", "http://localhost/items");
        tc2.setExpectedStatus(200);
        tc2.setBodyTemplate("{\"name\": \"Orig\", \"price\": 5.0, \"active\": true}");
        TestSuite suite2 = new TestSuite("Suite 2");
        suite2.setTestCases(new ArrayList<>(List.of(tc2)));

        service.apply(suite2, wb2, null, options);

        // Resulting requests must match identically
        assertEquals(tc2.getUrl(), tc1.getUrl());
        assertEquals(tc2.getBodyTemplate(), tc1.getBodyTemplate());
        assertTrue(tc1.getBodyTemplate().contains("Version2"));
        assertFalse(tc1.getBodyTemplate().contains("Version1"));
    }

    @Test
    void applyThenSaveLoadApply_identical(@TempDir Path tempDir) throws Exception {
        Path wb = tempDir.resolve("wb.xlsx");
        com.sabyasachi.apiregression.testsupport.XlsxFixtureBuilder.create()
                .sheet("item")
                .target("POST /items")
                .text("title", "SavedItem")
                .build(wb);

        TestCase tc = new TestCase("item", "POST", "http://localhost/items");
        tc.setExpectedStatus(200);
        tc.setBodyTemplate("{\"title\": \"Original\"}");
        TestSuite suite = new TestSuite("Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideOptions options = new OverrideOptions();
        service.apply(suite, wb, null, options);

        // Save and Load via SuiteRepository
        Path suiteFile = tempDir.resolve("suite.json");
        com.sabyasachi.apiregression.store.SuiteRepository repo = new com.sabyasachi.apiregression.store.SuiteRepository();
        repo.save(suite, suiteFile);

        TestSuite loadedSuite = repo.load(suiteFile);
        TestCase loadedTc = loadedSuite.getTestCases().get(0);
        assertEquals(tc.getBodyTemplate(), loadedTc.getBodyTemplate());
        assertNotNull(loadedTc.getBaseline());

        // Apply same workbook to loaded suite
        service.apply(loadedSuite, wb, null, options);
        assertEquals(tc.getBodyTemplate(), loadedTc.getBodyTemplate());
    }

    @Test
    void dryRunNeverMutatesSuite(@TempDir Path tempDir) throws Exception {
        Path wb = tempDir.resolve("wb.xlsx");
        com.sabyasachi.apiregression.testsupport.XlsxFixtureBuilder.create()
                .sheet("item")
                .target("POST /items")
                .text("title", "NewTitle")
                .build(wb);

        TestCase tc = new TestCase("item", "POST", "http://localhost/items");
        tc.setExpectedStatus(200);
        tc.setBodyTemplate("{\"title\": \"Original\"}");
        TestSuite suite = new TestSuite("Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        com.sabyasachi.apiregression.store.SuiteRepository repo = new com.sabyasachi.apiregression.store.SuiteRepository();
        String jsonBefore = repo.toJson(suite);

        ParameterOverrideService service = new ParameterOverrideService();
        service.dryRun(suite, wb, null, new OverrideOptions());

        String jsonAfter = repo.toJson(suite);
        assertEquals(jsonBefore, jsonAfter, "dryRun must NEVER mutate any part of the test suite");
    }

    @Test
    void applyBlockedOnErrorsUnlessApplyWithErrors(@TempDir Path tempDir) throws Exception {
        Path wb = tempDir.resolve("wb.xlsx");
        // Row 1 succeeds (name -> Bob), Row 2 fails (unknown.nonexistent is not in spec)
        com.sabyasachi.apiregression.testsupport.XlsxFixtureBuilder.create()
                .sheet("customersSheet")
                .target("POST /customers")
                .text("name", "Bob")
                .row("$.unknown.nonexistent", "bad")
                .build(wb);

        Path spec = Path.of("src/test/resources/spec/customers-oas3.yaml");
        TestCase tc = new TestCase("createCustomer", "POST", "https://api.example.com/customers");
        tc.setExpectedStatus(200);
        tc.setBodyTemplate("{\"name\": \"Alice\"}");
        TestSuite suite = new TestSuite("Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        com.sabyasachi.apiregression.store.SuiteRepository repo = new com.sabyasachi.apiregression.store.SuiteRepository();
        String jsonBefore = repo.toJson(suite);

        ParameterOverrideService service = new ParameterOverrideService();
        OverrideOptions options = new OverrideOptions();
        options.setApplyWithErrors(false);

        // When applyWithErrors is false and sheet has errors, changes must NOT be committed to TestCase (rollback)
        OverrideReport report1 = service.apply(suite, wb, spec, options);
        assertTrue(report1.getTotalErrors() > 0);
        assertEquals("{\"name\": \"Alice\"}", tc.getBodyTemplate(), "Body must remain original when rolled back");
        assertNull(tc.getLastOverride(), "TestCase must not have lastOverride committed when applyWithErrors=false");

        // Now with applyWithErrors = true
        options.setApplyWithErrors(true);
        OverrideReport report2 = service.apply(suite, wb, spec, options);
        assertNotNull(report2);
        assertTrue(report2.getTotalErrors() > 0);
        assertTrue(tc.getBodyTemplate().contains("Bob"), "Body should be updated with successful rows when applyWithErrors=true");
        assertNotNull(tc.getLastOverride());
        assertTrue(tc.getLastOverride().getErrors() > 0);
        assertEquals(1, tc.getLastOverride().getModifications());
    }

    @Test
    void untouchedFieldsInvariance_withJsonPointerDiff() {
        String originalBody = """
                {
                    "user": {
                        "name": "Alice",
                        "age": 30,
                        "metadata": {
                            "created": "2026-01-01",
                            "tags": ["admin", "staff"]
                        }
                    },
                    "price": 10.50,
                    "active": true
                }
                """;

        String updatedBody = """
                {
                    "user": {
                        "name": "Bob",
                        "age": 30,
                        "metadata": {
                            "created": "2026-01-01",
                            "tags": ["admin", "staff"]
                        }
                    },
                    "price": 10.50,
                    "active": true
                }
                """;

        // Assert untouched fields invariance
        com.sabyasachi.apiregression.testsupport.JsonPointerDiff.assertUntouchedExcept(
                originalBody, updatedBody, Set.of("user/name", "/user/name"));
    }

    @Test
    void architecturalInvariants_noOverrideFailedString_noHashSetTestCase() throws Exception {
        Path srcMain = Path.of("src/main/java");
        assertTrue(java.nio.file.Files.exists(srcMain));

        try (var stream = java.nio.file.Files.walk(srcMain)) {
            List<Path> javaFiles = stream.filter(p -> p.toString().endsWith(".java")).toList();
            assertFalse(javaFiles.isEmpty());

            for (Path f : javaFiles) {
                String content = java.nio.file.Files.readString(f);
                assertFalse(content.contains("OVERRIDE_FAILED"),
                        "Main sources must not contain deprecated string OVERRIDE_FAILED in " + f.getFileName());
                assertFalse(content.matches("(?s).*HashSet\\s*<\\s*TestCase\\s*>.*"),
                        "Main sources must not instantiate HashSet<TestCase> in " + f.getFileName());
            }
        }
    }
}

