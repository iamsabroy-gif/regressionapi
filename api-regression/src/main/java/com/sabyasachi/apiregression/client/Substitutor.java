package com.sabyasachi.apiregression.client;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Substitutor {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");

    public static class SubstitutionResult {
        private final String result;
        private final List<String> unresolved;

        public SubstitutionResult(String result, List<String> unresolved) {
            this.result = result;
            this.unresolved = unresolved != null ? unresolved : Collections.emptyList();
        }

        public String getResult() {
            return result;
        }

        public List<String> getUnresolved() {
            return unresolved;
        }

        public boolean hasUnresolved() {
            return !unresolved.isEmpty();
        }
    }

    public static SubstitutionResult substitute(String input, Map<String, String> variables) {
        if (input == null) {
            return new SubstitutionResult(null, Collections.emptyList());
        }
        if (variables == null) {
            variables = Collections.emptyMap();
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        List<String> unresolved = new ArrayList<>();

        while (matcher.find()) {
            String key = matcher.group(1).trim();
            if (variables.containsKey(key)) {
                String replacement = variables.get(key);
                // Quote replacement to treat characters like $ and \ literally
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement != null ? replacement : ""));
            } else {
                unresolved.add(key);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);

        return new SubstitutionResult(sb.toString(), unresolved);
    }

    public static Set<String> extractPlaceholders(String input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> placeholders = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(input);
        while (matcher.find()) {
            placeholders.add(matcher.group(1).trim());
        }
        return placeholders;
    }

    public static Map<String, String> substituteHeaders(Map<String, String> headers, Map<String, String> variables, List<String> unresolvedCollector) {
        if (headers == null) {
            return Collections.emptyMap();
        }
        Map<String, String> substituted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String keySub = substitute(entry.getKey(), variables).getResult();
            SubstitutionResult valRes = substitute(entry.getValue(), variables);
            if (unresolvedCollector != null) {
                unresolvedCollector.addAll(valRes.getUnresolved());
            }
            substituted.put(keySub, valRes.getResult());
        }
        return substituted;
    }
}
