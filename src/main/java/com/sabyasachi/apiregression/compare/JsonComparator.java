package com.sabyasachi.apiregression.compare;

import net.javacrumbs.jsonunit.core.Configuration;
import net.javacrumbs.jsonunit.core.internal.Diff;

import java.util.*;

public class JsonComparator {

    public static class ComparisonResult {
        private final boolean passed;
        private final String diffSummary;

        public ComparisonResult(boolean passed, String diffSummary) {
            this.passed = passed;
            this.diffSummary = diffSummary;
        }

        public boolean isPassed() {
            return passed;
        }

        public String getDiffSummary() {
            return diffSummary;
        }
    }

    public static ComparisonResult compare(String expectedJson, String actualJson, List<String> ignorePaths) {
        // If no expected JSON is specified, we consider the body check as passed
        if (expectedJson == null || expectedJson.trim().isEmpty()) {
            return new ComparisonResult(true, "No expected JSON template specified (skipped body diff).");
        }

        if (actualJson == null) {
            return new ComparisonResult(false, "Actual response body is null (expected JSON).");
        }

        String expTrimmed = expectedJson.trim();
        String actTrimmed = actualJson.trim();

        // Handle empty bodies
        if (expTrimmed.isEmpty() && actTrimmed.isEmpty()) {
            return new ComparisonResult(true, "Both expected and actual bodies are empty.");
        }

        // Clean up ignorePaths: json-unit expects dot-notation like "id", "createdAt", or "user.profile.id" without "$." prefix
        List<String> normalizedIgnorePaths = normalizeIgnorePaths(ignorePaths);

        Configuration configuration = Configuration.empty();
        if (!normalizedIgnorePaths.isEmpty()) {
            configuration = configuration.whenIgnoringPaths(normalizedIgnorePaths.toArray(new String[0]));
        }

        try {
            Diff diff = Diff.create(expTrimmed, actTrimmed, "fullJson", "", configuration);
            boolean similar = diff.similar();
            if (similar) {
                return new ComparisonResult(true, "JSON bodies match.");
            } else {
                return new ComparisonResult(false, diff.differences());
            }
        } catch (Exception e) {
            // One of them was likely not valid JSON (e.g., HTML error response or malformed template)
            if (expTrimmed.equals(actTrimmed)) {
                return new ComparisonResult(true, "Raw text bodies match exactly (non-JSON).");
            }
            return new ComparisonResult(false, "JSON parsing/comparison failed: " + e.getMessage() +
                    "\nExpected:\n" + truncate(expTrimmed, 500) +
                    "\nActual:\n" + truncate(actTrimmed, 500));
        }
    }

    private static List<String> normalizeIgnorePaths(List<String> rawPaths) {
        if (rawPaths == null || rawPaths.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> normalized = new ArrayList<>();
        for (String p : rawPaths) {
            if (p == null || p.isBlank()) continue;
            String clean = p.trim();
            // Remove leading "$." or "$"
            if (clean.startsWith("$.")) {
                clean = clean.substring(2);
            } else if (clean.startsWith("$")) {
                clean = clean.substring(1);
            }
            if (!clean.isEmpty()) {
                normalized.add(clean);
            }
        }
        return normalized;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "... [truncated]";
    }
}
