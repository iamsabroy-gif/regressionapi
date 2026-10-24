package com.sabyasachi.apiregression.payload;

import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sabyasachi.apiregression.spec.SpecIndex;

import java.util.ArrayList;
import java.util.List;

public class TargetMatcher {

    public static class MatchResult {
        private TestCase testCase;
        private int matchCount = 0;
        private String matchedBy; // NAME | OPERATION_ID | METHOD_PATH
        private final List<String> candidateNames = new ArrayList<>();
        private String resolvedOperationId;

        public TestCase getTestCase() {
            return testCase;
        }

        public int getMatchCount() {
            return matchCount;
        }

        public String getMatchedBy() {
            return matchedBy;
        }

        public List<String> getCandidateNames() {
            return candidateNames;
        }

        public String getResolvedOperationId() {
            return resolvedOperationId;
        }
    }

    public static MatchResult match(TestSuite suite, String target, SpecIndex specIndex) {
        MatchResult res = new MatchResult();
        if (suite == null || suite.getTestCases() == null || target == null || target.isBlank()) {
            return res;
        }
        String cleanTarget = target.trim();

        // 1. Exact match on TestCase.name
        for (TestCase tc : suite.getTestCases()) {
            if (cleanTarget.equals(tc.getName())) {
                res.matchCount++;
                res.testCase = tc;
                res.matchedBy = "NAME";
                res.candidateNames.add(tc.getName());
            }
        }
        if (res.matchCount == 1) return res;
        if (res.matchCount > 1) return res; // Ambiguous on name

        // 2. Match on operationId
        res = new MatchResult();
        for (TestCase tc : suite.getTestCases()) {
            if (cleanTarget.equals(tc.getOperationId())) {
                res.matchCount++;
                res.testCase = tc;
                res.matchedBy = "OPERATION_ID";
                res.candidateNames.add(tc.getName());
            } else if (specIndex != null && tc.getUrl() != null && tc.getMethod() != null) {
                try {
                    SpecIndex.OperationMatch opMatch = specIndex.matchOperation(tc.getOperationId(), tc.getMethod(), tc.getUrl());
                    if (opMatch != null && opMatch.getOperation() != null && cleanTarget.equals(opMatch.getOperation().getOperationId())) {
                        res.matchCount++;
                        res.testCase = tc;
                        res.matchedBy = "OPERATION_ID";
                        res.resolvedOperationId = opMatch.getOperation().getOperationId();
                        res.candidateNames.add(tc.getName());
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (res.matchCount == 1) return res;
        if (res.matchCount > 1) return res;

        // 3. Match on METHOD /path-template
        res = new MatchResult();
        String[] parts = cleanTarget.split("\\s+", 2);
        if (parts.length == 2) {
            String targetMethod = parts[0].toUpperCase();
            String targetTemplate = parts[1].trim();
            if (!targetTemplate.startsWith("/")) {
                targetTemplate = "/" + targetTemplate;
            }

            for (TestCase tc : suite.getTestCases()) {
                if (tc.getMethod() != null && tc.getMethod().equalsIgnoreCase(targetMethod)) {
                    if (matchPathTemplate(tc.getUrl(), targetTemplate, specIndex)) {
                        res.matchCount++;
                        res.testCase = tc;
                        res.matchedBy = "METHOD_PATH";
                        res.candidateNames.add(tc.getName());
                    }
                }
            }
        }

        return res;
    }

    public static boolean matchPathTemplate(String actualUrl, String targetTemplate, SpecIndex specIndex) {
        if (actualUrl == null || targetTemplate == null) return false;

        UrlParts actualParts = UrlParts.parse(actualUrl);
        String actualPath = actualParts.getPath();

        // Strip basePath/server url if specIndex present
        if (specIndex != null && specIndex.getBasePath() != null) {
            String bp = specIndex.getBasePath();
            if (bp.endsWith("/")) bp = bp.substring(0, bp.length() - 1);
            if (!bp.isEmpty() && (actualPath.equals(bp) || actualPath.startsWith(bp + "/"))) {
                actualPath = actualPath.substring(bp.length());
                if (!actualPath.startsWith("/")) actualPath = "/" + actualPath;
            }
        }

        String[] actualSegments = splitSegments(actualPath);
        String[] templateSegments = splitSegments(targetTemplate);

        if (actualSegments.length != templateSegments.length) {
            return false;
        }

        for (int i = 0; i < actualSegments.length; i++) {
            String tSeg = templateSegments[i];
            String aSeg = actualSegments[i];

            // If template segment is a parameter: {id} or :id
            if (isTemplateParam(tSeg)) {
                // Matches any one non-empty segment
                if (aSeg.isEmpty()) return false;
            } else {
                // Literal match (case-sensitive)
                if (!tSeg.equals(aSeg)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean isTemplateParam(String seg) {
        if (seg == null) return false;
        return (seg.startsWith("{") && seg.endsWith("}")) || seg.startsWith(":");
    }

    private static String[] splitSegments(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return new String[0];
        }
        String clean = path.startsWith("/") ? path.substring(1) : path;
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean.split("/");
    }
}
