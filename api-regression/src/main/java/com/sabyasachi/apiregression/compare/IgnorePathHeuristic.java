package com.sabyasachi.apiregression.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Pattern;

public class IgnorePathHeuristic {

    private static final List<Pattern> VOLATILE_PATTERNS = List.of(
            Pattern.compile("^id$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*id$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*uuid.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*guid.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*at$", Pattern.CASE_INSENSITIVE),       // createdAt, updatedAt, etc.
            Pattern.compile(".*date.*", Pattern.CASE_INSENSITIVE),     // createdDate, date, etc.
            Pattern.compile(".*time.*", Pattern.CASE_INSENSITIVE),     // timestamp, time, etc.
            Pattern.compile(".*token.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*session.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*nonce.*", Pattern.CASE_INSENSITIVE)
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static List<String> guessVolatilePaths(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            Set<String> paths = new LinkedHashSet<>();
            traverseNode(root, "$", paths);
            return new ArrayList<>(paths);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static void traverseNode(JsonNode node, String currentPath, Set<String> result) {
        if (node == null) return;

        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                String fieldPath = currentPath + "." + key;

                if (isVolatileKey(key)) {
                    result.add(fieldPath);
                }

                traverseNode(field.getValue(), fieldPath, result);
            }
        } else if (node.isArray()) {
            int idx = 0;
            for (JsonNode child : node) {
                // For arrays, sample elements
                if (idx < 5) {
                    traverseNode(child, currentPath + "[*]", result);
                }
                idx++;
            }
        }
    }

    public static boolean isVolatileKey(String key) {
        if (key == null || key.isBlank()) return false;
        for (Pattern p : VOLATILE_PATTERNS) {
            if (p.matcher(key).matches()) {
                return true;
            }
        }
        return false;
    }
}
