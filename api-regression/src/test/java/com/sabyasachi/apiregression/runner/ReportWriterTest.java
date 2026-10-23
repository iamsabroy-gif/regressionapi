package com.sabyasachi.apiregression.runner;

import com.sabyasachi.apiregression.payload.OverrideAction;
import com.sabyasachi.apiregression.model.TestResult;
import com.sabyasachi.apiregression.payload.OverrideEntry;
import com.sabyasachi.apiregression.payload.OverrideReport;
import com.sabyasachi.apiregression.payload.SheetSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ReportWriterTest {

    @Test
    public void testWriteTestResultsJsonAndHtml(@TempDir Path tempDir) throws IOException {
        ReportWriter writer = new ReportWriter();

        TestResult result = new TestResult();
        result.setTestCaseName("Get Users");
        result.setStatusPassed(true);
        result.setBodyPassed(true);
        result.setActualStatus(200);
        result.setLatencyMs(45);
        result.setExecutedAt(Instant.now());

        Path jsonPath = tempDir.resolve("results.json");
        writer.writeJson(List.of(result), jsonPath);
        assertTrue(Files.exists(jsonPath));
        String jsonContent = Files.readString(jsonPath);
        assertTrue(jsonContent.contains("\"testCaseName\" : \"Get Users\""));
        assertTrue(jsonContent.contains("\"actualStatus\" : 200"));

        Path htmlPath = tempDir.resolve("results.html");
        writer.writeHtml(List.of(result), htmlPath);
        assertTrue(Files.exists(htmlPath));
        String htmlContent = Files.readString(htmlPath);
        assertTrue(htmlContent.contains("API Regression Test Report"));
        assertTrue(htmlContent.contains("Get Users"));
    }

    @Test
    public void testWriteOverrideJsonAndHtml(@TempDir Path tempDir) throws IOException {
        ReportWriter writer = new ReportWriter();

        OverrideReport report = new OverrideReport();
        report.setWorkbook("params.xlsx");
        report.setWorkbookSha256("abc123sha");
        report.setSpecPath("specs/api.json");
        report.setSpecVersion("3.0.3");
        report.setSpecSha256("xyz987sha");
        report.setCreatedAt(Instant.now());

        SheetSummary summary = new SheetSummary();
        summary.setSheet("updateUser");
        summary.setTargetTestCase("Update User Profile");
        summary.setMatchedBy("NAME");
        summary.setModified(2);
        summary.setAdded(1);
        summary.setSkipped(0);
        summary.setErrors(0);
        summary.setStatus("APPLIED");
        report.getSheets().add(summary);

        OverrideEntry entry = new OverrideEntry();
        entry.setSheet("updateUser");
        entry.setRow(2);
        entry.setParameter("address.city");
        entry.setAction(OverrideAction.MODIFIED);
        entry.setResolvedPath("/address/city");
        entry.setRawValue("Seattle");
        report.getEntries().add(entry);

        Path jsonPath = tempDir.resolve("override_report.json");
        writer.writeOverrideJson(report, jsonPath);
        assertTrue(Files.exists(jsonPath));
        String json = Files.readString(jsonPath);
        assertTrue(json.contains("\"workbook\" : \"params.xlsx\""));
        assertTrue(json.contains("\"address.city\""));

        Path htmlPath = tempDir.resolve("override_report.html");
        writer.writeOverrideHtml(report, htmlPath);
        assertTrue(Files.exists(htmlPath));
        String html = Files.readString(htmlPath);
        assertTrue(html.contains("Parameter Override Report"));
        assertTrue(html.contains("params.xlsx"));
        assertTrue(html.contains("updateUser"));
        assertTrue(html.contains("address.city"));
    }

    @Test
    public void s6_htmlEscapesAllFields(@TempDir Path tempDir) throws IOException {
        ReportWriter writer = new ReportWriter();

        OverrideReport report = new OverrideReport();
        report.setWorkbook("dangerous<script>alert(1)</script>.xlsx");

        SheetSummary summary = new SheetSummary();
        summary.setSheet("<script>alert('sheet')</script>");
        summary.setTargetTestCase("Dangerous Case <svg onload=alert(2)>");
        summary.setStatus("STATUS<tag>");
        summary.setMatchedBy("MATCH&VAL");
        report.getSheets().add(summary);

        OverrideEntry entry = new OverrideEntry();
        entry.setSheet("<script>alert('sheet')</script>");
        entry.setRow(1);
        entry.setParameter("name&param<test>");
        entry.setAction(OverrideAction.MODIFIED);
        entry.setRawValue("\"><img onerror=alert(3)>");
        entry.setReason("Reason with <script> and & symbol");
        entry.getWarnings().add("Warning <script>");
        report.getEntries().add(entry);

        Path htmlPath = tempDir.resolve("escaped_override.html");
        writer.writeOverrideHtml(report, htmlPath);

        String html = Files.readString(htmlPath);
        // Assert raw dangerous tags are absent
        assertFalse(html.contains("<script>"), "HTML report must not contain raw <script>");
        assertFalse(html.contains("<svg onload"), "HTML report must not contain raw <svg>");
        assertFalse(html.contains("<img onerror"), "HTML report must not contain raw <img onerror>");

        // Assert escaped characters present
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&amp;"));
        assertTrue(html.contains("&quot;&gt;&lt;img"));
    }

    @Test
    public void s6_cspMetaPresent(@TempDir Path tempDir) throws IOException {
        ReportWriter writer = new ReportWriter();

        Path resultsHtml = tempDir.resolve("results.html");
        writer.writeHtml(List.of(new TestResult("Test A")), resultsHtml);

        Path overrideHtml = tempDir.resolve("override.html");
        OverrideReport report = new OverrideReport();
        report.setWorkbook("test.xlsx");
        writer.writeOverrideHtml(report, overrideHtml);

        String rHtml = Files.readString(resultsHtml);
        String oHtml = Files.readString(overrideHtml);

        String expectedCsp = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:\">";
        assertTrue(rHtml.contains(expectedCsp), "results.html must have CSP meta tag");
        assertTrue(oHtml.contains(expectedCsp), "override.html must have CSP meta tag");
        assertTrue(rHtml.contains("<meta charset=\"UTF-8\">"));
        assertTrue(oHtml.contains("<meta charset=\"UTF-8\">"));
    }

    @Test
    public void writeOverrideJson_hasHeaderAndPerSheetCounts(@TempDir Path tempDir) throws IOException {
        ReportWriter writer = new ReportWriter();

        OverrideReport report = new OverrideReport();
        report.setWorkbook("customers.xlsx");
        report.setWorkbookSha256("sha-wb-123");
        report.setSpecPath("customers-oas3.yaml");
        report.setSpecVersion("openapi: 3.0.3");
        report.setSpecSha256("sha-spec-456");
        report.setPolicy(com.sabyasachi.apiregression.payload.ConstraintPolicy.STRICT);
        report.setDryRun(true);
        report.setCreatedAt(Instant.now());

        SheetSummary s1 = new SheetSummary("Sheet1", "POST /customers");
        s1.setModified(3);
        s1.setAdded(2);
        s1.setErrors(1);
        report.getSheets().add(s1);

        Path jsonPath = tempDir.resolve("override_header.json");
        writer.writeOverrideJson(report, jsonPath);

        String json = Files.readString(jsonPath);
        assertTrue(json.contains("\"workbook\" : \"customers.xlsx\""));
        assertTrue(json.contains("\"workbookSha256\" : \"sha-wb-123\""));
        assertTrue(json.contains("\"specPath\" : \"customers-oas3.yaml\""));
        assertTrue(json.contains("\"specVersion\" : \"openapi: 3.0.3\""));
        assertTrue(json.contains("\"specSha256\" : \"sha-spec-456\""));
        assertTrue(json.contains("\"policy\" : \"STRICT\""));
        assertTrue(json.contains("\"dryRun\" : true"));
        assertTrue(json.contains("\"createdAt\""));
        assertTrue(json.contains("\"sheets\""));
    }

    @Test
    public void writeOverrideHtml_summaryTableAndErrorRowsFirst(@TempDir Path tempDir) throws IOException {
        ReportWriter writer = new ReportWriter();

        OverrideReport report = new OverrideReport();
        report.setWorkbook("customers.xlsx");
        report.setWorkbookSha256("sha-wb");
        report.setSpecPath("spec.yaml");
        report.setSpecVersion("3.0.3");
        report.setSpecSha256("sha-spec");
        report.setPolicy(com.sabyasachi.apiregression.payload.ConstraintPolicy.WARN);
        report.setDryRun(false);
        report.setCreatedAt(Instant.now());

        report.getSpecMessages().add("Test spec warning message");

        // Add entries out of order: MODIFIED, then ADDED, then ERROR, then SKIPPED
        OverrideEntry eMod = new OverrideEntry();
        eMod.setSheet("S1"); eMod.setRow(1); eMod.setParameter("modParam"); eMod.setAction(OverrideAction.MODIFIED);
        report.getEntries().add(eMod);

        OverrideEntry eAdd = new OverrideEntry();
        eAdd.setSheet("S1"); eAdd.setRow(2); eAdd.setParameter("addParam"); eAdd.setAction(OverrideAction.ADDED);
        report.getEntries().add(eAdd);

        OverrideEntry eErr = new OverrideEntry();
        eErr.setSheet("S1"); eErr.setRow(3); eErr.setParameter("errParam"); eErr.setAction(OverrideAction.ERROR); eErr.setReason("Some error");
        report.getEntries().add(eErr);

        OverrideEntry eSkip = new OverrideEntry();
        eSkip.setSheet("S1"); eSkip.setRow(4); eSkip.setParameter("skipParam"); eSkip.setAction(OverrideAction.SKIPPED);
        report.getEntries().add(eSkip);

        Path htmlPath = tempDir.resolve("sorted_report.html");
        writer.writeOverrideHtml(report, htmlPath);

        String html = Files.readString(htmlPath);
        assertTrue(html.contains("Specification Messages:"));
        assertTrue(html.contains("Test spec warning message"));

        // Verify order in HTML: ERROR must appear before SKIPPED, before ADDED, before MODIFIED
        int idxErr = html.indexOf("errParam");
        int idxSkip = html.indexOf("skipParam");
        int idxAdd = html.indexOf("addParam");
        int idxMod = html.indexOf("modParam");

        assertTrue(idxErr != -1 && idxSkip != -1 && idxAdd != -1 && idxMod != -1);
        assertTrue(idxErr < idxSkip, "ERROR should appear before SKIPPED");
        assertTrue(idxSkip < idxAdd, "SKIPPED should appear before ADDED");
        assertTrue(idxAdd < idxMod, "ADDED should appear before MODIFIED");
    }
}
