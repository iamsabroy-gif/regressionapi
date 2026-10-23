package com.sabyasachi.apiregression.ui;

import com.sabyasachi.apiregression.payload.OverrideReport;

public class ApplyResultFormatter {

    public static String format(OverrideReport report) {
        if (report == null) {
            return "No report available.";
        }

        long appliedSheets = 0;
        long partialSheets = 0;
        long notAppliedSheets = 0;
        long skippedSheets = 0;

        if (report.getSheets() != null) {
            for (var s : report.getSheets()) {
                String st = s.getStatus() != null ? s.getStatus().toUpperCase() : "";
                if ("APPLIED".equals(st)) {
                    appliedSheets++;
                } else if ("PARTIAL".equals(st)) {
                    partialSheets++;
                } else if ("SKIPPED".equals(st)) {
                    skippedSheets++;
                } else {
                    notAppliedSheets++;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Overrides applied successfully!\n");
        sb.append("Sheets: ").append(appliedSheets + partialSheets).append(" applied");
        if (notAppliedSheets > 0) {
            sb.append(", ").append(notAppliedSheets).append(" not applied");
        }
        if (skippedSheets > 0) {
            sb.append(", ").append(skippedSheets).append(" skipped");
        }
        sb.append("\n");
        sb.append("Modified: ").append(report.getTotalModified())
                .append(" | Added: ").append(report.getTotalAdded())
                .append(" | Skipped: ").append(report.getTotalSkipped())
                .append(" | Errors: ").append(report.getTotalErrors());

        return sb.toString();
    }
}
