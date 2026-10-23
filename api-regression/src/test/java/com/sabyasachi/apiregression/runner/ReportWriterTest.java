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
}
