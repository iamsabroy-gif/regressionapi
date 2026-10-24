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
            String replacement = null;
            boolean resolved = false;

            if (variables.containsKey(key)) {
                replacement = variables.get(key);
                resolved = true;
            } else if (key.startsWith("env:")) {
                String envKey = key.substring(4).trim();
                replacement = resolveEnvSecret(envKey);
                if (replacement != null) {
                    resolved = true;
                }
            }

            if (resolved) {
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

    private static String resolveEnvSecret(String envKey) {
        if (envKey == null || envKey.isBlank()) {
            return null;
        }
        // 1. System property APIREG_SECRET_<KEY> (for easy test setup)
        String val = System.getProperty("APIREG_SECRET_" + envKey);
        if (val != null) return val;

        // 2. Environment variable APIREG_SECRET_<KEY>
        val = System.getenv("APIREG_SECRET_" + envKey);
        if (val != null) return val;

        // 3. Fallback direct property/env
        val = System.getProperty(envKey);
        if (val != null) return val;
        val = System.getenv(envKey);
        if (val != null) return val;

        // 4. Fallback to secrets.local.json if exists in current dir
        java.nio.file.Path localSecrets = java.nio.file.Path.of("secrets.local.json");
        if (java.nio.file.Files.exists(localSecrets)) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(localSecrets.toFile());
                if (root.has(envKey)) {
                    return root.get(envKey).asText();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
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
