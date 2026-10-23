package com.sabyasachi.apiregression.compare;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JsonComparatorTest {

    @Test
    void testExactMatch() {
        String expected = "{\"id\": 1, \"name\": \"Antigravity\", \"roles\": [\"dev\", \"tester\"]}";
        String actual = "{\"roles\": [\"dev\", \"tester\"], \"name\": \"Antigravity\", \"id\": 1}";

        JsonComparator.ComparisonResult result = JsonComparator.compare(expected, actual, List.of());
        assertTrue(result.isPassed());
    }

    @Test
    void testMismatchProducesReadableDiff() {
        String expected = "{\"status\": \"ACTIVE\", \"count\": 10}";
        String actual = "{\"status\": \"INACTIVE\", \"count\": 10}";

        JsonComparator.ComparisonResult result = JsonComparator.compare(expected, actual, List.of());
        assertFalse(result.isPassed());
        assertNotNull(result.getDiffSummary());
        assertTrue(result.getDiffSummary().contains("status") || result.getDiffSummary().contains("ACTIVE"));
    }

    @Test
    void testIgnorePathsVolatileFields() {
        String expected = "{\"id\": 100, \"name\": \"Antigravity\", \"createdAt\": \"2020-01-01\", \"uuid\": \"aaa\"}";
        String actual = "{\"id\": 999, \"name\": \"Antigravity\", \"createdAt\": \"2026-10-23\", \"uuid\": \"bbb\"}";

        // Without ignore paths, should fail
        JsonComparator.ComparisonResult failedResult = JsonComparator.compare(expected, actual, List.of());
        assertFalse(failedResult.isPassed());

        // With ignore paths, should pass!
        List<String> ignorePaths = List.of("$.id", "$.createdAt", "$.uuid");
        JsonComparator.ComparisonResult passedResult = JsonComparator.compare(expected, actual, ignorePaths);
        assertTrue(passedResult.isPassed(), "Should pass when volatile paths are ignored: " + passedResult.getDiffSummary());
    }

    @Test
    void testEmptyExpectedTemplateSkipped() {
        String expected = "";
        String actual = "{\"message\": \"any response\"}";

        JsonComparator.ComparisonResult result = JsonComparator.compare(expected, actual, List.of());
        assertTrue(result.isPassed());
    }

    @Test
    void testIgnorePathHeuristic() {
        String json = "{\"id\": 12, \"user\": {\"uuid\": \"x-y-z\", \"createdAt\": \"2026-01-01\", \"email\": \"test@example.com\"}}";
        List<String> guessed = IgnorePathHeuristic.guessVolatilePaths(json);

        assertTrue(guessed.contains("$.id"));
        assertTrue(guessed.contains("$.user.uuid"));
        assertTrue(guessed.contains("$.user.createdAt"));
        assertFalse(guessed.contains("$.user.email"));
    }
}
