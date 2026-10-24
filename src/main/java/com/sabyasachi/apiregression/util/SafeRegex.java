package com.sabyasachi.apiregression.util;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SafeRegex {

    public static final int MAX_PATTERN_LENGTH = 1_000;
    public static final int MAX_INPUT_LENGTH = 4_096;
    public static final Duration DEFAULT_DEADLINE = Duration.ofMillis(100);

    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    private SafeRegex() {
    }

    public static class RegexTimeoutException extends RuntimeException {
        public RegexTimeoutException(String message) {
            super(message);
        }
    }

    public static class SafeRegexResult {
        private final boolean matched;
        private final boolean skipped;
        private final String warning;

        public SafeRegexResult(boolean matched, boolean skipped, String warning) {
            this.matched = matched;
            this.skipped = skipped;
            this.warning = warning;
        }

        public boolean isMatched() {
            return matched;
        }

        public boolean isSkipped() {
            return skipped;
        }

        public String getWarning() {
            return warning;
        }

        public static SafeRegexResult match() {
            return new SafeRegexResult(true, false, null);
        }

        public static SafeRegexResult noMatch() {
            return new SafeRegexResult(false, false, null);
        }

        public static SafeRegexResult skipped(String warning) {
            return new SafeRegexResult(false, true, warning);
        }
    }

    public static class DeadlineCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final long deadlineNano;
        private int callCount = 0;

        public DeadlineCharSequence(CharSequence delegate, Duration timeout) {
            this.delegate = delegate != null ? delegate : "";
            this.deadlineNano = System.nanoTime() + timeout.toNanos();
        }

        private DeadlineCharSequence(CharSequence delegate, long deadlineNano) {
            this.delegate = delegate != null ? delegate : "";
            this.deadlineNano = deadlineNano;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if ((++callCount & 0xFF) == 0) {
                if (System.nanoTime() > deadlineNano) {
                    throw new RegexTimeoutException("Regex evaluation exceeded deadline");
                }
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(delegate.subSequence(start, end), deadlineNano);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    public static SafeRegexResult find(String patternStr, String input) {
        return find(patternStr, input, DEFAULT_DEADLINE);
    }

    public static SafeRegexResult find(String patternStr, String input, Duration timeout) {
        if (input == null) {
            return SafeRegexResult.noMatch();
        }
        if (patternStr == null || patternStr.isBlank()) {
            return SafeRegexResult.match();
        }

        if (input.length() > MAX_INPUT_LENGTH) {
            return SafeRegexResult.skipped("CONSTRAINT_PATTERN_SKIPPED: input length ("
                    + input.length() + ") exceeds limit of " + MAX_INPUT_LENGTH + " characters.");
        }

        if (patternStr.length() > MAX_PATTERN_LENGTH) {
            return SafeRegexResult.skipped("CONSTRAINT_PATTERN_SKIPPED: pattern length ("
                    + patternStr.length() + ") exceeds limit of " + MAX_PATTERN_LENGTH + " characters.");
        }

        Pattern pattern;
        try {
            pattern = PATTERN_CACHE.computeIfAbsent(patternStr, Pattern::compile);
        } catch (PatternSyntaxException e) {
            return SafeRegexResult.skipped("CONSTRAINT_PATTERN_SKIPPED: pattern syntax invalid: " + e.getMessage());
        }

        try {
            DeadlineCharSequence dcs = new DeadlineCharSequence(input, timeout);
            Matcher matcher = pattern.matcher(dcs);
            // JSON Schema pattern uses unanchored find() semantics
            boolean found = matcher.find();
            return found ? SafeRegexResult.match() : SafeRegexResult.noMatch();
        } catch (RegexTimeoutException e) {
            return SafeRegexResult.skipped("CONSTRAINT_PATTERN_SKIPPED: regex execution timed out (backtracking guard triggered)");
        }
    }
}
