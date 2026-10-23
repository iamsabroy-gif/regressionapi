package com.sabyasachi.apiregression.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class SafeRegexTest {

    @Test
    void s4_catastrophicPattern_returnsWithin500ms() {
        String pattern = "^(.*a){20}$";
        String input = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaab"; // 30 'a's followed by 'b'

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            long start = System.currentTimeMillis();
            SafeRegex.SafeRegexResult result = SafeRegex.find(pattern, input, Duration.ofMillis(100));
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed < 500, "Catastrophic regex must return within 500ms, was " + elapsed + "ms");
            assertTrue(result.isSkipped());
            assertTrue(result.getWarning().contains("timed out"));
        });
    }

    @Test
    void s4_overlongInput_skippedWithWarning() {
        String pattern = "^[a-z]+$";
        String input = "a".repeat(4097);

        SafeRegex.SafeRegexResult result = SafeRegex.find(pattern, input);
        assertTrue(result.isSkipped());
        assertTrue(result.getWarning().contains("input length (4097) exceeds limit"));
    }

    @Test
    void s4_overlongPattern_skipped() {
        String pattern = "a".repeat(1001);
        String input = "a";

        SafeRegex.SafeRegexResult result = SafeRegex.find(pattern, input);
        assertTrue(result.isSkipped());
        assertTrue(result.getWarning().contains("pattern length (1001) exceeds limit"));
    }

    @Test
    void s4_validPatternStillEnforced() {
        String pattern = "^[A-Z]{2}\\d{4}$";
        assertTrue(SafeRegex.find(pattern, "AB1234").isMatched());
        assertFalse(SafeRegex.find(pattern, "ab1234").isMatched());
    }

    @Test
    void s4_patternUnanchoredFindSemantics() {
        String pattern = "[A-Z]{2}\\d{4}";
        // Should match inside longer string since find() is unanchored per JSON schema spec
        assertTrue(SafeRegex.find(pattern, "prefix-AB1234-suffix").isMatched());
    }
}
