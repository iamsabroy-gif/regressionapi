package com.sabyasachi.apiregression.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sabyasachi.apiregression.model.TestResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ReportWriter {

    private final ObjectMapper objectMapper;

    public ReportWriter() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void writeJson(List<TestResult> results, Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        objectMapper.writeValue(path.toFile(), results);
    }

    public void writeOverrideJson(com.sabyasachi.apiregression.payload.OverrideReport report, Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        objectMapper.writeValue(path.toFile(), report);
    }

    public void writeOverrideHtml(com.sabyasachi.apiregression.payload.OverrideReport report, Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"UTF-8\">\n")
                .append("<title>Parameter Override Report</title>\n")
                .append("<style>\n")
                .append(":root { --bg: #0f172a; --card: #1e293b; --text: #f8fafc; --muted: #94a3b8; --border: #334155; --pass: #10b981; --fail: #ef4444; --mod: #3b82f6; --add: #10b981; --skip: #64748b; }\n")
                .append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: var(--bg); color: var(--text); margin: 0; padding: 24px; font-size: 14px; }\n")
                .append(".container { max-width: 1200px; margin: 0 auto; }\n")
                .append(".header { border-bottom: 1px solid var(--border); padding-bottom: 16px; margin-bottom: 24px; }\n")
                .append(".stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; margin-bottom: 24px; }\n")
                .append(".card { background: var(--card); border: 1px solid var(--border); border-radius: 8px; padding: 14px; text-align: center; }\n")
                .append(".val { font-size: 26px; font-weight: 800; margin-top: 4px; }\n")
                .append(".lbl { font-size: 12px; color: var(--muted); text-transform: uppercase; }\n")
                .append("table { width: 100%; border-collapse: collapse; background: var(--card); border: 1px solid var(--border); border-radius: 8px; margin-bottom: 24px; overflow: hidden; }\n")
                .append("th, td { padding: 10px 14px; text-align: left; border-bottom: 1px solid var(--border); }\n")
                .append("th { background: #172033; font-size: 12px; color: var(--muted); text-transform: uppercase; }\n")
                .append(".badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 700; }\n")
                .append(".badge.MODIFIED { background: rgba(59, 130, 246, 0.2); color: #3b82f6; }\n")
                .append(".badge.ADDED { background: rgba(16, 185, 129, 0.2); color: #10b981; }\n")
                .append(".badge.SKIPPED { background: rgba(100, 116, 139, 0.2); color: #94a3b8; }\n")
                .append(".badge.ERROR { background: rgba(239, 68, 68, 0.2); color: #ef4444; }\n")
                .append("</style>\n</head>\n<body>\n<div class=\"container\">\n")
                .append("<div class=\"header\"><h1>Parameter Override Report (").append(report.isDryRun() ? "DRY RUN" : "APPLIED").append(")</h1>\n")
                .append("<div style=\"color:var(--muted);\">Workbook: <strong>").append(escapeHtml(report.getWorkbook())).append("</strong></div>\n")
                .append("<div style=\"color:var(--muted);font-size:12px;\">Spec: ").append(escapeHtml(report.getSpecPath() != null ? report.getSpecPath() : "None")).append("</div>\n")
                .append("</div>\n");

        sb.append("<div class=\"stats\">\n")
                .append("<div class=\"card\"><div class=\"lbl\">Modified</div><div class=\"val\" style=\"color:#3b82f6;\">").append(report.getTotalModified()).append("</div></div>\n")
                .append("<div class=\"card\"><div class=\"lbl\">Added from Spec</div><div class=\"val\" style=\"color:#10b981;\">").append(report.getTotalAdded()).append("</div></div>\n")
                .append("<div class=\"card\"><div class=\"lbl\">Skipped</div><div class=\"val\" style=\"color:#94a3b8;\">").append(report.getTotalSkipped()).append("</div></div>\n")
                .append("<div class=\"card\"><div class=\"lbl\">Errors</div><div class=\"val\" style=\"color:#ef4444;\">").append(report.getTotalErrors()).append("</div></div>\n")
                .append("</div>\n");

        sb.append("<h2>Sheet Summaries</h2>\n<table>\n<thead>\n<tr><th>Sheet</th><th>Target TestCase</th><th>Matched By</th><th>Status</th><th>Modified</th><th>Added</th><th>Skipped</th><th>Errors</th></tr></thead>\n<tbody>\n");
        for (var s : report.getSheets()) {
            sb.append("<tr><td><strong>").append(escapeHtml(s.getSheet())).append("</strong></td>")
                    .append("<td>").append(escapeHtml(s.getTargetTestCase())).append("</td>")
                    .append("<td>").append(escapeHtml(s.getMatchedBy())).append("</td>")
                    .append("<td>").append(escapeHtml(s.getStatus())).append("</td>")
                    .append("<td>").append(s.getModified()).append("</td>")
                    .append("<td>").append(s.getAdded()).append("</td>")
                    .append("<td>").append(s.getSkipped()).append("</td>")
                    .append("<td>").append(s.getErrors()).append("</td></tr>\n");
        }
        sb.append("</tbody></table>\n");

        sb.append("<h2>Override Entries</h2>\n<table>\n<thead>\n<tr><th>Sheet:Row</th><th>Parameter</th><th>Location</th><th>Action</th><th>Old Value</th><th>New Value</th><th>Reason / Details</th></tr></thead>\n<tbody>\n");
        for (var e : report.getEntries()) {
            sb.append("<tr><td>").append(escapeHtml(e.getSheet())).append(":").append(e.getRow()).append("</td>")
                    .append("<td><strong>").append(escapeHtml(e.getParameter())).append("</strong></td>")
                    .append("<td>").append(e.getLocation() != null ? e.getLocation() : "-").append("</td>")
                    .append("<td><span class=\"badge ").append(e.getAction()).append("\">").append(e.getAction()).append("</span></td>")
                    .append("<td>").append(escapeHtml(e.getOldValue() != null ? e.getOldValue().toString() : "-")).append("</td>")
                    .append("<td>").append(escapeHtml(e.getCoercedValue() != null ? e.getCoercedValue().toString() : (e.getRawValue() != null ? e.getRawValue() : "-"))).append("</td>")
                    .append("<td>");
            if (e.getReason() != null) sb.append(escapeHtml(e.getReason())).append(" ");
            if (e.getSpecPointer() != null) sb.append("<small style=\"color:var(--muted);\">(").append(escapeHtml(e.getSpecPointer())).append(")</small> ");
            if (!e.getWarnings().isEmpty()) sb.append("<br><small style=\"color:#f59e0b;\">Warnings: ").append(escapeHtml(String.join(", ", e.getWarnings()))).append("</small>");
            sb.append("</td></tr>\n");
        }
        sb.append("</tbody></table></div></body></html>");

        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    public void writeHtml(List<TestResult> results, Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        int total = results != null ? results.size() : 0;
        int passed = 0;
        long totalLatency = 0;

        if (results != null) {
            for (TestResult r : results) {
                if (r.isPassed()) passed++;
                totalLatency += r.getLatencyMs();
            }
        }
        int failed = total - passed;
        double passRate = total > 0 ? (passed * 100.0 / total) : 0.0;
        String nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
                .append("<title>API Regression Test Report</title>\n")
                .append("<style>\n")
                .append(":root { --bg: #0f172a; --card: #1e293b; --text: #f8fafc; --muted: #94a3b8; --border: #334155; --pass: #10b981; --fail: #ef4444; --accent: #3b82f6; }\n")
                .append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background: var(--bg); color: var(--text); margin: 0; padding: 24px; line-height: 1.5; }\n")
                .append(".container { max-width: 1200px; margin: 0 auto; }\n")
                .append(".header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border); padding-bottom: 16px; margin-bottom: 24px; }\n")
                .append(".header h1 { margin: 0; font-size: 24px; font-weight: 700; }\n")
                .append(".stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 24px; }\n")
                .append(".card { background: var(--card); border: 1px solid var(--border); border-radius: 8px; padding: 16px; text-align: center; }\n")
                .append(".card .val { font-size: 28px; font-weight: 800; margin-top: 4px; }\n")
                .append(".val.pass { color: var(--pass); } .val.fail { color: var(--fail); }\n")
                .append(".card .lbl { font-size: 13px; color: var(--muted); text-transform: uppercase; letter-spacing: 0.05em; }\n")
                .append("table { width: 100%; border-collapse: collapse; background: var(--card); border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }\n")
                .append("th, td { padding: 12px 16px; text-align: left; border-bottom: 1px solid var(--border); }\n")
                .append("th { background: #172033; font-size: 12px; color: var(--muted); text-transform: uppercase; }\n")
                .append(".badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: 600; text-transform: uppercase; }\n")
                .append(".badge.pass { background: rgba(16, 185, 129, 0.2); color: var(--pass); border: 1px solid var(--pass); }\n")
                .append(".badge.fail { background: rgba(239, 68, 68, 0.2); color: var(--fail); border: 1px solid var(--fail); }\n")
                .append(".badge.method { background: rgba(59, 130, 246, 0.2); color: var(--accent); }\n")
                .append(".badge.token { background: rgba(245, 158, 11, 0.2); color: #f59e0b; border: 1px solid #f59e0b; }\n")
                .append("details { margin-top: 6px; }\n")
                .append("summary { cursor: pointer; color: var(--accent); font-size: 13px; }\n")
                .append("pre { background: #090d16; border: 1px solid var(--border); border-radius: 6px; padding: 12px; font-size: 12px; overflow-x: auto; white-space: pre-wrap; word-break: break-all; }\n")
                .append("</style>\n</head>\n<body>\n")
                .append("<div class=\"container\">\n")
                .append("<div class=\"header\">\n")
                .append("<div><h1>API Regression Test Report</h1><div style=\"color:var(--muted);font-size:13px;margin-top:4px;\">Generated: ").append(escapeHtml(nowStr)).append("</div></div>\n")
                .append("</div>\n");

        sb.append("<div class=\"stats\">\n")
                .append("<div class=\"card\"><div class=\"lbl\">Total Tests</div><div class=\"val\">").append(total).append("</div></div>\n")
                .append("<div class=\"card\"><div class=\"lbl\">Passed</div><div class=\"val pass\">").append(passed).append("</div></div>\n")
                .append("<div class=\"card\"><div class=\"lbl\">Failed</div><div class=\"val fail\">").append(failed).append("</div></div>\n")
                .append("<div class=\"card\"><div class=\"lbl\">Pass Rate</div><div class=\"val\">").append(String.format("%.1f%%", passRate)).append("</div></div>\n")
                .append("<div class=\"card\"><div class=\"lbl\">Total Latency</div><div class=\"val\">").append(totalLatency).append(" ms</div></div>\n")
                .append("</div>\n");

        sb.append("<table>\n<thead>\n<tr>\n")
                .append("<th>Status</th><th>Method</th><th>Name / Category</th><th>HTTP Status</th><th>Latency</th><th>Diff / Details</th>\n")
                .append("</tr>\n</thead>\n<tbody>\n");

        if (results != null && !results.isEmpty()) {
            for (TestResult r : results) {
                boolean isPass = r.isPassed();
                sb.append("<tr>\n");
                sb.append("<td>");
                if (r.isTokenProvider()) {
                    sb.append("<span class=\"badge token\">TOKEN</span> ");
                }
                sb.append("<span class=\"badge ").append(isPass ? "pass" : "fail").append("\">")
                        .append(isPass ? "PASS" : "FAIL").append("</span></td>\n");

                sb.append("<td><span class=\"badge method\">").append(escapeHtml(r.getMethod() != null ? r.getMethod() : "GET")).append("</span></td>\n");

                sb.append("<td><strong>").append(escapeHtml(r.getTestCaseName())).append("</strong>");
                if (r.getCategory() != null && !r.getCategory().isBlank()) {
                    sb.append("<br><small style=\"color:var(--muted);\">").append(escapeHtml(r.getCategory())).append("</small>");
                }
                if (r.getResolvedUrl() != null) {
                    sb.append("<br><code style=\"font-size:11px;color:var(--muted);\">").append(escapeHtml(r.getResolvedUrl())).append("</code>");
                }
                sb.append("</td>\n");

                sb.append("<td>").append(r.getActualStatus());
                if (r.getExpectedStatus() > 0 && r.getExpectedStatus() != r.getActualStatus()) {
                    sb.append(" <small style=\"color:var(--muted);\">exp: ").append(r.getExpectedStatus()).append("</small>");
                }
                sb.append("</td>\n");

                sb.append("<td>").append(r.getLatencyMs()).append(" ms</td>\n");

                sb.append("<td>");
                if (r.getErrorMessage() != null) {
                    sb.append("<strong style=\"color:var(--fail);\">").append(escapeHtml(r.getErrorMessage())).append("</strong><br>");
                }
                if (r.getDiffSummary() != null && !r.getDiffSummary().isBlank()) {
                    sb.append("<details><summary>View Diff / Details</summary><pre>")
                            .append(escapeHtml(r.getDiffSummary()))
                            .append("</pre></details>");
                }
                if (r.getActualBody() != null && !r.getActualBody().isBlank()) {
                    sb.append("<details><summary>View Actual Body</summary><pre>")
                            .append(escapeHtml(r.getActualBody()))
                            .append("</pre></details>");
                }
                sb.append("</td>\n");

                sb.append("</tr>\n");
            }
        } else {
            sb.append("<tr><td colspan=\"6\" style=\"text-align:center;color:var(--muted);\">No test results available.</td></tr>\n");
        }

        sb.append("</tbody>\n</table>\n</div>\n</body>\n</html>");

        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
